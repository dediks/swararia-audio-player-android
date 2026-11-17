package com.swararia.audiolab.phasechecker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.swararia.audiolab.CompactCircleButton
import com.swararia.audiolab.FohFilterChip
import com.swararia.audiolab.FohHeaderChipButton
import com.swararia.audiolab.FohSegmentedBar
import com.swararia.audiolab.FohTheme
import com.swararia.audiolab.UiIcons
import com.swararia.audiolab.foh.AudioOutputKind
import com.swararia.audiolab.foh.AudioOutputRoute
import com.swararia.audiolab.foh.rememberPhoneMediaVolumeState
import com.swararia.audiolab.foh.showSystemMediaVolumePanel

@Composable
private fun rememberPhaseCheckerViewModel(): PhaseCheckerViewModel {
    val context = LocalContext.current.applicationContext
    val factory = remember(context) { PhaseCheckerViewModelFactory.fromContext(context) }
    return viewModel(factory = factory)
}

@Composable
internal fun PhaseCheckerScreen(
    onBack: () -> Unit,
    onPauseOtherAudio: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PhaseCheckerViewModel = rememberPhaseCheckerViewModel(),
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by viewModel.state.collectAsStateWithLifecycle()
    val phoneVolume = rememberPhoneMediaVolumeState()

    var topology by rememberSaveable {
        mutableStateOf(
            if (viewModel.isTransmitterActiveAtEntry()) PhasePhoneTopology.TWO_PHONES
            else PhasePhoneTopology.SINGLE_PHONE,
        )
    }
    var singleSubMode by rememberSaveable { mutableStateOf(PhaseSingleSubMode.PULSE_AND_MIC) }
    var twoPhoneRole by rememberSaveable { mutableStateOf(PhaseTwoPhoneRole.RECEIVER) }
    var selectedSpeakerSlot by rememberSaveable {
        mutableStateOf(PhaseCheckerRules.DEFAULT_SPEAKER_SLOTS.first())
    }
    var permissionDeniedOnce by remember { mutableStateOf(false) }
    var pendingStartSingleAllInOne by remember { mutableStateOf(false) }
    var promptedNotifOnce by rememberSaveable { mutableStateOf(false) }
    var pendingTxAfterNotif by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showGuideDialog by rememberSaveable { mutableStateOf(false) }

    val preserveTxInBackground by rememberUpdatedState(
        topology == PhasePhoneTopology.TWO_PHONES && twoPhoneRole == PhaseTwoPhoneRole.TRANSMITTER,
    )
    LaunchedEffect(preserveTxInBackground) {
        viewModel.setPreserveTxInBackground(preserveTxInBackground)
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ ->
        pendingTxAfterNotif?.invoke()
        pendingTxAfterNotif = null
    }

    val launchTxWithOptionalNotif: (() -> Unit) -> Unit = { startAction ->
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !promptedNotifOnce &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            promptedNotifOnce = true
            pendingTxAfterNotif = startAction
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startAction()
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            permissionDeniedOnce = false
            if (pendingStartSingleAllInOne) {
                pendingStartSingleAllInOne = false
                onPauseOtherAudio()
                if (!state.mode.usesMicPulse) viewModel.setMode(PhaseTestSignalMode.PULSE_FULL_RANGE)
                viewModel.startMicAnalysis()
                viewModel.startGenerator()
            } else {
                viewModel.startMicAnalysis()
            }
        } else {
            pendingStartSingleAllInOne = false
            permissionDeniedOnce = true
        }
    }

    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.refreshOutputRoute()
                Lifecycle.Event.ON_STOP -> viewModel.onHostStop(preserveTxInBackground)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            viewModel.onScreenDisposed(preserveTxInBackground)
        }
    }

    val startSinglePulseAndMic: () -> Unit = {
        if (!state.mode.usesMicPulse) viewModel.setMode(PhaseTestSignalMode.PULSE_FULL_RANGE)
        if (viewModel.hasMicPermission()) {
            onPauseOtherAudio()
            viewModel.startMicAnalysis()
            viewModel.startGenerator()
        } else {
            pendingStartSingleAllInOne = true
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val toggleRxMicOnly: () -> Unit = {
        if (state.micListening) {
            viewModel.stopMicAnalysis()
        } else {
            if (!state.mode.usesMicPulse) viewModel.setMode(PhaseTestSignalMode.PULSE_FULL_RANGE)
            if (viewModel.hasMicPermission()) {
                viewModel.startMicAnalysis()
            } else {
                pendingStartSingleAllInOne = false
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val foh = FohTheme.colors

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "phase-header") {
            PhaseCheckerHeader(
                outputRoute = state.outputRoute,
                onBack = {
                    viewModel.onScreenDisposed(preserveTxInBackground)
                    onBack()
                },
                onRefreshRoute = viewModel::refreshOutputRoute,
                onOpenGuide = { showGuideDialog = true },
            )
        }

        item(key = "phase-topology") {
            FohSegmentedBar(
                options = PhasePhoneTopology.entries,
                selected = topology,
                label = { it.label },
                onSelect = { next ->
                    topology = next
                    if (next == PhasePhoneTopology.SINGLE_PHONE && singleSubMode == PhaseSingleSubMode.STEREO_EAR) {
                        viewModel.stopMicAnalysis()
                    } else if (next == PhasePhoneTopology.TWO_PHONES) {
                        if (twoPhoneRole == PhaseTwoPhoneRole.RECEIVER) {
                            viewModel.stopGenerator()
                            if (!state.mode.usesMicPulse) viewModel.setMode(PhaseTestSignalMode.PULSE_FULL_RANGE)
                        } else {
                            viewModel.stopMicAnalysis()
                        }
                    }
                },
            )
        }

        if (topology == PhasePhoneTopology.SINGLE_PHONE) {
            item(key = "phase-single-chips") {
                SingleModeChipRow(
                    singleSubMode = singleSubMode,
                    pulseMode = state.mode,
                    onSelectSubMode = { next ->
                        singleSubMode = next
                        if (next == PhaseSingleSubMode.STEREO_EAR) {
                            viewModel.stopMicAnalysis()
                            if (state.mode.usesMicPulse) viewModel.setMode(PhaseTestSignalMode.STEREO_IN_PHASE)
                        } else if (!state.mode.usesMicPulse) {
                            viewModel.stopGenerator()
                            viewModel.setMode(PhaseTestSignalMode.PULSE_FULL_RANGE)
                        }
                    },
                    onSelectPulseMode = viewModel::setMode,
                )
            }
        } else {
            item(key = "phase-two-role") {
                FohSegmentedBar(
                    options = PhaseTwoPhoneRole.entries,
                    selected = twoPhoneRole,
                    label = { role ->
                        val activeDot = (role == PhaseTwoPhoneRole.TRANSMITTER && state.generatorRunning) ||
                            (role == PhaseTwoPhoneRole.RECEIVER && state.micListening)
                        if (activeDot) "● ${role.label}" else role.label
                    },
                    onSelect = { role ->
                        twoPhoneRole = role
                        if (role == PhaseTwoPhoneRole.TRANSMITTER) {
                            viewModel.stopMicAnalysis()
                        } else {
                            viewModel.stopGenerator()
                            if (!state.mode.usesMicPulse) viewModel.setMode(PhaseTestSignalMode.PULSE_FULL_RANGE)
                        }
                    },
                )
            }
        }

        if (state.errorMessage != null) {
            item(key = "phase-error") {
                AlertMessageBanner(
                    message = state.errorMessage.orEmpty(),
                    bg = foh.alertBg,
                    border = foh.alertBorder,
                    accent = foh.alertAccent,
                    actionLabel = "Tutup",
                    onAction = viewModel::clearError,
                )
            }
        }

        val showsMicUi = (topology == PhasePhoneTopology.SINGLE_PHONE && singleSubMode == PhaseSingleSubMode.PULSE_AND_MIC) ||
            (topology == PhasePhoneTopology.TWO_PHONES && twoPhoneRole == PhaseTwoPhoneRole.RECEIVER)
        val showsTxUi = topology == PhasePhoneTopology.SINGLE_PHONE ||
            (topology == PhasePhoneTopology.TWO_PHONES && twoPhoneRole == PhaseTwoPhoneRole.TRANSMITTER)
        if (permissionDeniedOnce && !viewModel.hasMicPermission() && showsMicUi) {
            item(key = "phase-mic-denied") {
                AlertMessageBanner(
                    message = "Izin mikrofon belum diberikan. Buka setelan aplikasi agar alat dapat mendengar impuls speaker.",
                    bg = foh.warnBg,
                    border = foh.warnBorder,
                    accent = foh.warnAccent,
                    actionLabel = "Setelan",
                    onAction = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                    },
                )
            }
        }
        if (showsTxUi && phoneVolume.shouldWarnPlayer) {
            item(key = "phase-phone-volume-warning") {
                AlertMessageBanner(
                    message = if (phoneVolume.isMuted) {
                        "Volume Media HP 0% (Bisu) — sinyal uji tidak akan keluar sebelum volume HP dinaikkan."
                    } else {
                        "Volume Media HP rendah (${phoneVolume.percent}%) — level sinyal uji terbatasi oleh volume HP."
                    },
                    bg = if (phoneVolume.isMuted) foh.alertBg else foh.warnBg,
                    border = if (phoneVolume.isMuted) foh.alertBorder else foh.warnBorder,
                    accent = if (phoneVolume.isMuted) foh.alertAccent else foh.warnAccent,
                    actionLabel = "Panel HP",
                    onAction = { showSystemMediaVolumePanel(context) },
                )
            }
        }

        when {
            topology == PhasePhoneTopology.SINGLE_PHONE && singleSubMode == PhaseSingleSubMode.PULSE_AND_MIC -> {
                val active = state.generatorRunning || state.micListening
                item(key = "single-readout") {
                    PolarityReadoutCard(state = state)
                }
                item(key = "single-cta") {
                    PhasePrimaryActionButton(
                        active = active,
                        activeLabel = "Hentikan Uji (TX + Mic)",
                        idleLabel = "Mulai Uji (TX + Mic)",
                        idleIcon = UiIcons.Play,
                        onClick = {
                            if (active) {
                                viewModel.stopGenerator()
                                viewModel.stopMicAnalysis()
                            } else {
                                startSinglePulseAndMic()
                            }
                        },
                    )
                }
                item(key = "single-controls") {
                    SinglePhoneSecondaryCard(
                        state = state,
                        selectedSpeakerSlot = selectedSpeakerSlot,
                        onSelectSpeakerSlot = { selectedSpeakerSlot = it },
                        onGainChange = viewModel::setGeneratorGain,
                        onSelectSensitivity = viewModel::setSensitivity,
                        onLockOrClearRef = viewModel::toggleReferenceLock,
                        onToggleInvertMic = viewModel::toggleMicInverted,
                        onSaveResult = { viewModel.recordCurrentSpeaker(selectedSpeakerSlot) },
                    )
                }
            }

            topology == PhasePhoneTopology.SINGLE_PHONE && singleSubMode == PhaseSingleSubMode.STEREO_EAR -> {
                item(key = "single-stereo-ear") {
                    StereoEarCheckerCard(
                        state = state,
                        onSelectStereoMode = viewModel::setMode,
                        onGainChange = viewModel::setGeneratorGain,
                        onTogglePlay = {
                            if (state.generatorRunning) {
                                viewModel.stopGenerator()
                            } else {
                                launchTxWithOptionalNotif {
                                    if (state.mode.usesMicPulse) viewModel.setMode(PhaseTestSignalMode.STEREO_IN_PHASE)
                                    onPauseOtherAudio()
                                    viewModel.startGenerator()
                                }
                            }
                        },
                    )
                }
            }

            topology == PhasePhoneTopology.TWO_PHONES && twoPhoneRole == PhaseTwoPhoneRole.TRANSMITTER -> {
                item(key = "two-tx-card") {
                    TwoPhoneTransmitterCard(
                        state = state,
                        onSelectMode = viewModel::setMode,
                        onGainChange = viewModel::setGeneratorGain,
                        onToggleTransmit = {
                            if (state.generatorRunning) {
                                viewModel.stopGenerator()
                            } else {
                                launchTxWithOptionalNotif {
                                    onPauseOtherAudio()
                                    viewModel.startGenerator()
                                }
                            }
                        },
                        onShareWav = { viewModel.shareCurrentSignalWavAsync() },
                    )
                }
            }

            else -> {
                item(key = "two-rx-readout") {
                    PolarityReadoutCard(state = state)
                }
                item(key = "two-rx-cta") {
                    PhasePrimaryActionButton(
                        active = state.micListening,
                        activeLabel = "Hentikan Deteksi Mic",
                        idleLabel = "Mulai Deteksi Mic",
                        idleIcon = PhaseOnlyIcons.Mic,
                        onClick = toggleRxMicOnly,
                    )
                }
                item(key = "two-rx-controls") {
                    TwoPhoneReceiverSecondaryCard(
                        state = state,
                        selectedSpeakerSlot = selectedSpeakerSlot,
                        onSelectSpeakerSlot = { selectedSpeakerSlot = it },
                        onSelectPulseMode = viewModel::setMode,
                        onSelectSensitivity = viewModel::setSensitivity,
                        onLockOrClearRef = viewModel::toggleReferenceLock,
                        onToggleInvertMic = viewModel::toggleMicInverted,
                        onSaveResult = { viewModel.recordCurrentSpeaker(selectedSpeakerSlot) },
                    )
                }
            }
        }

        if (state.checkEntries.isNotEmpty() && showsMicUi) {
            item(key = "phase-checklist") {
                SpeakerChecklistCard(
                    entries = state.checkEntries,
                    onRemoveEntry = viewModel::removeCheckEntry,
                    onClearAll = viewModel::clearChecklist,
                )
            }
        }
    }

    if (showGuideDialog) {
        PhaseFieldGuideDialog(
            topology = topology,
            singleSubMode = singleSubMode,
            twoPhoneRole = twoPhoneRole,
            onDismiss = { showGuideDialog = false },
        )
    }
}

@Composable
private fun PhaseCheckerHeader(
    outputRoute: AudioOutputRoute,
    onBack: () -> Unit,
    onRefreshRoute: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    val routeIcon = when (outputRoute.kind) {
        AudioOutputKind.LINE_OR_USB -> UiIcons.Plug
        AudioOutputKind.BLUETOOTH -> UiIcons.Bluetooth
        AudioOutputKind.SPEAKER -> UiIcons.VolumeUp
    }
    val routeLabel = when (outputRoute.kind) {
        AudioOutputKind.LINE_OR_USB -> "USB/Line"
        AudioOutputKind.BLUETOOTH -> "BT Audio"
        AudioOutputKind.SPEAKER -> "Speaker"
    }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CompactCircleButton(
            icon = UiIcons.Back,
            description = "Kembali ke More",
            onClick = onBack,
            size = 36.dp,
            iconSize = 20.dp,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
        Text(
            text = "Phase Checker",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        FohHeaderChipButton(
            label = routeLabel,
            icon = routeIcon,
            active = outputRoute.kind == AudioOutputKind.LINE_OR_USB,
            onClick = onRefreshRoute,
        )
        CompactCircleButton(
            icon = UiIcons.Info,
            description = "Panduan lapangan",
            onClick = onOpenGuide,
            size = 36.dp,
            iconSize = 20.dp,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SingleModeChipRow(
    singleSubMode: PhaseSingleSubMode,
    pulseMode: PhaseTestSignalMode,
    onSelectSubMode: (PhaseSingleSubMode) -> Unit,
    onSelectPulseMode: (PhaseTestSignalMode) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PhaseSingleSubMode.entries.forEach { sub ->
            FohFilterChip(
                label = sub.label,
                selected = singleSubMode == sub,
                onClick = { onSelectSubMode(sub) },
            )
        }
        if (singleSubMode == PhaseSingleSubMode.PULSE_AND_MIC) {
            listOf(PhaseTestSignalMode.PULSE_FULL_RANGE, PhaseTestSignalMode.PULSE_SUB).forEach { mode ->
                FohFilterChip(
                    label = if (mode == PhaseTestSignalMode.PULSE_FULL_RANGE) "Full" else "Sub LF",
                    selected = pulseMode == mode,
                    onClick = { onSelectPulseMode(mode) },
                )
            }
        }
    }
}

@Composable
internal fun PhasePrimaryActionButton(
    active: Boolean,
    activeLabel: String,
    idleLabel: String,
    idleIcon: ImageVector,
    onClick: () -> Unit,
) {
    val foh = FohTheme.colors
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) foh.alertAccent else MaterialTheme.colorScheme.primary,
            contentColor = if (active) Color.Black else MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Icon(
            imageVector = if (active) UiIcons.Stop else idleIcon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(if (active) activeLabel else idleLabel, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PhaseFieldGuideDialog(
    topology: PhasePhoneTopology,
    singleSubMode: PhaseSingleSubMode,
    twoPhoneRole: PhaseTwoPhoneRole,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(UiIcons.Info, null) },
        title = {
            Text(
                when {
                    topology == PhasePhoneTopology.SINGLE_PHONE && singleSubMode == PhaseSingleSubMode.PULSE_AND_MIC ->
                        "Panduan 1 HP (Pulsa + Mic)"
                    topology == PhasePhoneTopology.SINGLE_PHONE ->
                        "Panduan 1 HP (Stereo L/R)"
                    twoPhoneRole == PhaseTwoPhoneRole.TRANSMITTER ->
                        "Panduan HP 1 · Pengirim (TX)"
                    else ->
                        "Panduan HP 2 · Penerima (RX)"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    topology == PhasePhoneTopology.SINGLE_PHONE && singleSubMode == PhaseSingleSubMode.PULSE_AND_MIC -> {
                        Text("1. Hubungkan output HP ke speaker/mixer memakai kabel (hindari Bluetooth karena mengubah fasa).", style = MaterialTheme.typography.bodySmall)
                        Text("2. Dekatkan mic HP ~20–30 cm tepat di depan woofer, tekan Mulai Uji, lalu tekan Kunci Ref A di speaker pertama.", style = MaterialTheme.typography.bodySmall)
                        Text("3. Pindah ke speaker lain dengan jarak serupa lalu tekan Simpan Hasil untuk mencatat daftar speaker.", style = MaterialTheme.typography.bodySmall)
                    }

                    topology == PhasePhoneTopology.SINGLE_PHONE -> {
                        Text("1. Colok HP ke input Stereo L/R mixer dan buka kedua fader Master seimbang.", style = MaterialTheme.typography.bodySmall)
                        Text("2. Berdiri di posisi tengah antara speaker Kiri dan Kanan.", style = MaterialTheme.typography.bodySmall)
                        Text("3. Pada L=R suara seharusnya terpusat padat. Jika justru lebih padat saat L=-R, salah satu sisi kabel terbalik.", style = MaterialTheme.typography.bodySmall)
                    }

                    twoPhoneRole == PhaseTwoPhoneRole.TRANSMITTER -> {
                        Text("1. Colok HP 1 ke channel Line/AUX di mixer (atau bagikan file .wav untuk laptop FOH).", style = MaterialTheme.typography.bodySmall)
                        Text("2. Pilih Pulsa Full atau Pulsa Sub, atur level aman, lalu mulai pancaran.", style = MaterialTheme.typography.bodySmall)
                        Text("3. HP 1 aman ditinggal walau layar terkunci; berjalanlah membawa HP 2 (mode Penerima RX).", style = MaterialTheme.typography.bodySmall)
                    }

                    else -> {
                        Text("1. Pastikan HP 1 (atau pemutar FOH) memancarkan pulsa uji via kabel, lalu samakan Target Speaker (Full / Sub LF) di HP 2.", style = MaterialTheme.typography.bodySmall)
                        Text("2. Di depan Speaker Acuan (~20–30 cm dari woofer), nyalakan Mic lalu ketuk Kunci Ref A.", style = MaterialTheme.typography.bodySmall)
                        Text("3. Keliling ke speaker lain dengan HP 2 yang sama, lalu ketuk Simpan Hasil untuk rekap.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Mengerti") }
        },
    )
}

@Composable
private fun AlertMessageBanner(
    message: String,
    bg: Color,
    border: Color,
    accent: Color,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(UiIcons.Warning, null, tint = accent, modifier = Modifier.size(16.dp))
            Text(
                message,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = accent,
            )
            TextButton(onClick = onAction) {
                Text(actionLabel, color = accent)
            }
        }
    }
}
