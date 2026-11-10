package com.swararia.audiolab
import org.junit.Assert.*
import org.junit.Test
class ThumbnailPolicyTest {
    @Test fun onlyPublicYoutubeHttpsImageHostsAreAllowed() {
        val type = runCatching { Class.forName("com.swararia.audiolab.ThumbnailPolicy") }.getOrNull()
        assertNotNull("HTTPS thumbnail allowlist is not implemented", type)
        val method = type!!.getMethod("allowed", String::class.java)
        fun allowed(url: String?) = method.invoke(null, url) as Boolean
        listOf("https://i.ytimg.com/vi/id/hqdefault.jpg", "https://lh3.googleusercontent.com/cover=w128", "https://yt3.ggpht.com/avatar").forEach { assertTrue(it, allowed(it)) }
        listOf(null, "", "http://i.ytimg.com/a", "https://localhost/a", "https://127.0.0.1/a", "https://i.ytimg.com.evil.example/a", "https://i.ytimg.com@evil.example/a", "https://user:pass@i.ytimg.com/a", "https://i.ytimg.com:444/a", "file:///sdcard/private", "https://evil.example/a", "https://i.ytimg.com/"+"a".repeat(5000)).forEach { assertFalse(it, allowed(it)) }
    }
}
