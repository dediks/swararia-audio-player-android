package com.swararia.audiolab

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal enum class MoreToolRoute {
    HUB,
    PHASE_CHECKER,
    BRAND_STUDIO,
}

@Composable
internal fun MoreScreen(
    tracks: List<AudioTrack>,
    playlists: List<LibraryPlaylist>,
    lastRename: RenameRecord?,
    ytMusicState: YoutubeMusicAccountState,
    playbackSettings: PlaybackSettingsState,
    remoteServerState: RemoteServerUiState,
    jobs: List<DownloadJob>,
    activeTool: MoreToolRoute,
    onSelectTool: (MoreToolRoute) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRenameHistory: () -> Unit,
    onPauseOtherAudio: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenStageRta: () -> Unit = {},
) {
    when (activeTool) {
        MoreToolRoute.PHASE_CHECKER -> PhaseCheckerScreen(
            onBack = { onSelectTool(MoreToolRoute.HUB) },
            onPauseOtherAudio = onPauseOtherAudio,
            modifier = modifier,
        )

        MoreToolRoute.BRAND_STUDIO -> BrandSkinStudioScreen(
            onBack = { onSelectTool(MoreToolRoute.HUB) },
            onOpenStageRta = onOpenStageRta,
            modifier = modifier,
        )

        MoreToolRoute.HUB -> MoreHubScreen(
            tracks = tracks,
            playlists = playlists,
            lastRename = lastRename,
            ytMusicState = ytMusicState,
            playbackSettings = playbackSettings,
            remoteServerState = remoteServerState,
            jobs = jobs,
            onOpenPhaseChecker = { onSelectTool(MoreToolRoute.PHASE_CHECKER) },
            onOpenBrandStudio = { onSelectTool(MoreToolRoute.BRAND_STUDIO) },
            onOpenStageRta = onOpenStageRta,
            onOpenSettings = onOpenSettings,
            onOpenRenameHistory = onOpenRenameHistory,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoreHubScreen(
    tracks: List<AudioTrack>,
    playlists: List<LibraryPlaylist>,
    lastRename: RenameRecord?,
    ytMusicState: YoutubeMusicAccountState,
    playbackSettings: PlaybackSettingsState,
    remoteServerState: RemoteServerUiState,
    jobs: List<DownloadJob>,
    onOpenPhaseChecker: () -> Unit,
    onOpenBrandStudio: () -> Unit,
    onOpenStageRta: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRenameHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    var outputRoute by remember {
        mutableStateOf(audioManager.activeFohOutputRoute())
    }
    val txState by PhaseTransmitterService.state.collectAsStateWithLifecycle()

    DisposableEffect(lifecycle, audioManager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                outputRoute = audioManager.activeFohOutputRoute()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val foh = FohTheme.colors
    val brand = FohTheme.brand
    val ytSummary = when {
        ytMusicState.needsReauth -> "YT Music perlu login ulang"
        ytMusicState.connected && ytMusicState.enabledForDownloads -> "YT Music aktif"
        ytMusicState.connected -> "YT Music nonaktif"
        else -> "YT Music belum terhubung"
    }
    val activeJobCount = remember(jobs) {
        jobs.count { it.status == "QUEUED" || it.status == "RUNNING" }
    }
    val appVersion = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "0.6.0-spike"
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "more-header") {
            MoreHeader(
                txRunning = txState.running,
                onOpenPhaseChecker = onOpenPhaseChecker,
                onOpenSettings = onOpenSettings,
            )
        }

        item(key = "more-brand-banner") {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, foh.safeBorder.copy(alpha = 0.7f)),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenBrandStudio),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PrestigeBrandEmblem(
                        logoKey = brand.customLogoKey,
                        size = 48.dp,
                        shimmerEnabled = brand.prestigeAnimations,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = BrandSkinRules.effectiveRentalTitle(brand),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${BrandSkinRules.effectiveEventSubtitle(brand)} · Skin ${BrandSkinRules.resolvePreset(brand.presetId).label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = foh.safeBg,
                        border = BorderStroke(1.dp, foh.safeBorder),
                    ) {
                        Text(
                            text = "Logo & Skin",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = foh.safeAccent,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }

        item(key = "section-tools") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MoreSectionLabel("Alat Uji & FOH")
                MoreActionTile(
                    icon = UiIcons.Rta,
                    title = "Stage Brand & Live RTA",
                    subtitle = "Layar penuh spektrum RTA 16-band, logo rental & meter stereo",
                    accentColor = foh.safeAccent,
                    onClick = onOpenStageRta,
                )
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = foh.safeBg,
                    border = BorderStroke(1.dp, foh.safeBorder),
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenPhaseChecker),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = foh.safeAccent.copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, foh.safeAccent.copy(alpha = 0.45f)),
                        ) {
                            Icon(
                                UiIcons.Fade,
                                contentDescription = null,
                                tint = foh.safeAccent,
                                modifier = Modifier.padding(11.dp).size(22.dp),
                            )
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                itemVerticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Speaker Phase Checker",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                if (txState.running) {
                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = foh.safeAccent,
                                    ) {
                                        Text(
                                            "TX AKTIF",
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Black,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    }
                                }
                            }
                            Text(
                                "Cek polaritas (+/-) mode 1 HP & 2 HP FOH via pulsa akustik atau uji L/R.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Rute output: ${outputRoute.label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = foh.safeAccent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            UiIcons.ChevronRight,
                            contentDescription = "Buka Speaker Phase Checker",
                            tint = foh.safeAccent,
                        )
                    }
                }
            }
        }

        item(key = "section-settings") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MoreSectionLabel("Pengaturan & Riwayat")
                MoreActionTile(
                    icon = UiIcons.Settings,
                    title = "Pengaturan Engine & Tentang",
                    subtitle = "Fade In ${playbackSettings.fadeInMs} ms · Fade Out ${playbackSettings.fadeOutMs} ms · $ytSummary",
                    accentColor = MaterialTheme.colorScheme.primary,
                    onClick = onOpenSettings,
                )
                MoreActionTile(
                    icon = UiIcons.History,
                    title = "Riwayat Rename & Urungkan",
                    subtitle = if (lastRename != null) {
                        "Terakhir: ${UiPresentation.title(lastRename.fromName)} → ${UiPresentation.title(lastRename.toName)}"
                    } else {
                        "Belum ada riwayat perubahan nama untuk diurungkan"
                    },
                    accentColor = MaterialTheme.colorScheme.secondary,
                    onClick = onOpenRenameHistory,
                )
            }
        }

        item(key = "section-summary") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MoreSectionLabel("Ringkasan SETELO")
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, foh.borderSubtle),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SummaryRow("Pustaka Lokal", "${tracks.size} track · ${playlists.size} playlist")
                        HorizontalDivider(color = foh.borderSubtle.copy(alpha = 0.6f))
                        SummaryRow(
                            "Antrian Unduhan",
                            if (activeJobCount > 0) "$activeJobCount aktif · ${jobs.size} total" else "Tidak ada antrian aktif (${jobs.size} riwayat)",
                        )
                        HorizontalDivider(color = foh.borderSubtle.copy(alpha = 0.6f))
                        SummaryRow(
                            "Server Remote FOH",
                            if (remoteServerState.snapshot.enabled) {
                                val ip = remoteServerState.interfaces.firstOrNull()?.ip
                                if (ip != null) "Aktif ($ip)" else "Aktif"
                            } else {
                                "Tidak aktif"
                            },
                        )
                        HorizontalDivider(color = foh.borderSubtle.copy(alpha = 0.6f))
                        SummaryRow("Rute Audio Saat Ini", outputRoute.label)
                        HorizontalDivider(color = foh.borderSubtle.copy(alpha = 0.6f))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                UiIcons.Info,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                "SETELO by Swararia · Versi $appVersion",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreHeader(
    txRunning: Boolean,
    onOpenPhaseChecker: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "More",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        FohHeaderChipButton(
            label = if (txRunning) "TX Aktif" else "Phase",
            icon = UiIcons.Fade,
            active = txRunning,
            onClick = onOpenPhaseChecker,
        )
        CompactCircleButton(
            icon = UiIcons.Settings,
            description = "Pengaturan & tentang",
            onClick = onOpenSettings,
            size = 36.dp,
            iconSize = 20.dp,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
}

@Composable
private fun MoreSectionLabel(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 2.dp),
    )
}

@Composable
private fun MoreActionTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = accentColor.copy(alpha = 0.14f),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.padding(9.dp).size(20.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                UiIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
