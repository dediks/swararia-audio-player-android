package com.swararia.audiolab

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * Tactile 2-bank x 8-slot FOH Soundboard panel rendered inside the mid deck when PADS view is active.
 */
@Composable
internal fun FohSoundboardPanel(
    soundboardState: SoundboardUiState,
    tracksById: Map<Long, AudioTrack>,
    onSelectBank: (Int) -> Unit,
    onTriggerPad: (Int, Int) -> Unit,
    onOpenAssignSlot: (Int, Int) -> Unit,
    onOpenEditSlot: (SoundPadSlot) -> Unit,
    onTogglePadDuck: (Boolean) -> Unit,
    onStopAllPads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FohTheme.colors
    val selectedBank = soundboardState.selectedBank.coerceIn(0, SoundboardRules.BANK_COUNT - 1)
    val bankSlots = remember(soundboardState.slots, selectedBank) {
        soundboardState.slots.filter { it.bankIndex == selectedBank }
            .sortedBy { it.slotIndex }
    }
    val hasActivePads = soundboardState.activeSlots.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surfaceDeep)
                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(10.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (bank in 0 until SoundboardRules.BANK_COUNT) {
                    val active = bank == selectedBank
                    Surface(
                        onClick = { onSelectBank(bank) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (active) colors.safeBg else Color.Transparent,
                        border = if (active) BorderStroke(1.dp, colors.safeBorder) else null,
                    ) {
                        Text(
                            text = SoundboardRules.bankLabel(bank),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            color = if (active) colors.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    onClick = { onTogglePadDuck(!soundboardState.padDuckEnabled) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (soundboardState.padDuckEnabled) colors.warnBg else colors.surfaceCard,
                    border = BorderStroke(
                        1.dp,
                        if (soundboardState.padDuckEnabled) colors.warnBorder else colors.borderSubtle,
                    ),
                ) {
                    Text(
                        text = if (soundboardState.padDuckEnabled) "Duck -12dB" else "Duck Off",
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.SemiBold,
                        color = if (soundboardState.padDuckEnabled) colors.warnAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                    )
                }

                Surface(
                    onClick = onStopAllPads,
                    enabled = hasActivePads,
                    shape = RoundedCornerShape(10.dp),
                    color = if (hasActivePads) colors.alertBg else colors.surfaceCard,
                    border = BorderStroke(
                        1.dp,
                        if (hasActivePads) colors.alertBorder else colors.borderSubtle,
                    ),
                ) {
                    Box(
                        modifier = Modifier.size(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = UiIcons.Stop,
                            contentDescription = "Stop semua pad",
                            tint = if (hasActivePads) colors.alertAccent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val rows = SoundboardRules.SLOTS_PER_BANK / 2
            for (rowIndex in 0 until rows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (colIndex in 0 until 2) {
                        val slotIdx = rowIndex * 2 + colIndex
                        val slot = bankSlots.getOrNull(slotIdx)
                            ?: SoundPadSlot(bankIndex = selectedBank, slotIndex = slotIdx)
                        val key = PadKey(selectedBank, slotIdx)
                        val track = slot.trackId?.let { tracksById[it] }
                        SoundPadTile(
                            slot = slot,
                            track = track,
                            playing = key in soundboardState.activeSlots,
                            loading = key in soundboardState.loadingSlots,
                            onTap = {
                                if (slot.isAssigned && track != null) {
                                    onTriggerPad(selectedBank, slotIdx)
                                } else {
                                    onOpenAssignSlot(selectedBank, slotIdx)
                                }
                            },
                            onLongPress = {
                                if (slot.isAssigned && track != null) {
                                    onOpenEditSlot(slot)
                                } else {
                                    onOpenAssignSlot(selectedBank, slotIdx)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SoundPadTile(
    slot: SoundPadSlot,
    track: AudioTrack?,
    playing: Boolean,
    loading: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FohTheme.colors
    val assigned = slot.isAssigned && track != null
    val bgColor = when {
        playing -> colors.safeBg
        assigned -> colors.surfaceCard
        else -> colors.surfaceDeep
    }
    val borderColor = when {
        playing -> colors.safeAccent
        assigned -> colors.borderSubtle
        else -> colors.borderSubtle.copy(alpha = 0.65f)
    }

    Box(
        modifier = modifier
            .height(74.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(if (playing) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (!assigned) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "P${slot.slotIndex + 1}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    )
                    Icon(
                        imageVector = UiIcons.Plus,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    text = "Isi Pad",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    maxLines = 1,
                )
            }
        } else {
            val title = slot.label.ifBlank { SoundboardRules.defaultPadLabel(track!!.displayName) }
            val trimActive = abs(SoundboardRules.clampTrimDb(slot.trimDb)) >= 0.5f
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "P${slot.slotIndex + 1}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.Bold,
                            color = if (playing) colors.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (trimActive) {
                            Text(
                                text = SoundboardRules.formatTrimDb(slot.trimDb),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontFeatureSettings = "tnum",
                                ),
                                color = colors.warnAccent,
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (playing) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(colors.safeAccent),
                            )
                            Text(
                                text = "ON",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = colors.safeAccent,
                            )
                        } else if (loading) {
                            Text(
                                text = "...",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.warnAccent,
                            )
                        } else {
                            Text(
                                text = LocalFohRules.formatCountdown(track?.durationMs),
                                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                            )
                        }
                    }
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Bottom sheet track selector so operators can assign any Library track onto a soundboard slot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SoundPadTrackPickerSheet(
    slotNumber: Int,
    tracks: List<AudioTrack>,
    onPickTrack: (AudioTrack) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FohTheme.colors
    var query by remember { mutableStateOf("") }
    val filteredTracks = remember(tracks, query) {
        val needle = query.trim().lowercase()
        val matched = if (needle.isEmpty()) {
            tracks
        } else {
            tracks.filter {
                UiPresentation.title(it.displayName).lowercase().contains(needle) ||
                    it.artist.orEmpty().lowercase().contains(needle)
            }
        }
        matched.sortedWith(
            compareBy<AudioTrack> { if (SoundboardRules.shouldUseSoundPool(it.durationMs)) 0 else 1 }
                .thenBy { UiPresentation.title(it.displayName).lowercase() },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceCard,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Pilih Audio · Pad $slotNumber",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(UiIcons.Close, contentDescription = "Tutup", modifier = Modifier.size(18.dp))
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Cari audio...") },
                leadingIcon = { Icon(UiIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.fillMaxWidth(),
            )

            if (filteredTracks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (tracks.isEmpty()) "Belum ada file di Library" else "Tidak ditemukan",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(filteredTracks, key = { it.id }) { track ->
                        val isFast = SoundboardRules.shouldUseSoundPool(track.durationMs)
                        Surface(
                            onClick = { onPickTrack(track) },
                            shape = RoundedCornerShape(10.dp),
                            color = colors.surfaceDeep,
                            border = BorderStroke(1.dp, colors.borderSubtle),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = UiPresentation.title(track.displayName),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val sub = track.artist?.takeIf { it.isNotBlank() }
                                    if (sub != null) {
                                        Text(
                                            text = sub,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (isFast) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = colors.safeBg,
                                            border = BorderStroke(1.dp, colors.safeBorder),
                                        ) {
                                            Text(
                                                text = "FAST",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                fontWeight = FontWeight.Bold,
                                                color = colors.safeAccent,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                    Text(
                                        text = LocalFohRules.formatCountdown(track.durationMs),
                                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact pad editor opened on long-press to rename label, trim level, replace audio, or clear pad.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SoundPadEditSheet(
    slot: SoundPadSlot,
    onSave: (label: String, trimDb: Float) -> Unit,
    onReplaceAudio: () -> Unit,
    onClearSlot: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FohTheme.colors
    var label by remember(slot) { mutableStateOf(slot.label) }
    var trimDb by remember(slot) { mutableFloatStateOf(SoundboardRules.clampTrimDb(slot.trimDb)) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceCard,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Pad ${slot.slotIndex + 1} · ${SoundboardRules.bankLabel(slot.bankIndex)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(UiIcons.Close, contentDescription = "Tutup", modifier = Modifier.size(18.dp))
                }
            }

            OutlinedTextField(
                value = label,
                onValueChange = { label = it.take(28) },
                singleLine = true,
                label = { Text("Label Pad") },
                modifier = Modifier.fillMaxWidth(),
            )

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.surfaceDeep,
                border = BorderStroke(1.dp, colors.borderSubtle),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Pad Trim", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = SoundboardRules.formatTrimDb(trimDb),
                            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.Bold,
                            color = colors.safeAccent,
                        )
                    }
                    Slider(
                        value = trimDb,
                        onValueChange = { trimDb = kotlin.math.round(it) },
                        valueRange = SoundboardRules.TRIM_MIN_DB..SoundboardRules.TRIM_MAX_DB,
                        steps = 17,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.safeAccent,
                            activeTrackColor = colors.safeAccent,
                        ),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onReplaceAudio,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Ganti")
                }
                OutlinedButton(
                    onClick = onClearSlot,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.alertAccent),
                    border = BorderStroke(1.dp, colors.alertBorder),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(UiIcons.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Hapus")
                }
                Button(
                    onClick = { onSave(label, trimDb) },
                    modifier = Modifier.weight(1.2f),
                ) {
                    Text("Simpan")
                }
            }
        }
    }
}

/**
 * Minimalist FOH Safe Mode bottom sheet covering Mode Panggung, Auto DND, Audio Focus Lock, and routing shortcuts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FohSafeModeSheet(
    settings: FohSafeModeSettings,
    dndGranted: Boolean,
    route: AudioOutputRoute,
    keepScreenOn: Boolean = true,
    onToggleKeepScreenOn: (Boolean) -> Unit = {},
    guardEnabled: Boolean = true,
    onToggleGuard: (Boolean) -> Unit = {},
    onToggleEnabled: (Boolean) -> Unit,
    onToggleAutoDnd: (Boolean) -> Unit,
    onToggleFocusLock: (Boolean) -> Unit,
    onRequestDndPermission: () -> Unit,
    onOpenSamsungSeparateAppSound: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FohTheme.colors
    val needsDndPerm = FohSafeModeRules.needsDndPermissionPrompt(settings, dndGranted)
    val btWarn = FohSafeModeRules.shouldWarnBluetooth(route)
    val isSamsung = FohSafeModeRules.isSamsungManufacturer(Build.MANUFACTURER)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceCard,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Setup FOH (Pra-Acara)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (route.kind == AudioOutputKind.SPEAKER) colors.warnBg else colors.safeBg,
                border = BorderStroke(1.dp, if (route.kind == AudioOutputKind.SPEAKER) colors.warnBorder else colors.safeBorder),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = route.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (route.kind == AudioOutputKind.SPEAKER) colors.warnAccent else colors.safeAccent,
                    )
                    Text(
                        text = route.detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.surfaceDeep,
                border = BorderStroke(1.dp, colors.borderSubtle),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Guard Switch (Pengaman Live)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "Konfirmasi sebelum memutus lagu yang sedang diputar",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = guardEnabled, onCheckedChange = onToggleGuard)
                    }

                    HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.6f))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Layar Tetap Menyala (Awake)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "Mencegah layar HP terkunci saat di meja FOH",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = keepScreenOn, onCheckedChange = onToggleKeepScreenOn)
                    }
                }
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (settings.enabled) colors.safeBg else colors.surfaceDeep,
                border = BorderStroke(1.dp, if (settings.enabled) colors.safeBorder else colors.borderSubtle),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (settings.enabled) UiIcons.VolumeMuted else UiIcons.Shield,
                            contentDescription = null,
                            tint = if (settings.enabled) colors.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Column {
                            Text(
                                text = "FOH Safe Mode",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = if (settings.enabled) "Mode Panggung Aktif" else "Nonaktif",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (settings.enabled) colors.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Switch(
                        checked = settings.enabled,
                        onCheckedChange = onToggleEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.surfaceDeep,
                            checkedTrackColor = colors.safeAccent,
                        ),
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.surfaceDeep,
                border = BorderStroke(1.dp, colors.borderSubtle),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto DND", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "Bisukan notif sistem",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = settings.autoDnd, onCheckedChange = onToggleAutoDnd)
                    }

                    if (needsDndPerm) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.warnBg)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Butuh izin DND OS",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.warnAccent,
                            )
                            Surface(
                                onClick = onRequestDndPermission,
                                shape = RoundedCornerShape(8.dp),
                                color = colors.warnAccent,
                            ) {
                                Text(
                                    text = "Izinkan",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.surfaceDeep,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.6f))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Audio Focus Lock", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "Anti pause / duck audio lain",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = settings.focusLock, onCheckedChange = onToggleFocusLock)
                    }
                }
            }

            if (btWarn) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.btBg,
                    border = BorderStroke(1.dp, colors.btBorder),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(UiIcons.Bluetooth, contentDescription = null, tint = colors.btAccent, modifier = Modifier.size(18.dp))
                        Text(
                            text = "Bluetooth aktif · ada latency",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.btAccent,
                        )
                    }
                }
            }

            if (isSamsung) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.surfaceDeep,
                    border = BorderStroke(1.dp, colors.borderSubtle),
                    modifier = Modifier.clickable(onClick = onOpenSamsungSeparateAppSound),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = "Separate App Sound",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Pisah notif ke speaker HP (Samsung)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = UiIcons.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}
