package com.swararia.audiolab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/** No saved state, clipboard reads, camera permission, discovery, or playback ownership. */
@Composable
fun DesktopRemoteScreen(session: DesktopRemoteSession, modifier: Modifier = Modifier) {
    val state by session.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var scanFeedback by remember { mutableStateOf<String?>(null) }
    var pendingScanned by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(session, lifecycle) {
        val lease = Any()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    session.visible(lease)
                    pendingScanned?.let { scanned ->
                        pendingScanned = null
                        session.connect(scanned)
                    }
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> session.hidden(lease)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) session.visible(lease)
        onDispose {
            pendingScanned = null
            session.hidden(lease)
            lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(state.phase) {
        if (state.phase == "pending" || state.phase == "active") {
            input = ""
            scanFeedback = null
            pendingScanned = null
        }
    }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RemoteHeaderBar(state)
        if (state.phase == "disconnected") {
            DisconnectedPairingSection(
                input = input,
                scanning = scanning,
                busy = state.busy,
                scanFeedback = scanFeedback,
                errorMessage = state.message,
                onInputChange = {
                    if (it.length <= 256) {
                        input = it
                        scanFeedback = null
                        pendingScanned = null
                    }
                },
                onScanQr = {
                    if (scanning || state.busy) return@DisconnectedPairingSection
                    scanning = true
                    scanFeedback = null
                    pendingScanned = null
                    coroutineScope.launch {
                        try {
                            when (val res = DesktopRemoteQrScanner.scan(context) { scanFeedback = it }) {
                                is DesktopRemoteScanResult.Scanned -> {
                                    scanFeedback = null
                                    input = ""
                                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                        pendingScanned = null
                                        session.connect(res.rawText)
                                    } else {
                                        // Scanner overlay may briefly pause the Activity; deliver right after ON_RESUME restores visibility.
                                        pendingScanned = res.rawText
                                    }
                                }
                                DesktopRemoteScanResult.Canceled -> scanFeedback = null
                                is DesktopRemoteScanResult.Failed -> scanFeedback = res.message
                            }
                        } finally {
                            scanning = false
                        }
                    }
                },
                onConnectManual = {
                    scanFeedback = null
                    pendingScanned = null
                    session.connect(input)
                },
            )
        } else if (state.phase != "active") {
            PendingApprovalCard(
                state = state,
                onRefresh = { session.refresh() },
                onCancel = {
                    session.disconnect()
                    input = ""
                },
            )
        } else {
            ActiveRemoteControlSection(
                state = state,
                onCommand = { action -> session.command(action) },
                onRefresh = { session.refresh() },
                onDisconnect = {
                    session.disconnect()
                    input = ""
                },
            )
        }
    }
}

private data class RemoteBadge(val label: String, val highlighted: Boolean, val error: Boolean = false)

private fun resolveConnectionBadge(state: DesktopRemoteUiState): RemoteBadge? = when (state.phase) {
    "disconnected" -> null
    "connecting" -> RemoteBadge("Menghubungkan", highlighted = false)
    "pending" -> RemoteBadge("Menunggu PC", highlighted = false)
    else -> when {
        !state.fresh -> RemoteBadge("Sinkronisasi", highlighted = false)
        state.playback?.online != true -> RemoteBadge("PC Offline", highlighted = false, error = true)
        state.role == "view_only" -> RemoteBadge("View-Only", highlighted = false)
        else -> RemoteBadge("FOH Ready", highlighted = true)
    }
}

private fun resolveTransportBadge(fresh: Boolean, playback: DesktopRemotePlayback?): RemoteBadge = when {
    !fresh -> RemoteBadge("SINKRONISASI", highlighted = false)
    playback == null || !playback.online -> RemoteBadge("OFFLINE", highlighted = false, error = true)
    playback.transport == "FADING" -> RemoteBadge("FADING OUT 3S", highlighted = true)
    playback.playing || playback.transport == "PLAYING" -> RemoteBadge("PLAYING", highlighted = true)
    playback.transport == "PAUSED" -> RemoteBadge("PAUSED", highlighted = false)
    playback.transport == "STOPPED" || playback.transport == "CUED" -> RemoteBadge("CUED", highlighted = false)
    else -> RemoteBadge(playback.transport ?: "STANDBY", highlighted = false)
}

private fun formatSecondsLabel(seconds: Number?): String =
    seconds?.let { LibraryRules.durationLabel((it.toDouble() * 1000.0).toLong().coerceAtLeast(0L)) } ?: "--:--"

private fun formatOutcomeFeedback(outcome: String): Pair<String, Boolean> = when (outcome) {
    "accepted" -> "Perintah diterima, meneruskan ke pemutar PC…" to false
    "pending" -> "Menunggu konfirmasi pemutar PC…" to false
    "applied" -> "Dikonfirmasi oleh pemutar PC" to false
    "unknown" -> "Hasil belum pasti — tidak dikirim ulang otomatis" to true
    "expired" -> "Batas waktu perintah habis — cek pemutar PC" to true
    else -> "Perintah ditolak oleh pemutar PC" to true
}

@Composable
private fun RemoteHeaderBar(state: DesktopRemoteUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BrandLogo(38.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text("Desktop Remote", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = state.endpoint?.let { "Desktop • $it" } ?: "Kendalikan pemutar PC dari HP",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        resolveConnectionBadge(state)?.let { badge -> StatusPill(badge) }
    }
}

@Composable
private fun StatusPill(badge: RemoteBadge, modifier: Modifier = Modifier) {
    val containerColor = when {
        badge.error -> MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
        badge.highlighted -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        badge.error -> MaterialTheme.colorScheme.error
        badge.highlighted -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.35f)),
    ) {
        Text(
            text = badge.label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun NoticeCard(text: String, isError: Boolean = false, icon: ImageVector = UiIcons.Info) {
    val container = if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
    val content = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = container,
        border = BorderStroke(1.dp, content.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = if (isError) content else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun DisconnectedPairingSection(
    input: String,
    scanning: Boolean,
    busy: Boolean,
    scanFeedback: String?,
    errorMessage: String?,
    onInputChange: (String) -> Unit,
    onScanQr: () -> Unit,
    onConnectManual: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(UiIcons.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Text("Hubungkan ke Pemutar PC", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "Di PC: buka menu HP Remote (Android App) → Aktifkan Listener → Buat Kode Pairing, lalu pindai kode QR dari layar PC.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onScanQr,
                enabled = !busy && !scanning,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Icon(UiIcons.QrScan, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (scanning) "Menyiapkan Scanner…" else "Scan QR dari PC")
            }
            if (scanning || busy) LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
            scanFeedback?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    errorMessage?.let { NoticeCard(it, isError = true) }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Atau Pairing Manual", style = MaterialTheme.typography.titleSmall)
            Text(
                "Gunakan hanya jika kamera tidak tersedia. Jangan kirim teks pairing lewat chat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                label = { Text("Teks pairing swararia1|…") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            )
            OutlinedButton(
                onClick = onConnectManual,
                enabled = input.isNotEmpty() && !busy && !scanning,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
            ) { Text("Connect") }
        }
    }
}

@Composable
private fun PendingApprovalCard(state: DesktopRemoteUiState, onRefresh: () -> Unit, onCancel: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (state.phase == "connecting") "Menghubungkan ke PC…" else "Menunggu Persetujuan di PC",
                style = MaterialTheme.typography.titleLarge,
            )
            LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
            Text(
                if (state.phase == "connecting") {
                    "Memverifikasi identitas TLS dan mendaftarkan sesi pairing."
                } else {
                    "Di jendela Swararia pada PC Anda, klik Setujui / Approve untuk mengizinkan HP ini terhubung."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !state.busy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                ) { Text("Cek Status") }
                TextButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                ) { Text("Batalkan") }
            }
        }
    }
}

@Composable
private fun ActiveRemoteControlSection(
    state: DesktopRemoteUiState,
    onCommand: (String) -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val playback = state.playback
    val isPlaying = playback?.playing == true || playback?.transport == "PLAYING"
    val isFading = playback?.transport == "FADING"
    val supportsFadeOut = "fade_out" in state.commands
    if (!state.fresh) {
        NoticeCard("Menyegarkan status terbaru dari PC… kontrol terkunci sementara.")
    } else if (playback?.online != true) {
        NoticeCard("Pemutar web PC belum aktif. Buka halaman Swararia di browser PC.", isError = true)
    } else if (state.role == "view_only") {
        NoticeCard("Mode View-Only: Anda hanya dapat memantau status pemutar PC.")
    }
    NowPlayingDeckCard(state.fresh, playback)
    TransportDeckCard(
        state = state,
        isPlaying = isPlaying,
        isFading = isFading,
        supportsFadeOut = supportsFadeOut,
        onCommand = onCommand,
    )
    state.outcome?.let { outcome ->
        val (label, isError) = formatOutcomeFeedback(outcome)
        NoticeCard(label, isError = isError, icon = if (isError) UiIcons.Info else UiIcons.Check)
    }
    state.message?.let { message -> NoticeCard(message, isError = state.outcome == null) }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = onRefresh,
            enabled = !state.busy,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f).heightIn(min = 46.dp),
        ) {
            Icon(UiIcons.History, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Refresh")
        }
        OutlinedButton(
            onClick = onDisconnect,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f).heightIn(min = 46.dp),
        ) {
            Icon(UiIcons.Close, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Putuskan")
        }
    }
    Text(
        "Menutup layar atau pindah aplikasi tidak menghentikan audio PC. Sesi disimpan di RAM saja.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NowPlayingDeckCard(fresh: Boolean, playback: DesktopRemotePlayback?) {
    val badge = resolveTransportBadge(fresh, playback)
    val durationSec = playback?.duration?.toDouble()
    val timeSec = playback?.time?.toDouble()
    val progress = if (timeSec != null && durationSec != null && durationSec > 0.0) {
        (timeSec / durationSec).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "PEMUTAR PC",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                StatusPill(badge)
            }
            Text(
                text = playback?.title?.takeIf { it.isNotBlank() } ?: "Belum ada track dipilih di PC",
                style = MaterialTheme.typography.titleLarge,
                color = if (playback?.title.isNullOrBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatSecondsLabel(playback?.time), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatSecondsLabel(playback?.duration), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TransportDeckCard(
    state: DesktopRemoteUiState,
    isPlaying: Boolean,
    isFading: Boolean,
    supportsFadeOut: Boolean,
    onCommand: (String) -> Unit,
) {
    val waitingCommand = state.busy || state.outcome in setOf("accepted", "pending")
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "KONTROL TRANSPORT",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                if (waitingCommand) {
                    Text("Mengirim perintah…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DeckActionButton(
                    label = "Play",
                    icon = UiIcons.Play,
                    enabled = state.canControl && "play" in state.commands,
                    filled = !isPlaying,
                    outlined = false,
                    onClick = { onCommand("play") },
                    modifier = Modifier.weight(1f),
                )
                DeckActionButton(
                    label = "Pause",
                    icon = UiIcons.Pause,
                    enabled = state.canControl && "pause" in state.commands,
                    filled = isPlaying && !isFading,
                    outlined = false,
                    onClick = { onCommand("pause") },
                    modifier = Modifier.weight(1f),
                )
                DeckActionButton(
                    label = "Stop",
                    icon = UiIcons.Stop,
                    enabled = state.canControl && "stop" in state.commands,
                    filled = false,
                    outlined = true,
                    onClick = { onCommand("stop") },
                    modifier = Modifier.weight(1f),
                )
            }
            FilledTonalButton(
                onClick = { onCommand("fade_out") },
                enabled = state.canFadeOut,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (isFading) MaterialTheme.colorScheme.primary.copy(alpha = 0.24f) else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (state.canFadeOut || isFading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                border = BorderStroke(
                    1.dp,
                    if (state.canFadeOut || isFading) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .semantics { stateDescription = if (isFading) "Fading" else "Idle" },
            ) {
                Icon(UiIcons.Fade, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isFading) "Fading Out (3s)…" else "Fade Out 3s",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (isFading) LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
            if (state.role == "control" && !supportsFadeOut) {
                Text(
                    "Fade Out 3s belum aktif di server PC saat ini (restart server Swararia di PC).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DeckActionButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    filled: Boolean,
    outlined: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content: @Composable RowScope.() -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
        }
    }
    val shape = RoundedCornerShape(14.dp)
    val padding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
    val sizedModifier = modifier.heightIn(min = 60.dp)
    when {
        filled -> Button(onClick = onClick, enabled = enabled, shape = shape, contentPadding = padding, modifier = sizedModifier, content = content)
        outlined -> OutlinedButton(onClick = onClick, enabled = enabled, shape = shape, contentPadding = padding, modifier = sizedModifier, content = content)
        else -> FilledTonalButton(onClick = onClick, enabled = enabled, shape = shape, contentPadding = padding, modifier = sizedModifier, content = content)
    }
}
