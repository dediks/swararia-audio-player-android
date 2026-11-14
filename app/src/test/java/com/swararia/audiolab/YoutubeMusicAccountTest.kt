package com.swararia.audiolab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class YoutubeMusicAccountTest {
    private class MemoryStore : YoutubeMusicSessionStore {
        var stored: PersistedYoutubeMusicSession? = null
        override fun load() = stored
        override fun save(session: PersistedYoutubeMusicSession) {
            stored = session
        }
        override fun clear() {
            stored = null
        }
    }

    @Test fun netscapeParserKeepsYoutubeCookiesStripsForeignDomainsAndDropsExpired() {
        val now = 1_750_000_000L
        val raw = """
            # Netscape HTTP Cookie File
            .google.com	TRUE	/	TRUE	${now + 3600}	GMAIL_AT	secret-gmail
            .evil.example	TRUE	/	TRUE	${now + 3600}	SESSION	bad
            #HttpOnly_.youtube.com	TRUE	/	TRUE	${now + 3600}	LOGIN_INFO	AFmmF2swRAIgTest:QUQ3MjNmdQ
            .youtube.com	TRUE	/	TRUE	${now + 3600}	__Secure-3PAPISID	abc123/XYZ
            .youtube.com	TRUE	/	TRUE	${now - 10}	STALE_COOKIE	expired
            music.youtube.com	FALSE	/	TRUE	0	PREF	hl=en
        """.trimIndent()

        val clean = YoutubeMusicCookieRules.sanitizeNetscapeCookies(raw, nowEpochSeconds = now)

        assertTrue(clean.startsWith("# Netscape HTTP Cookie File\n"))
        assertFalse("Foreign google/gmail cookies must not be stored", clean.contains("GMAIL_AT"))
        assertFalse("Unrelated domain cookies must be stripped", clean.contains("evil.example"))
        assertFalse("Expired cookies must be stripped", clean.contains("STALE_COOKIE"))
        assertTrue(clean.contains("LOGIN_INFO\tAFmmF2swRAIgTest:QUQ3MjNmdQ"))
        assertTrue(clean.contains("__Secure-3PAPISID\tabc123/XYZ"))
        assertEquals(3, YoutubeMusicCookieRules.countCookies(clean))
    }

    @Test fun netscapeParserRejectsJsonControlCharsAndMissingAuth() {
        val jsonAttempt = runCatching { YoutubeMusicCookieRules.sanitizeNetscapeCookies("""[{"name":"SAPISID"}]""") }
        assertTrue(jsonAttempt.isFailure)

        val missingLogin = runCatching {
            YoutubeMusicCookieRules.sanitizeNetscapeCookies(
                ".youtube.com\tTRUE\t/\tTRUE\t0\tSAPISID\tabc\n",
            )
        }
        assertTrue(missingLogin.isFailure)

        val crlfInjection = """
            # Netscape HTTP Cookie File
            .youtube.com	TRUE	/	TRUE	0	LOGIN_INFO	ok
            .youtube.com	TRUE	/	TRUE	0	SAPISID	bad	injected
        """.trimIndent()
        val withBadColumnCount = runCatching { YoutubeMusicCookieRules.sanitizeNetscapeCookies(crlfInjection) }
        assertTrue(withBadColumnCount.isFailure)
    }

    @Test fun webViewCookieMergeBuildsValidNetscapeJarAndMatchesUrlsOnlyForYoutube() {
        val netscape = YoutubeMusicCookieRules.fromWebViewCookieStrings(
            wwwYoutubeCookies = "LOGIN_INFO=test_login; SAPISID=sapi_value; __Host-EXTRA=host_val",
            musicYoutubeCookies = "SAPISID=sapi_value; PREF=f6=40000000",
        )
        assertTrue(netscape.contains(".youtube.com\tTRUE\t/\tTRUE\t0\tLOGIN_INFO\ttest_login"))
        assertTrue(netscape.contains("www.youtube.com\tFALSE\t/\tTRUE\t0\t__Host-EXTRA\thost_val"))
        assertTrue(netscape.contains("PREF\tf6=40000000"))

        val ytHeader = YoutubeMusicCookieRules.cookieHeaderForUrl(netscape, "https://music.youtube.com/")
        assertTrue(ytHeader.contains("LOGIN_INFO=test_login"))
        assertTrue(ytHeader.contains("SAPISID=sapi_value"))
        assertFalse("Host-only www cookie must not be sent to music subdomain", ytHeader.contains("__Host-EXTRA"))

        val musicOnlyMerged = YoutubeMusicCookieRules.fromWebViewCookieStrings(
            wwwYoutubeCookies = "LOGIN_INFO=li; SAPISID=sp",
            musicYoutubeCookies = "__Secure-3PAPISID=sec3; __Secure-1PSIDTS=ts1; __Host-YTM=ytm_only",
        )
        val wwwHeader = YoutubeMusicCookieRules.cookieHeaderForUrl(musicOnlyMerged, "https://www.youtube.com/")
        assertTrue("Shared __Secure- auth cookies captured on YTM must also serve www.youtube.com", wwwHeader.contains("__Secure-3PAPISID=sec3"))
        assertTrue(wwwHeader.contains("__Secure-1PSIDTS=ts1"))
        assertFalse("Host-only __Host- YTM cookie must stay pinned to music.youtube.com", wwwHeader.contains("__Host-YTM"))

        assertEquals("", YoutubeMusicCookieRules.cookieHeaderForUrl(netscape, "https://evil.com/"))
        assertEquals("", YoutubeMusicCookieRules.cookieHeaderForUrl(netscape, "http://www.youtube.com/"))
    }

    @Test fun loginAllowlistRestrictsNavigationToTrustedHttpsGoogleAndYoutubeHosts() {
        assertTrue(YoutubeMusicCookieRules.isAllowedLoginUrl("https://music.youtube.com/"))
        assertTrue(YoutubeMusicCookieRules.isAllowedLoginUrl("https://accounts.google.com/ServiceLogin"))
        assertTrue(YoutubeMusicCookieRules.isAllowedLoginUrl("https://accounts.google.co.id/accounts/SetSID"))
        assertTrue(YoutubeMusicCookieRules.isAllowedLoginUrl("https://accounts.youtube.com/accounts/SetSID"))

        assertFalse(YoutubeMusicCookieRules.isAllowedLoginUrl("http://music.youtube.com/"))
        assertFalse(YoutubeMusicCookieRules.isAllowedLoginUrl("file:///etc/passwd"))
        assertFalse(YoutubeMusicCookieRules.isAllowedLoginUrl("javascript:alert(1)"))
        assertFalse(YoutubeMusicCookieRules.isAllowedLoginUrl("https://accounts.google.com.evil.example/"))
        assertFalse(YoutubeMusicCookieRules.isAllowedLoginUrl("https://user:pass@music.youtube.com/"))
    }

    @Test fun managerTracksPremiumTierToggleReauthFallbackAndDisconnectCleanly() = runBlocking {
        val store = MemoryStore()
        var platformCleared = 0
        var fakeResult: YoutubeMusicTierCheckResult = YoutubeMusicTierCheckResult.Premium
        val manager = YoutubeMusicAccountManager(
            store = store,
            verifier = { fakeResult },
            onPlatformCookiesCleared = { platformCleared += 1 },
            ioDispatcher = Dispatchers.Unconfined,
            nowMillis = { 1_750_000_000_000L },
        )

        val state = manager.connectFromWebView(
            wwwCookies = "LOGIN_INFO=li; SAPISID=sp",
            musicCookies = null,
        )
        assertTrue(state.connected)
        assertTrue(state.enabledForDownloads)
        assertEquals(YoutubeMusicPremiumTier.PREMIUM, state.tier)
        assertFalse(state.needsReauth)
        assertNotNull(manager.activeNetscapeCookiesForDownload())

        manager.setEnabledForDownloads(false)
        assertNull("Disabled account must not attach cookies to downloads", manager.activeNetscapeCookiesForDownload())
        assertTrue(manager.state.value.connected)

        manager.setEnabledForDownloads(true)
        assertNotNull(manager.activeNetscapeCookiesForDownload())

        manager.refreshFromYtDlpOutput("# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t0\tVISITOR_INFO1_LIVE\tv\n")
        assertTrue("Rotated-out auth cookies must mark session needsReauth", manager.state.value.needsReauth)
        assertNull(manager.activeNetscapeCookiesForDownload())

        manager.confirmPremiumFromDownload("256k Opus")
        assertFalse(manager.state.value.needsReauth)
        assertEquals(YoutubeMusicPremiumTier.PREMIUM, manager.state.value.tier)
        assertTrue(manager.state.value.statusNote?.contains("256k Opus") == true)

        val disconnected = manager.disconnect()
        assertFalse(disconnected.connected)
        assertNull(store.stored)
        assertNull(manager.activeNetscapeCookiesForDownload())
        assertEquals(1, platformCleared)
    }
}
