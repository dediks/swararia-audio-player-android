package com.swararia.audiolab

import androidx.compose.foundation.layout.heightIn

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun BrandLogo(size: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.swararia_logo), contentDescription = "Logo SETELO",
        modifier = modifier.size(size), contentScale = ContentScale.Fit)
}

@Composable
internal fun RoyalBrandLogo(size: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.setelo_logo_royal), contentDescription = "Logo SETELO",
        modifier = modifier.size(size), contentScale = ContentScale.Fit)
}

private val artworkBitmaps = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

/** Only resolves the validated private cache key; never extracts media or opens the DB. */
@Composable
internal fun TrackArtwork(artworkKey: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val bitmap by produceState<Bitmap?>(null, artworkKey) {
        value = null
        if (artworkKey != null) value = artworkBitmaps.get(artworkKey) ?: runInterruptible(Dispatchers.IO) {
            try {
                val file = ArtworkStore.resolve(context, artworkKey)
                if (file == null || !file.isFile || file.length() !in 1..4_194_304L) null
                else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, bounds)
                    if (bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192 ||
                        bounds.outWidth.toLong() * bounds.outHeight > 16_777_216L) null
                    else {
                        var sample = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) > 512 * sample) sample *= 2
                        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                            ?.also { artworkBitmaps.put(artworkKey, it) }
                    }
                }
            } catch (_: IOException) { null }
              catch (_: SecurityException) { null }
              catch (_: IllegalArgumentException) { null }
        }
    }
    val image = bitmap
    if (image != null) Image(image.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    else Image(painterResource(R.drawable.cover_0), contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
}

@Composable
internal fun GlyphButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    iconSize: Dp = 24.dp,
) {
    val colors = MaterialTheme.colorScheme
    IconButton(onClick = onClick, enabled = enabled,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .then(if (filled) Modifier.background(if (enabled) colors.primary else colors.surfaceVariant, CircleShape) else Modifier)) {
        Icon(icon, contentDescription = description,
            tint = if (!enabled) colors.onSurface.copy(alpha = .38f) else if (filled) colors.onPrimary else colors.onSurface,
            modifier = Modifier.size(iconSize))
    }
}

/** Shared compact circular transport/toggle button reused by collapsed players and top audio pills. */
@Composable
internal fun CompactCircleButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 32.dp,
    iconSize: Dp = 17.dp,
    containerColor: Color = Color.Transparent,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = containerColor,
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = if (enabled) contentColor else contentColor.copy(alpha = 0.38f),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
internal fun EmptyMessage(title: String, description: String, icon: ImageVector, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(description, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        action?.invoke()
    }
}

@Composable
internal fun FohDeckButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    filled: Boolean,
    outlined: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
        }
    }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    val padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 8.dp)
    val sizedModifier = modifier.heightIn(min = 52.dp)
    when {
        filled -> androidx.compose.material3.Button(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            contentPadding = padding,
            modifier = sizedModifier,
            content = content,
        )
        outlined -> androidx.compose.material3.OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            contentPadding = padding,
            modifier = sizedModifier,
            content = content,
        )
        else -> androidx.compose.material3.FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            contentPadding = padding,
            modifier = sizedModifier,
            content = content,
        )
    }
}

@Composable
internal fun FohPlayToggleCircleButton(
    playing: Boolean,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    size: Dp = 36.dp,
    iconSize: Dp = 18.dp,
    containerColor: Color = FohTheme.colors.safeBg,
    contentColor: Color = FohTheme.colors.safeAccent,
) {
    CompactCircleButton(
        icon = if (playing) UiIcons.Pause else UiIcons.Play,
        description = description,
        onClick = onClick,
        enabled = enabled,
        size = size,
        iconSize = iconSize,
        containerColor = containerColor,
        contentColor = contentColor,
    )
}

/** Shared header pill action shared across Library and Search screens. */
@Composable
internal fun FohHeaderChipButton(
    label: String,
    icon: ImageVector,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(999.dp),
        color = if (active) FohTheme.colors.safeBg else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            1.dp,
            if (active) FohTheme.colors.safeBorder else FohTheme.colors.borderSubtle,
        ),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 36.dp)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                else if (active) FohTheme.colors.safeAccent else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                else if (active) FohTheme.colors.safeAccent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** Full-width 2-choice (or multi-choice) scope segmented bar matching the Library tab look. */
@Composable
internal fun <T> FohSegmentedBar(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = FohTheme.colors.surfaceDeep,
        border = BorderStroke(1.dp, FohTheme.colors.borderSubtle),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEach { option ->
                val active = option == selected
                Surface(
                    onClick = { onSelect(option) },
                    enabled = enabled,
                    shape = RoundedCornerShape(10.dp),
                    color = if (active) FohTheme.colors.safeBg else Color.Transparent,
                    border = if (active) BorderStroke(1.dp, FohTheme.colors.safeBorder) else null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = when {
                            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                            active -> FohTheme.colors.safeAccent
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 9.dp),
                    )
                }
            }
        }
    }
}

/** Compact pill filter chip shared across Library grouping and Search result type filters. */
@Composable
internal fun FohFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(999.dp),
        color = if (selected) FohTheme.colors.safeBg else FohTheme.colors.surfaceCard,
        border = BorderStroke(
            1.dp,
            if (selected) FohTheme.colors.safeBorder else FohTheme.colors.borderSubtle,
        ),
        modifier = modifier.heightIn(min = 36.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    selected -> FohTheme.colors.safeAccent
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * Dual counter-rotating orbital loading indicator driven by frame timing.
 * Keeps motion unmistakably alive even at compact (16-20 dp) icon sizes or OS animator scale 0.
 */
@Composable
internal fun FohSpinningIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    strokeWidth: Dp = 2.4.dp,
    color: Color = FohTheme.colors.safeAccent,
    trackColor: Color = FohTheme.colors.borderSubtle.copy(alpha = 0.65f),
    periodMs: Long = 850L,
) {
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(periodMs) {
        val startNanos = withFrameNanos { it }
        while (true) {
            withFrameNanos { nowNanos ->
                elapsedMs = (nowNanos - startNanos) / 1_000_000L
            }
        }
    }
    val safePeriodMs = periodMs.coerceAtLeast(300L)
    val outerAngle = ((elapsedMs % safePeriodMs).toFloat() / safePeriodMs.toFloat()) * 360f
    val innerPeriodMs = ((safePeriodMs * 100L) / 145L).coerceAtLeast(240L)
    val innerAngle = -((elapsedMs % innerPeriodMs).toFloat() / innerPeriodMs.toFloat()) * 360f
    val outerBrush = remember(color) {
        Brush.sweepGradient(
            0.00f to Color.Transparent,
            0.08f to color.copy(alpha = 0.10f),
            0.42f to color.copy(alpha = 0.50f),
            0.70f to color,
            0.78f to color,
            0.84f to Color.Transparent,
            1.00f to Color.Transparent,
        )
    }
    Canvas(modifier = modifier.size(size)) {
        val strokePx = strokeWidth.toPx()
        val inset = strokePx / 2f
        val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
        val topLeft = Offset(inset, inset)
        val outerRadius = arcSize.width / 2f
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokePx),
        )
        rotate(degrees = outerAngle) {
            drawArc(
                brush = outerBrush,
                startAngle = 14f,
                sweepAngle = 248f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.90f),
                radius = strokePx * 0.45f,
                center = Offset(x = center.x, y = center.y - outerRadius),
            )
        }
        val innerRadius = outerRadius * 0.54f
        if (innerRadius > strokePx * 1.15f) {
            val innerSize = Size(innerRadius * 2f, innerRadius * 2f)
            val innerTopLeft = Offset(center.x - innerRadius, center.y - innerRadius)
            val innerStroke = (strokePx * 0.78f).coerceAtLeast(1.4f)
            rotate(degrees = innerAngle) {
                drawArc(
                    color = color.copy(alpha = 0.78f),
                    startAngle = 0f,
                    sweepAngle = 135f,
                    useCenter = false,
                    topLeft = innerTopLeft,
                    size = innerSize,
                    style = Stroke(width = innerStroke, cap = StrokeCap.Round),
                )
                drawCircle(
                    color = color.copy(alpha = 0.95f),
                    radius = innerStroke * 0.45f,
                    center = Offset(x = center.x + innerRadius, y = center.y),
                )
            }
        }
    }
}

/** Compact 3-bar wave equalizer indicator driven by frame timing for active status pills. */
@Composable
internal fun FohWaveBarsIndicator(
    modifier: Modifier = Modifier,
    color: Color = FohTheme.colors.safeAccent,
    width: Dp = 13.dp,
    height: Dp = 11.dp,
    periodMs: Long = 860L,
) {
    var phaseRad by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(periodMs) {
        val startNanos = withFrameNanos { it }
        val safePeriodMs = periodMs.coerceAtLeast(360L)
        while (true) {
            withFrameNanos { nowNanos ->
                val elapsedMs = (nowNanos - startNanos) / 1_000_000L
                phaseRad = ((elapsedMs % safePeriodMs).toFloat() / safePeriodMs.toFloat()) * (2.0 * PI).toFloat()
            }
        }
    }
    Canvas(modifier = modifier.size(width = width, height = height)) {
        val barCount = 3
        val gap = this.size.width * 0.16f
        val barWidth = ((this.size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(2f)
        val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
        for (index in 0 until barCount) {
            val wave = 0.5f + 0.5f * sin((phaseRad + index * 2.094f).toDouble()).toFloat()
            val barHeight = this.size.height * (0.30f + 0.70f * wave)
            val left = index * (barWidth + gap)
            drawRoundRect(
                color = color,
                topLeft = Offset(left, this.size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = radius,
            )
        }
    }
}

/** Smooth animated indeterminate horizontal progress bar that glides continuously while busy. */
@Composable
internal fun FohIndeterminateBar(
    modifier: Modifier = Modifier,
    color: Color = FohTheme.colors.safeAccent,
    trackColor: Color = FohTheme.colors.surfaceDeep,
    height: Dp = 5.dp,
    periodMs: Long = 1350L,
) {
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(periodMs) {
        val startNanos = withFrameNanos { it }
        val safePeriodMs = periodMs.coerceAtLeast(400L)
        while (true) {
            withFrameNanos { nowNanos ->
                val elapsedMs = (nowNanos - startNanos) / 1_000_000L
                phase = ((elapsedMs % safePeriodMs).toFloat() / safePeriodMs.toFloat())
            }
        }
    }
    val pillBrush = remember(color) {
        Brush.horizontalGradient(
            0.0f to color.copy(alpha = 0.25f),
            0.5f to color,
            1.0f to color.copy(alpha = 0.85f),
        )
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(999.dp)),
    ) {
        drawRect(color = trackColor)
        val segmentW = size.width * 0.36f
        val travel = size.width + segmentW
        val left = -segmentW + phase * travel
        drawRoundRect(
            brush = pillBrush,
            topLeft = Offset(left, 0f),
            size = Size(segmentW, size.height),
            cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
        )
    }
}

/**
 * Determinate horizontal progress bar with a continuous frame-driven light sweep over the active fill
 * so slow or paused increments still look unmistakably alive.
 */
@Composable
internal fun FohDeterminateShimmerBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = FohTheme.colors.safeAccent,
    trackColor: Color = FohTheme.colors.surfaceDeep,
    height: Dp = 5.dp,
    shimmerEnabled: Boolean = true,
    periodMs: Long = 1350L,
) {
    val clamped = progress.coerceIn(0f, 1f)
    var shimmerPhase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(shimmerEnabled, clamped > 0.01f, periodMs) {
        if (!shimmerEnabled || clamped <= 0.01f) {
            shimmerPhase = 0f
            return@LaunchedEffect
        }
        val startNanos = withFrameNanos { it }
        val safePeriodMs = periodMs.coerceAtLeast(450L)
        while (true) {
            withFrameNanos { nowNanos ->
                val elapsedMs = (nowNanos - startNanos) / 1_000_000L
                shimmerPhase = ((elapsedMs % safePeriodMs).toFloat() / safePeriodMs.toFloat())
            }
        }
    }
    val highlightBrush = remember(color) {
        Brush.horizontalGradient(
            0.0f to Color.Transparent,
            0.5f to Color.White.copy(alpha = 0.55f),
            1.0f to Color.Transparent,
        )
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(999.dp)),
    ) {
        drawRect(color = trackColor)
        val fillW = size.width * clamped
        if (fillW > 0.5f) {
            val corner = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(
                color = color,
                topLeft = Offset.Zero,
                size = Size(fillW, size.height),
                cornerRadius = corner,
            )
            if (shimmerEnabled) {
                val bandW = (size.width * 0.28f).coerceAtMost(fillW.coerceAtLeast(12f))
                val travel = fillW + bandW
                val left = (-bandW + shimmerPhase * travel).coerceIn(-bandW, fillW)
                val visibleLeft = left.coerceAtLeast(0f)
                val visibleRight = (left + bandW).coerceAtMost(fillW)
                if (visibleRight > visibleLeft) {
                    drawRoundRect(
                        brush = highlightBrush,
                        topLeft = Offset(visibleLeft, 0f),
                        size = Size(visibleRight - visibleLeft, size.height),
                        cornerRadius = corner,
                    )
                }
            }
        }
    }
}

/** Frame-driven gliding shimmer block for skeleton loading placeholders. */
@Composable
internal fun FohShimmerBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
    baseColor: Color = FohTheme.colors.surfaceDeep,
    highlightColor: Color = FohTheme.colors.borderSubtle.copy(alpha = 0.85f),
    periodMs: Long = 1250L,
) {
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(periodMs) {
        val startNanos = withFrameNanos { it }
        val safePeriodMs = periodMs.coerceAtLeast(450L)
        while (true) {
            withFrameNanos { nowNanos ->
                val elapsedMs = (nowNanos - startNanos) / 1_000_000L
                phase = ((elapsedMs % safePeriodMs).toFloat() / safePeriodMs.toFloat())
            }
        }
    }
    Canvas(modifier = modifier.clip(RoundedCornerShape(cornerRadius))) {
        drawRect(color = baseColor)
        val bandW = size.width * 0.45f
        val startX = -bandW + phase * (size.width + bandW * 2f)
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, highlightColor, Color.Transparent),
                start = Offset(startX, 0f),
                end = Offset(startX + bandW, size.height * 0.65f),
            ),
            size = size,
        )
    }
}

/**
 * Square-rounded track artwork with Orbit Glow indicator along the frame edge:
 * - Active & playing: emerald comet arc + tip spark orbiting the rounded perimeter with breathing halo.
 * - Active & paused (Standby): steady warm amber frame outline.
 */
@Composable
internal fun FohAnimatedTrackArtwork(
    artworkKey: String?,
    active: Boolean,
    playing: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    cornerRadius: Dp = 9.dp,
) {
    var orbitPhase by remember { mutableFloatStateOf(0f) }
    var glowPulse by remember { mutableFloatStateOf(0.5f) }
    LaunchedEffect(active, playing) {
        if (!active || !playing) {
            orbitPhase = 0f
            glowPulse = 0.5f
            return@LaunchedEffect
        }
        val startNanos = withFrameNanos { it }
        while (true) {
            withFrameNanos { nowNanos ->
                val elapsedMs = (nowNanos - startNanos) / 1_000_000L
                orbitPhase = ((elapsedMs % 2100L).toFloat() / 2100f)
                val pulseAngle = ((elapsedMs % 1500L).toFloat() / 1500f) * (2.0 * PI).toFloat()
                glowPulse = 0.5f + 0.5f * sin(pulseAngle.toDouble()).toFloat()
            }
        }
    }

    val safeAccent = FohTheme.colors.safeAccent
    val warnAccent = FohTheme.colors.warnAccent
    val perimeterPath = remember { Path() }
    val perimeterMeasure = remember { PathMeasure() }
    val cometSegmentPath = remember { Path() }
    val innerArtSize = size - 4.dp
    val innerCorner = (cornerRadius - 1.5.dp).coerceAtLeast(4.dp)

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        TrackArtwork(
            artworkKey = artworkKey,
            modifier = Modifier
                .size(innerArtSize)
                .clip(RoundedCornerShape(innerCorner)),
        )
        if (active) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokePx = if (playing) 2.2.dp.toPx() else 1.8.dp.toPx()
                val inset = strokePx / 2f
                val cornerPx = cornerRadius.toPx()
                if (playing) {
                    val corner = CornerRadius(cornerPx, cornerPx)
                    drawRoundRect(
                        color = safeAccent.copy(alpha = 0.18f + 0.16f * glowPulse),
                        topLeft = Offset(inset, inset),
                        size = Size(this.size.width - strokePx, this.size.height - strokePx),
                        cornerRadius = corner,
                        style = Stroke(width = strokePx + 2.4.dp.toPx()),
                    )
                    perimeterPath.reset()
                    perimeterPath.addRoundRect(
                        RoundRect(
                            left = inset,
                            top = inset,
                            right = this.size.width - inset,
                            bottom = this.size.height - inset,
                            cornerRadius = corner,
                        ),
                    )
                    perimeterMeasure.setPath(perimeterPath, forceClosed = false)
                    val totalLen = perimeterMeasure.length
                    if (totalLen > 0f) {
                        val headDist = orbitPhase * totalLen
                        drawWrappedSegment(
                            measure = perimeterMeasure,
                            segmentPath = cometSegmentPath,
                            totalLen = totalLen,
                            start = headDist - totalLen * 0.44f,
                            end = headDist - totalLen * 0.16f,
                            color = safeAccent.copy(alpha = 0.42f),
                            strokeWidth = strokePx,
                        )
                        drawWrappedSegment(
                            measure = perimeterMeasure,
                            segmentPath = cometSegmentPath,
                            totalLen = totalLen,
                            start = headDist - totalLen * 0.16f,
                            end = headDist,
                            color = safeAccent,
                            strokeWidth = strokePx,
                        )
                        val tipPos = perimeterMeasure.getPosition(headDist)
                        drawCircle(
                            color = safeAccent.copy(alpha = 0.42f),
                            radius = strokePx * 1.35f,
                            center = tipPos,
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0.95f),
                            radius = strokePx * 0.62f,
                            center = tipPos,
                        )
                    }
                } else {
                    drawRoundRect(
                        color = warnAccent.copy(alpha = 0.85f),
                        topLeft = Offset(inset, inset),
                        size = Size(this.size.width - strokePx, this.size.height - strokePx),
                        cornerRadius = CornerRadius(cornerPx, cornerPx),
                        style = Stroke(width = strokePx),
                    )
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWrappedSegment(
    measure: PathMeasure,
    segmentPath: Path,
    totalLen: Float,
    start: Float,
    end: Float,
    color: Color,
    strokeWidth: Float,
) {
    val normalizedStart = ((start % totalLen) + totalLen) % totalLen
    val normalizedEnd = ((end % totalLen) + totalLen) % totalLen
    segmentPath.reset()
    if (normalizedStart < normalizedEnd) {
        measure.getSegment(normalizedStart, normalizedEnd, segmentPath, startWithMoveTo = true)
    } else {
        measure.getSegment(normalizedStart, totalLen, segmentPath, startWithMoveTo = true)
        measure.getSegment(0f, normalizedEnd, segmentPath, startWithMoveTo = true)
    }
    drawPath(
        path = segmentPath,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
    )
}

/** Compact 18 dp spinner shared by status banners in Library, Search, and Downloads. */
@Composable
internal fun CircularProgressIndicatorCompact() {
    FohSpinningIndicator(size = 18.dp, strokeWidth = 2.2.dp, color = MaterialTheme.colorScheme.primary)
}
