package com.swararia.audiolab.foh

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.swararia.audiolab.FohTheme
import com.swararia.audiolab.UiIcons

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
                    SafeModeToggleRow(
                        title = "Guard Switch (Pengaman Live)",
                        subtitle = "Konfirmasi sebelum memutus lagu yang sedang diputar",
                        checked = guardEnabled,
                        onCheckedChange = onToggleGuard,
                    )
                    HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.6f))
                    SafeModeToggleRow(
                        title = "Layar Tetap Menyala (Awake)",
                        subtitle = "Mencegah layar HP terkunci saat di meja FOH",
                        checked = keepScreenOn,
                        onCheckedChange = onToggleKeepScreenOn,
                    )
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
                    SafeModeToggleRow(
                        title = "Auto DND",
                        subtitle = "Bisukan notif sistem",
                        checked = settings.autoDnd,
                        onCheckedChange = onToggleAutoDnd,
                    )

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

                    SafeModeToggleRow(
                        title = "Audio Focus Lock",
                        subtitle = "Anti pause / duck audio lain",
                        checked = settings.focusLock,
                        onCheckedChange = onToggleFocusLock,
                    )
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

@Composable
private fun SafeModeToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
