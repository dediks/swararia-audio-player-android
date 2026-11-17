package com.swararia.audiolab.brandskin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.swararia.audiolab.FohTheme
import com.swararia.audiolab.RoyalBrandLogo
import com.swararia.audiolab.rememberLocalAudioSignal
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

private val brandLogoCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

@Composable
internal fun rememberBrandLogoBitmap(logoKey: String?): Bitmap? {
    val context = LocalContext.current.applicationContext
    val validKey = logoKey?.takeIf(BrandSkinRules::validLogoKey)
    val bitmap by produceState<Bitmap?>(initialValue = validKey?.let { brandLogoCache.get(it) }, validKey) {
        value = if (validKey == null) {
            null
        } else {
            brandLogoCache.get(validKey) ?: runInterruptible(Dispatchers.IO) {
                runCatching {
                    val file = BrandSkinManager.resolveLogoFile(context, validKey) ?: return@runCatching null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, bounds)
                    if (bounds.outWidth !in 1..2048 || bounds.outHeight !in 1..2048) return@runCatching null
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) > BrandSkinRules.MAX_LOGO_DIMENSION_PX * sample) {
                        sample *= 2
                    }
                    BitmapFactory.decodeFile(
                        file.path,
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    )?.also { decoded -> brandLogoCache.put(validKey, decoded) }
                }.getOrNull()
            }
        }
    }
    return bitmap
}

/**
 * Displays either the uploaded rental logo (preserving transparency) or falls back cleanly to
 * the built-in SETELO Royal emblem.
 */
@Composable
internal fun RentalBrandLogoImage(
    logoKey: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val customBitmap = rememberBrandLogoBitmap(logoKey)
    if (customBitmap != null) {
        Image(
            bitmap = customBitmap.asImageBitmap(),
            contentDescription = "Logo Brand Rental",
            modifier = modifier.size(size),
            contentScale = ContentScale.Fit,
        )
    } else {
        RoyalBrandLogo(size = size, modifier = modifier)
    }
}

/**
 * Prestige framed brand badge featuring a dark hardware bevel, optional metallic diagonal foil
 * shimmer, and optional audio-reactive radial aura ring.
 */
@Composable
internal fun PrestigeBrandEmblem(
    logoKey: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape((size * 0.28f).coerceAtLeast(8.dp)),
    circularAura: Boolean = true,
    shimmerEnabled: Boolean = FohTheme.brand.prestigeAnimations,
    bassEnergy: Float = 0f,
    playing: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val foh = FohTheme.colors
    val clampedBass = if (playing) bassEnergy.coerceIn(0f, 1f) else 0f
    var shimmerPhase by remember { mutableFloatStateOf(-0.6f) }

    LaunchedEffect(shimmerEnabled) {
        if (!shimmerEnabled) {
            shimmerPhase = -0.6f
            return@LaunchedEffect
        }
        val startNanos = withFrameNanos { it }
        val periodMs = 4200L
        while (true) {
            withFrameNanos { nowNanos ->
                val elapsedMs = (nowNanos - startNanos) % periodMs
                val t = elapsedMs.toFloat() / periodMs.toFloat()
                // Sweep across during first 45% of cycle, rest subtly between sweeps.
                shimmerPhase = if (t < 0.45f) {
                    -0.75f + (t / 0.45f) * 2.5f
                } else {
                    2.0f
                }
            }
        }
    }

    val auraPadding = if (playing || bassEnergy > 0.01f) 6.dp else 2.dp
    Box(
        modifier = modifier
            .size(size + auraPadding * 2)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        if (playing || bassEnergy > 0.01f) {
            val auraAccent = foh.safeAccent
            Canvas(modifier = Modifier.fillMaxSize()) {
                val scale = 0.88f + clampedBass * 0.12f
                val maxRadius = (minOf(this.size.width, this.size.height) * 0.5f) * scale
                drawCircle(
                    brush = Brush.radialGradient(
                        0.55f to auraAccent.copy(alpha = 0.22f + clampedBass * 0.28f),
                        1.0f to Color.Transparent,
                        radius = maxRadius.coerceAtLeast(4f),
                    ),
                    radius = maxRadius,
                )
                drawCircle(
                    color = auraAccent.copy(alpha = 0.35f + clampedBass * 0.55f),
                    radius = maxRadius - 1.5.dp.toPx(),
                    style = Stroke(width = (1.2f + clampedBass * 1.1f).dp.toPx()),
                )
            }
        }

        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(Color(0xFF070A09))
                .border(
                    width = 1.3.dp,
                    color = foh.safeAccent.copy(alpha = if (playing) 0.65f else 0.38f),
                    shape = shape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            RentalBrandLogoImage(
                logoKey = logoKey,
                size = size * 0.78f,
            )

            if (shimmerEnabled && shimmerPhase in -0.75f..1.75f) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    if (this.size.width <= 1f || this.size.height <= 1f || !shimmerPhase.isFinite()) return@Canvas
                    val beamW = this.size.width * 0.48f
                    val startX = shimmerPhase * this.size.width
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.06f),
                                Color.White.copy(alpha = 0.22f),
                                Color.White.copy(alpha = 0.06f),
                                Color.Transparent,
                            ),
                            start = Offset(startX, 0f),
                            end = Offset(startX + beamW, this.size.height),
                        ),
                        size = this.size,
                    )
                }
            }
        }
    }
}

/**
 * Compact header pill with miniature live RTA bars to enter the dedicated Stage Brand & RTA view
 * from Home or Player without taking extra text space.
 */
@Composable
internal fun StageRtaEntryPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
) {
    val foh = FohTheme.colors
    val signal by rememberLocalAudioSignal()
    val isLive = !signal.muted && signal.active

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(11.dp),
        color = if (isLive) foh.safeBg else foh.surfaceCard,
        border = BorderStroke(
            width = 1.dp,
            color = if (isLive) foh.safeAccent.copy(alpha = 0.75f) else foh.safeBorder,
        ),
        modifier = modifier
            .height(34.dp)
            .semantics { contentDescription = "Buka halaman Stage RTA" },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (showLabel) 9.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Canvas(modifier = Modifier.size(width = 15.dp, height = 14.dp)) {
                val barCount = 4
                val gap = 1.8.dp.toPx()
                val bw = ((size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(1.5f)
                val fallbacks = floatArrayOf(0.45f, 0.85f, 0.62f, 0.78f)
                val indices = intArrayOf(1, 5, 9, 13)
                for (i in 0 until barCount) {
                    val v = if (isLive) {
                        signal.bands.getOrElse(indices[i]) { 0.2f }.coerceIn(0.18f, 1f)
                    } else {
                        fallbacks[i]
                    }
                    val bh = (size.height * v).coerceAtLeast(2.dp.toPx())
                    drawRoundRect(
                        color = foh.safeAccent,
                        topLeft = Offset(i * (bw + gap), size.height - bh),
                        size = Size(bw, bh),
                        cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
                    )
                }
            }
            if (showLabel) {
                Text(
                    text = "RTA",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.5.sp),
                    fontWeight = FontWeight.ExtraBold,
                    color = foh.safeAccent,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
