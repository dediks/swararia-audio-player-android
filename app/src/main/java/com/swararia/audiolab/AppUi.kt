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

@Composable
fun AudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFF8CE3C7), onPrimary = Color(0xFF12392C),
        primaryContainer = Color(0xFF173B2E), onPrimaryContainer = Color(0xFF8CE3C7),
        secondary = Color(0xFFB5D7C6), onSecondary = Color(0xFF15372A),
        secondaryContainer = Color(0xFF2B4538), onSecondaryContainer = Color(0xFFD6EDE0),
        background = Color(0xFF101715), onBackground = Color(0xFFF1F5EF),
        surface = Color(0xFF19231F), onSurface = Color(0xFFF1F5EF),
        surfaceVariant = Color(0xFF22322A), onSurfaceVariant = Color(0xFFA9B6AD),
        surfaceContainerLowest = Color(0xFF0B120F), surfaceContainerLow = Color(0xFF141E19),
        surfaceContainer = Color(0xFF19231F), surfaceContainerHigh = Color(0xFF202D26),
        surfaceContainerHighest = Color(0xFF28372F), surfaceBright = Color(0xFF304138),
        surfaceDim = Color(0xFF101715), outline = Color(0xFF6A8071), outlineVariant = Color(0xFF354C3F),
        error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
        errorContainer = Color(0xFF4B2526), onErrorContainer = Color(0xFFFFDAD6),
        inverseSurface = Color(0xFFF1F5EF), inverseOnSurface = Color(0xFF19231F), inversePrimary = Color(0xFF236B53),
    ), typography = SwarariaTypography, content = content)
}
