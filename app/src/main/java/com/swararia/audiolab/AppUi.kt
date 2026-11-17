package com.swararia.audiolab
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.swararia.audiolab.brandskin.BrandSkinConfig
import com.swararia.audiolab.brandskin.BrandSkinRules
import com.swararia.audiolab.brandskin.FohSkinPreset

@OptIn(ExperimentalTextApi::class)
private val SwarariaFont = FontFamily(
    Font(R.font.inter_variable, weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter_variable, weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter_variable, weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.inter_variable, weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

private val SwarariaTypography = Typography(
    displayLarge = TextStyle(fontFamily = SwarariaFont, fontSize = 57.sp, lineHeight = 64.sp, fontWeight = FontWeight.Bold),
    displayMedium = TextStyle(fontFamily = SwarariaFont, fontSize = 45.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold),
    displaySmall = TextStyle(fontFamily = SwarariaFont, fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontFamily = SwarariaFont, fontSize = 36.sp, lineHeight = 43.sp, letterSpacing = (-1).sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontFamily = SwarariaFont, fontSize = 28.sp, lineHeight = 35.sp, letterSpacing = (-.5).sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontFamily = SwarariaFont, fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontFamily = SwarariaFont, fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = SwarariaFont, fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontFamily = SwarariaFont, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = SwarariaFont, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = SwarariaFont, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = SwarariaFont, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = SwarariaFont, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = SwarariaFont, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = SwarariaFont, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

@Immutable
internal data class FohColors(
    val safeBg: Color = Color(0xFF132B21),
    val safeBorder: Color = Color(0xFF285944),
    val safeAccent: Color = Color(0xFF8CE3C7),
    val btBg: Color = Color(0xFF142730),
    val btBorder: Color = Color(0xFF2B5669),
    val btAccent: Color = Color(0xFF7DD3FC),
    val warnBg: Color = Color(0xFF2E2311),
    val warnBorder: Color = Color(0xFF7A5B21),
    val warnAccent: Color = Color(0xFFF5C451),
    val alertBg: Color = Color(0xFF351B1B),
    val alertBorder: Color = Color(0xFF843B38),
    val alertAccent: Color = Color(0xFFFF8E7D),
    val surfaceDeep: Color = Color(0xFF131C18),
    val surfaceCard: Color = Color(0xFF19231F),
    val surfaceDeck: Color = Color(0xFF111916),
    val borderSubtle: Color = Color(0xFF2B3D33),
    val localBannerBg: Color = Color(0xFF282417),
    val localBannerBorder: Color = Color(0xFF7A6935),
    val localBannerAccent: Color = Color(0xFFF5D782),
)

internal val LocalFohColors = staticCompositionLocalOf { FohColors() }
internal val LocalBrandSkinConfig = staticCompositionLocalOf { BrandSkinConfig() }

internal object FohTheme {
    val colors: FohColors
        @Composable
        @ReadOnlyComposable
        get() = LocalFohColors.current

    val brand: BrandSkinConfig
        @Composable
        @ReadOnlyComposable
        get() = LocalBrandSkinConfig.current
}

internal fun resolveFohColors(skinConfig: BrandSkinConfig): FohColors {
    val preset = BrandSkinRules.resolvePreset(skinConfig.presetId)
    val customAccentActive = skinConfig.useCustomLogoAccent && skinConfig.customAccentArgb != null
    val accentColor = Color(BrandSkinRules.activeAccentArgb(skinConfig))
    val safeBgColor = Color(
        if (customAccentActive) BrandSkinRules.derivedSafeBgArgb(BrandSkinRules.activeAccentArgb(skinConfig))
        else preset.safeBgArgb,
    )
    val safeBorderColor = Color(
        if (customAccentActive) BrandSkinRules.derivedSafeBorderArgb(BrandSkinRules.activeAccentArgb(skinConfig))
        else preset.safeBorderArgb,
    )
    val oled = skinConfig.oledTrueBlack
    return FohColors(
        safeBg = safeBgColor,
        safeBorder = safeBorderColor,
        safeAccent = accentColor,
        surfaceDeep = if (oled) Color(0xFF000000) else Color(preset.bgDeepArgb),
        surfaceCard = if (oled) Color(0xFF0B0E0D) else Color(preset.bgCardArgb),
        surfaceDeck = if (oled) Color(0xFF060807) else Color(preset.bgDeckArgb),
        borderSubtle = Color(preset.borderSubtleArgb),
    )
}

@Composable
fun AudioTheme(
    skinConfig: BrandSkinConfig = BrandSkinConfig(),
    content: @Composable () -> Unit,
) {
    val preset = BrandSkinRules.resolvePreset(skinConfig.presetId)
    val fohColors = remember(skinConfig) { resolveFohColors(skinConfig) }
    val customAccentActive = skinConfig.useCustomLogoAccent && skinConfig.customAccentArgb != null
    val onAccent = if (customAccentActive) Color(0xFF0A1410L) else Color(preset.onAccentArgb)
    val bgColor = if (skinConfig.oledTrueBlack) Color(0xFF000000) else Color(preset.bgAppArgb)
    val cardColor = fohColors.surfaceCard
    val lowestContainer = if (skinConfig.oledTrueBlack) Color(0xFF000000) else Color(0xFF0B120F).takeIf {
        preset == FohSkinPreset.SWARARIA_EMERALD
    } ?: Color(preset.bgDeepArgb)
    val lowContainer = if (skinConfig.oledTrueBlack) Color(0xFF070908) else Color(preset.bgDeckArgb)

    CompositionLocalProvider(
        LocalFohColors provides fohColors,
        LocalBrandSkinConfig provides skinConfig,
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = fohColors.safeAccent,
                onPrimary = onAccent,
                primaryContainer = fohColors.safeBg,
                onPrimaryContainer = fohColors.safeAccent,
                secondary = Color(0xFFB5D7C6),
                onSecondary = Color(0xFF15372A),
                secondaryContainer = fohColors.surfaceDeck,
                onSecondaryContainer = Color(0xFFD6EDE0),
                background = bgColor,
                onBackground = Color(0xFFF1F5EF),
                surface = cardColor,
                onSurface = Color(0xFFF1F5EF),
                surfaceVariant = fohColors.surfaceDeck,
                onSurfaceVariant = Color(0xFFA9B6AD),
                surfaceContainerLowest = lowestContainer,
                surfaceContainerLow = lowContainer,
                surfaceContainer = cardColor,
                surfaceContainerHigh = fohColors.surfaceCard,
                surfaceContainerHighest = fohColors.borderSubtle,
                surfaceBright = fohColors.borderSubtle,
                surfaceDim = bgColor,
                outline = fohColors.borderSubtle,
                outlineVariant = fohColors.borderSubtle,
                error = Color(0xFFFFB4AB),
                onError = Color(0xFF690005),
                errorContainer = Color(0xFF4B2526),
                onErrorContainer = Color(0xFFFFDAD6),
                inverseSurface = Color(0xFFF1F5EF),
                inverseOnSurface = cardColor,
                inversePrimary = fohColors.safeBg,
            ),
            typography = SwarariaTypography,
            content = content,
        )
    }
}
