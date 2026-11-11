package com.swararia.audiolab

import org.junit.Assert.*
import org.junit.Test

class DataRulesTest {
    private fun rules() = Class.forName("com.swararia.audiolab.DownloadQueueRules")
    @Test fun consentLimitsAndUrlsAreValidated() {
        val type = rules()
        val validate = type.getMethod("validate", List::class.java, Boolean::class.javaPrimitiveType)
        val instance = type.getField("INSTANCE").get(null)
        fun valid(urls: List<String>, consent: Boolean) = validate.invoke(instance, urls, consent)
        assertEquals(listOf("https://www.youtube.com/watch?v=abcdefgh_12"), valid(listOf("https://youtu.be/abcdefgh_12?si=share", "https://music.youtube.com/watch?v=abcdefgh_12&feature=share"), true))
        for (url in listOf("https://example.com/a?signature=secret", "https://www.youtube.com/watch?v=abcdefgh_12&token=secret", "https://youtu.be/abcdefgh_12?sig=secret", "https://www.youtube.com/watch?v=bad", "https://youtu.be/abcdefgh_12#token=secret", "https://www.youtube.com/watch?v=abcdefgh_12&v=abcdefgh_12")) {
            assertThrows(java.lang.reflect.InvocationTargetException::class.java) { valid(listOf(url), true) }
        }
        for (urls in listOf(emptyList(), List(51) { "https://example.com/$it" }, listOf("http://example.com/a"), listOf("https://a:b@example.com/a"), listOf("https://example.com/a\n"))) {
            assertThrows(java.lang.reflect.InvocationTargetException::class.java) { valid(urls, true) }
        }
        assertThrows(java.lang.reflect.InvocationTargetException::class.java) { valid(listOf("https://example.com/a"), false) }
    }
    @Test fun transitionsProtectRunningWorkAndTerminalStates() {
        val type = rules(); val instance = type.getField("INSTANCE").get(null)
        val transition = type.getMethod("canTransition", String::class.java, String::class.java)
        fun allowed(from: String, to: String) = transition.invoke(instance, from, to) as Boolean
        assertTrue(allowed("QUEUED", "RUNNING")); assertTrue(allowed("RUNNING", "COMPLETED"))
        assertTrue(allowed("RUNNING", "CANCELED")); assertTrue(allowed("FAILED", "QUEUED"))
        assertFalse(allowed("COMPLETED", "RUNNING")); assertFalse(allowed("CANCELED", "QUEUED"))
        val cancel = type.getMethod("batchCancelable", String::class.java)
        assertEquals(true, cancel.invoke(instance, "QUEUED")); assertEquals(false, cancel.invoke(instance, "RUNNING"))
        val progress = type.getMethod("validProgress", Float::class.javaObjectType)
        assertEquals(null, progress.invoke(instance, null as Any?)); assertEquals(0.5f, progress.invoke(instance, 0.5f))
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.1f, 1.1f)) assertThrows(java.lang.reflect.InvocationTargetException::class.java) { progress.invoke(instance, bad) }
    }
    @Test fun durableThumbnailsNeverContainQueriesFragmentsOrCredentials() {
        assertEquals("https://i.ytimg.com/vi/abcdefgh_12/default.jpg", DownloadQueueRules.durableThumbnail("https://i.ytimg.com/vi/abcdefgh_12/default.jpg"))
        for (url in listOf("https://i.ytimg.com/a?sig=secret", "https://i.ytimg.com/a#secret", "https://user:secret@i.ytimg.com/a", "https://evil.example/a")) assertNull(DownloadQueueRules.durableThumbnail(url))
    }
    @Test fun artworkTransferHasAnOverallElapsedBudget() {
        assertTrue(ArtworkRules.transferWithinBudget(0L, 14_999_999_999L))
        assertFalse(ArtworkRules.transferWithinBudget(0L, 15_000_000_000L))
    }
    @Test fun artworkKeysAndDimensionsAreBounded() {
        val type = Class.forName("com.swararia.audiolab.ArtworkRules"); val instance = type.getField("INSTANCE").get(null)
        val key = type.getMethod("validKey", String::class.java)
        assertEquals(true, key.invoke(instance, "a".repeat(64)))
        for (bad in listOf(null, "../escape", "a".repeat(63), "g".repeat(64), "A".repeat(64))) assertEquals(false, key.invoke(instance, bad))
        val dimensions = type.getMethod("validDimensions", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        assertEquals(true, dimensions.invoke(instance, 640, 480))
        for ((w,h) in listOf(0 to 1, 1 to -1, 10000 to 10000, 4097 to 1)) assertEquals(false, dimensions.invoke(instance, w, h))
    }
    @Test fun deleteOnlyAcceptsCanonicalManagedItems() {
        val type = Class.forName("com.swararia.audiolab.ManagedCopyRules"); val instance = type.getField("INSTANCE").get(null)
        val method = type.getMethod("validUri", String::class.java)
        assertEquals(true, method.invoke(instance, "content://media/external_primary/audio/media/12"))
        for (bad in listOf("content://media/external/audio/media/12", "content://media/external_primary/audio/media/012", "content://media/external_primary/audio/media/12?x=1", "file:///a", "content://evil/external_primary/audio/media/12")) assertEquals(false, method.invoke(instance, bad))
    }
}
