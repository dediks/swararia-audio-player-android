package com.swararia.audiolab

import java.net.URI
import java.util.Locale

/** Only public first-party HTTPS image endpoints; no arbitrary network destinations. */
object ThumbnailPolicy {
    private val hosts = setOf("i.ytimg.com", "i1.ytimg.com", "i2.ytimg.com", "i3.ytimg.com", "i4.ytimg.com", "i9.ytimg.com", "yt3.ggpht.com", "lh3.googleusercontent.com")
    @JvmStatic fun allowed(url: String?): Boolean {
        if (url.isNullOrBlank() || url.length > 4096 || url.any { it.isISOControl() }) return false
        return runCatching {
            val uri = URI(url)
            uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null &&
                (uri.port == -1 || uri.port == 443) && uri.host?.lowercase(Locale.ROOT) in hosts
        }.getOrDefault(false)
    }
}
