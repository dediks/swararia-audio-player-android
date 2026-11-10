package com.swararia.audiolab
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

@Composable
fun AppHeader() {
    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 22.dp, vertical = 16.dp)) {
        Text("SWARARIA", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold)
        Text("Audio Lab", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("Uji coba • Proses di HP, tanpa backend", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun IngestScreen(state: IngestState, trackCount: Int, modifier: Modifier = Modifier,
                 onDownload: (String, Boolean) -> Unit, onImport: () -> Unit,
                 onUpdate: () -> Unit, onCancel: () -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    var authorized by rememberSaveable { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Tambah audio", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Unduh dari URL berizin atau salin audio milikmu dari HP.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = url, onValueChange = { url = it }, enabled = !state.busy,
            label = { Text("URL HTTPS") }, placeholder = { Text("https://…") },
            modifier = Modifier.fillMaxWidth(), maxLines = 3)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = authorized, onCheckedChange = { authorized = it }, enabled = !state.busy)
            Text("Saya memiliki izin untuk mengunduh audio ini.", style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = { onDownload(url, authorized) }, enabled = !state.busy && authorized && url.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("Unduh ke HP") }
        OutlinedButton(onClick = onImport, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Impor audio dari HP") }
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(state.stage, style = MaterialTheme.typography.titleSmall,
                    color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                if (state.busy) {
                    if (state.progress != null) LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (state.detail.isNotBlank()) SelectionContainer {
                    Text(state.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.busy && state.cancelable) TextButton(onClick = onCancel) { Text("Batalkan") }
            }
        }
        Text("Library: $trackCount file • Music/SwarariaAudioLab/", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Mesin yt-dlp: ${state.engineVersion}", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onUpdate, enabled = !state.busy) { Text("Perbarui yt-dlp") }
        Text("Opsional: mengambil versi stabil dari GitHub resmi. Diperlukan internet; tidak memasang Termux.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { about = true }) { Text("Tentang uji coba") }
        Spacer(Modifier.height(16.dp))
    }
    if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("APK uji coba") },
        text = { Text("File disalin ke folder Music/SwarariaAudioLab. Impor tidak mengubah file asli. Pemrosesan memakai engine di HP; URL dan pembaruan membutuhkan internet. Tidak ada akun, server pendamping, atau analitik aplikasi. Engine downloader/FFmpeg memiliki kewajiban lisensi; lihat source dan catatan lisensi sebelum distribusi.") },
        confirmButton = { TextButton(onClick = { about = false }) { Text("Mengerti") } })
}
