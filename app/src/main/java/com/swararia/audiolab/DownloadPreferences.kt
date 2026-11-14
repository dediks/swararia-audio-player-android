package com.swararia.audiolab

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class DownloadRateLimit(
    val label: String,
    val shortLabel: String,
    val ytDlpArg: String?,
) {
    UNLIMITED("Tanpa batas", "Penuh", null),
    LIMIT_2M("Maks 2 MB/s", "2 MB/s", "2M"),
    LIMIT_1M("Maks 1 MB/s", "1 MB/s", "1M"),
    LIMIT_500K("Maks 500 KB/s", "500 KB/s", "500K"),
}

data class DownloadSettingsState(
    val wifiOnly: Boolean = false,
    val rateLimit: DownloadRateLimit = DownloadRateLimit.UNLIMITED,
)

/** Pure policy helpers validated on the JVM without Android framework classes. */
object DownloadPolicyRules {
    @JvmStatic
    fun canDownloadOnNetwork(
        wifiOnly: Boolean,
        hasInternet: Boolean,
        isWifiOrEthernet: Boolean,
    ): Boolean {
        if (!wifiOnly) return true
        return hasInternet && isWifiOrEthernet
    }

    @JvmStatic
    fun ytDlpRateLimitArg(rateLimit: DownloadRateLimit): String? = rateLimit.ytDlpArg
}

class DownloadPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(loadInitial())
    val state: StateFlow<DownloadSettingsState> = mutableState.asStateFlow()

    private fun loadInitial(): DownloadSettingsState {
        val wifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, false)
        val rawRate = prefs.getString(KEY_RATE_LIMIT, DownloadRateLimit.UNLIMITED.name)
        val rate = DownloadRateLimit.entries.firstOrNull { it.name == rawRate } ?: DownloadRateLimit.UNLIMITED
        return DownloadSettingsState(wifiOnly = wifiOnly, rateLimit = rate)
    }

    fun setWifiOnly(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY, enabled).apply()
        mutableState.update { it.copy(wifiOnly = enabled) }
    }

    fun setRateLimit(rateLimit: DownloadRateLimit) {
        prefs.edit().putString(KEY_RATE_LIMIT, rateLimit.name).apply()
        mutableState.update { it.copy(rateLimit = rateLimit) }
    }

    fun canDownloadOnCurrentNetwork(): Boolean {
        val wifiOnly = state.value.wifiOnly
        if (!wifiOnly) return true
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return DownloadPolicyRules.canDownloadOnNetwork(
            wifiOnly = wifiOnly,
            hasInternet = hasInternet,
            isWifiOrEthernet = isWifi,
        )
    }

    private companion object {
        const val PREFS_NAME = "swararia_download_prefs"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_RATE_LIMIT = "rate_limit"
    }
}
