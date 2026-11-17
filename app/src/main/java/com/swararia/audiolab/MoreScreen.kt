package com.swararia.audiolab

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
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
import com.swararia.audiolab.brandskin.BrandSkinConfig
import com.swararia.audiolab.brandskin.BrandSkinRules
import com.swararia.audiolab.brandskin.BrandSkinStudioScreen
import com.swararia.audiolab.brandskin.BrandSkinStudioUiState
import com.swararia.audiolab.brandskin.BrandSkinUiActions
import com.swararia.audiolab.brandskin.PrestigeBrandEmblem
import com.swararia.audiolab.phasechecker.PhaseCheckerScreen
import com.swararia.audiolab.phasechecker.PhaseTransmitterService
import com.swararia.audiolab.phasechecker.activeFohOutputRoute
import com.swararia.audiolab.remote.DesktopRemoteSession
import com.swararia.audiolab.remote.RemoteServerUiState
import com.swararia.audiolab.remote.RemoteTabScreen
import com.swararia.audiolab.ytmusic.YoutubeMusicAccountState

internal enum class MoreToolRoute {
    HUB,
    PHASE_CHECKER,
    BRAND_STUDIO,
    REMOTE,
}

@Composable
internal fun MoreScreen(
    tracks: List<AudioTrack>,
    playlists: List<LibraryPlaylist>,
    lastRename: RenameRecord?,
    ytMusicState: YoutubeMusicAccountState,
    playbackSettings: PlaybackSettingsState,
    desktopRemote: DesktopRemoteSession,
    remoteServerState: RemoteServerUiState,
    remoteServerActions: RemoteServerUiActions = RemoteServerUiActions(),
    jobs: List<DownloadJob>,
    activeTool: MoreToolRoute,
    onSelectTool: (MoreToolRoute) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRenameHistory: () -> Unit,
    onPauseOtherAudio: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenStageRta: () -> Unit = {},
    localAudioBanner: @Composable () -> Unit = {},
    brandSkinActions: BrandSkinUiActions = BrandSkinUiActions(),
    brandSkinStudioState: BrandSkinStudioUiState = BrandSkinStudioUiState(),
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
            studioState = brandSkinStudioState,
            brandSkinActions = brandSkinActions,
            modifier = modifier,
        )

        MoreToolRoute.REMOTE -> RemoteTabScreen(
            desktopRemote = desktopRemote,
            serverState = remoteServerState,
            serverActions = remoteServerActions,
            modifier = modifier,
            localAudioBanner = localAudioBanner,
            onBack = { onSelectTool(MoreToolRoute.HUB) },
        )

        MoreToolRoute.HUB -> MoreHubScreen(
            playbackSettings = playbackSettings,
            remoteServerState = remoteServerState,
            onOpenPhaseChecker = { onSelectTool(MoreToolRoute.PHASE_CHECKER) },
            onOpenBrandStudio = { onSelectTool(MoreToolRoute.BRAND_STUDIO) },
            onOpenRemote = { onSelectTool(MoreToolRoute.REMOTE) },
            onOpenSettings = onOpenSettings,
            onOpenRenameHistory = onOpenRenameHistory,
            modifier = modifier,
        )
    }
}

@Composable
private fun MoreHubScreen(
    playbackSettings: PlaybackSettingsState,
    remoteServerState: RemoteServerUiState,
    onOpenPhaseChecker: () -> Unit,
    onOpenBrandStudio: () -> Unit,
    onOpenRemote: () -> Unit,
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
    val preset = remember(brand.presetId) { BrandSkinRules.resolvePreset(brand.presetId) }
    val appVersion = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "0.6.0-spike"
    }
    val remoteActive = remoteServerState.snapshot.enabled
    val remoteIp = remoteServerState.interfaces.firstOrNull()?.ip

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "more-header") {
            MoreHeader(
                brand = brand,
                outputRouteLabel = outputRoute.label,
                onOpenRenameHistory = onOpenRenameHistory,
                onOpenSettings = onOpenSettings,
            )
        }

        item(key = "more-brand-banner") {
            val heroBrush = remember(foh.safeBg, foh.surfaceCard) {
                Brush.linearGradient(listOf(foh.safeBg, foh.surfaceCard))
            }
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = foh.surfaceCard,
                border = BorderStroke(1.dp, foh.safeBorder),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenBrandStudio),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(heroBrush)
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PrestigeBrandEmblem(
                        logoKey = brand.customLogoKey,
                        size = 52.dp,
                        shimmerEnabled = brand.prestigeAnimations,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "BRAND & SKIN",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = foh.safeAccent,
                            maxLines = 1,
                        )
                        Text(
                            text = BrandSkinRules.effectiveRentalTitle(brand),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "Tema ${preset.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = foh.safeAccent,
                    ) {
                        Text(
                            text = "Ubah ›",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(preset.onAccentArgb),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }

        item(key = "section-tools") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MoreSectionLabel("Tools")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ToolsBentoPad(
                        icon = UiIcons.Fade,
                        badgeLabel = if (txState.running) "TX" else "Siap",
                        badgeHighlighted = true,
                        title = "Speaker Phase",
                        subtitle = "Pulsa · L/R",
                        onClick = onOpenPhaseChecker,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    ToolsBentoPad(
                        icon = UiIcons.QrScan,
                        badgeLabel = if (remoteActive) "Aktif" else "Wi-Fi",
                        badgeHighlighted = remoteActive,
                        title = "Remote Multi-HP",
                        subtitle = if (remoteActive) (remoteIp ?: "Server Aktif") else "QR / Server",
                        onClick = onOpenRemote,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }

        item(key = "section-footer") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MoreFooterPad(
                    icon = UiIcons.Settings,
                    title = "Engine",
                    subtitle = "Fade ${playbackSettings.fadeInMs}ms",
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                MoreFooterPad(
                    icon = UiIcons.Info,
                    title = "SWR Player",
                    subtitle = "v$appVersion",
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun MoreHeader(
    brand: BrandSkinConfig,
    outputRouteLabel: String,
    onOpenRenameHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PrestigeBrandEmblem(
            logoKey = brand.customLogoKey,
            size = 34.dp,
            shimmerEnabled = brand.prestigeAnimations,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = BrandSkinRules.effectiveRentalTitle(brand),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Alat & Skin · $outputRouteLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CompactCircleButton(
            icon = UiIcons.History,
            description = "Riwayat nama & urungkan",
            onClick = onOpenRenameHistory,
            size = 36.dp,
            iconSize = 19.dp,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
        CompactCircleButton(
            icon = UiIcons.Settings,
            description = "Pengaturan & tentang",
            onClick = onOpenSettings,
            size = 36.dp,
            iconSize = 19.dp,
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
private fun ToolsBentoPad(
    icon: ImageVector,
    badgeLabel: String,
    badgeHighlighted: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val foh = FohTheme.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = foh.surfaceCard,
        border = BorderStroke(1.dp, if (badgeHighlighted) foh.safeBorder else foh.borderSubtle),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = foh.safeBg,
                    border = BorderStroke(1.dp, foh.borderSubtle),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = foh.safeAccent,
                        modifier = Modifier.padding(9.dp).size(20.dp),
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (badgeHighlighted) foh.safeBg else foh.surfaceDeep,
                    border = BorderStroke(1.dp, if (badgeHighlighted) foh.safeBorder else foh.borderSubtle),
                ) {
                    Text(
                        text = badgeLabel,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (badgeHighlighted) foh.safeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MoreFooterPad(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val foh = FohTheme.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = foh.surfaceDeck,
        border = BorderStroke(1.dp, foh.borderSubtle),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
