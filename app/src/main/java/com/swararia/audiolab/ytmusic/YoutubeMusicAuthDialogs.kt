package com.swararia.audiolab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun YoutubeMusicSignInDialog(
    onDismiss: () -> Unit,
    onSwitchToImportCookies: () -> Unit,
    onCompleteSignIn: (wwwCookies: String?, musicCookies: String?) -> Unit,
) {
    var progress by remember { mutableIntStateOf(0) }
    var authDetected by remember { mutableStateOf(false) }
    var navWarning by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val refreshCookieStatus = {
        val mgr = runCatching { CookieManager.getInstance() }.getOrNull()
        val www = mgr?.getCookie("https://www.youtube.com")
        val ytm = mgr?.getCookie("https://music.youtube.com")
        authDetected = YoutubeMusicCookieRules.hasRequiredAuthCookiesInHeader(www) ||
            YoutubeMusicCookieRules.hasRequiredAuthCookiesInHeader(ytm)
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                removeAllViews()
                destroy()
            }
            webViewRef = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(UiIcons.Close, contentDescription = "Tutup")
                    }
                    Text(
                        text = "Masuk YouTube Music",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (authDetected) {
                        Button(
                            onClick = {
                                val mgr = CookieManager.getInstance()
                                mgr.flush()
                                onCompleteSignIn(
                                    mgr.getCookie("https://www.youtube.com"),
                                    mgr.getCookie("https://music.youtube.com"),
                                )
                            },
                        ) {
                            Text("Simpan Sesi")
                        }
                    } else {
                        FilledTonalButton(
                            onClick = refreshCookieStatus,
                            enabled = false,
                        ) {
                            Text("Simpan Sesi")
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = if (authDetected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = when {
                                navWarning != null -> navWarning!!
                                authDetected -> "Sesi YouTube terdeteksi. Pilih channel jika diminta, lalu ketuk Simpan Sesi."
                                else -> "Login ke akun YT Music di bawah. Jika Google menolak WebView di HP ini, pakai Impor cookies.txt."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (authDetected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onSwitchToImportCookies) {
                            Text("cookies.txt")
                        }
                    }
                }

                if (progress in 1..99) {
                    FohDeterminateShimmerBar(
                        progress = progress / 100f,
                        modifier = Modifier.fillMaxWidth(),
                        height = 3.dp,
                    )
                } else {
                    HorizontalDivider()
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    allowFileAccess = false
                                    allowContentAccess = false
                                    @Suppress("DEPRECATION")
                                    allowFileAccessFromFileURLs = false
                                    @Suppress("DEPRECATION")
                                    allowUniversalAccessFromFileURLs = false
                                    @Suppress("DEPRECATION")
                                    saveFormData = false
                                    setSupportMultipleWindows(false)
                                    javaScriptCanOpenWindowsAutomatically = false
                                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                }
                                val web = this
                                CookieManager.getInstance().apply {
                                    setAcceptCookie(true)
                                    setAcceptThirdPartyCookies(web, true)
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        progress = newProgress
                                    }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): Boolean {
                                        val target = request?.url?.toString().orEmpty()
                                        return if (YoutubeMusicCookieRules.isAllowedLoginUrl(target)) {
                                            navWarning = null
                                            false
                                        } else {
                                            navWarning = "Navigasi dibatasi ke halaman login HTTPS Google & YouTube."
                                            true
                                        }
                                    }

                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        refreshCookieStatus()
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        refreshCookieStatus()
                                    }
                                }
                                webViewRef = this
                                loadUrl("https://music.youtube.com")
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ImportYoutubeMusicCookiesDialog(
    onDismiss: () -> Unit,
    onImportCookies: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rawText by remember { mutableStateOf("") }
    var fileError by remember { mutableStateOf<String?>(null) }
    var readingFile by remember { mutableStateOf(false) }

    val validated = remember(rawText) {
        if (rawText.isBlank()) Result.failure(IllegalArgumentException(""))
        else runCatching { YoutubeMusicCookieRules.sanitizeNetscapeCookies(rawText) }
    }

    val pickFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        readingFile = true
        fileError = null
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { readBoundedCookiesFile(context, uri) }
            readingFile = false
            loaded.onSuccess { text -> rawText = text }
                .onFailure { err -> fileError = err.message ?: "Gagal membaca file cookies." }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(UiIcons.Link, null) },
        title = { Text("Impor cookies.txt YT Music") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Ekspor format Netscape cookies.txt setelah login di music.youtube.com (disarankan pakai akun sekunder / slot Family). Hanya cookie YouTube yang disimpan terenkripsi dengan Android Keystore.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { pickFileLauncher.launch(arrayOf("text/plain", "*/*")) },
                    enabled = !readingFile,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                ) {
                    Text(if (readingFile) "Membaca file..." else "Pilih file cookies.txt")
                }
                OutlinedTextField(
                    value = rawText,
                    onValueChange = {
                        rawText = it
                        fileError = null
                    },
                    label = { Text("Atau tempel isi Netscape cookies.txt") },
                    placeholder = { Text("# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t...") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (fileError != null) {
                    Text(
                        fileError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (rawText.isNotBlank()) {
                    val sanitized = validated.getOrNull()
                    if (sanitized != null) {
                        val count = remember(sanitized) { YoutubeMusicCookieRules.countCookies(sanitized) }
                        Text(
                            "Valid: $count cookie YouTube siap disimpan terenkripsi.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text(
                            validated.exceptionOrNull()?.message ?: "Format cookies.txt belum valid.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImportCookies(rawText) },
                enabled = validated.isSuccess && !readingFile,
            ) {
                Text("Simpan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        },
    )
}

private fun readBoundedCookiesFile(context: Context, uri: Uri): Result<String> = runCatching {
    val maxBytes = YoutubeMusicCookieRules.MAX_RAW_IMPORT_BYTES
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            total += read
            require(total <= maxBytes) { "File cookies terlalu besar (maksimal 256 KB)." }
            out.write(buf, 0, read)
        }
        out.toByteArray()
    } ?: error("Tidak dapat membuka file pilihan.")
    bytes.toString(Charsets.UTF_8)
}
