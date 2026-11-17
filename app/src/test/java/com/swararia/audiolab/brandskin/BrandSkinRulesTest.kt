package com.swararia.audiolab.brandskin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandSkinRulesTest {
    @Test
    fun sanitizesLabelsAndClampsLengths() {
        assertEquals("RAJA SOUND PRO", BrandSkinRules.sanitizeRentalName("  RAJA\nSOUND\t  PRO  "))
        assertEquals(
            BrandSkinRules.MAX_RENTAL_NAME_LENGTH,
            BrandSkinRules.sanitizeRentalName("A".repeat(80)).length,
        )
        assertEquals("GALA NIGHT", BrandSkinRules.sanitizeEventTag("\tGALA\r\n  NIGHT "))
        assertEquals(
            BrandSkinRules.MAX_EVENT_TAG_LENGTH,
            BrandSkinRules.sanitizeEventTag("B".repeat(80)).length,
        )
    }

    @Test
    fun logoKeyValidationRejectsTraversalAndInvalidLengths() {
        val validKey = "a".repeat(64)
        assertTrue(BrandSkinRules.validLogoKey(validKey))
        assertFalse(BrandSkinRules.validLogoKey(null))
        assertFalse(BrandSkinRules.validLogoKey("a".repeat(63)))
        assertFalse(BrandSkinRules.validLogoKey("../etc/passwd".padEnd(64, 'a')))
        assertFalse(BrandSkinRules.validLogoKey("A".repeat(64)))
    }

    @Test
    fun presetAndTitleFallbacksMatchSwrPlayerDefaultsWhenBlank() {
        val defaults = BrandSkinConfig()
        assertEquals(FohSkinPreset.SWARARIA_EMERALD, BrandSkinRules.resolvePreset("unknown"))
        assertEquals("SWR Player", BrandSkinRules.effectiveRentalTitle(defaults))
        assertEquals("Pro Audio Player", BrandSkinRules.effectiveEventSubtitle(defaults))
        assertFalse(BrandSkinRules.hasCustomBrand(defaults))
        assertTrue(BrandSkinRules.shouldShowWelcomeBrandCard(defaults))
        assertFalse(BrandSkinRules.shouldShowWelcomeBrandCard(defaults.copy(welcomeBannerDismissed = true)))

        val rental = BrandSkinConfig(
            presetId = FohSkinPreset.ROYAL_GOLD.id,
            rentalName = "RAJA SOUND PRO",
            eventTag = "MAIN STAGE",
        )
        assertEquals("RAJA SOUND PRO", BrandSkinRules.effectiveRentalTitle(rental))
        assertEquals("MAIN STAGE", BrandSkinRules.effectiveEventSubtitle(rental))
        assertEquals(FohSkinPreset.ROYAL_GOLD.accentArgb, BrandSkinRules.activeAccentArgb(rental))
        assertTrue(BrandSkinRules.hasCustomBrand(rental))
        assertFalse(BrandSkinRules.shouldShowWelcomeBrandCard(rental))
    }

    @Test
    fun vibrantLogoAccentExtractorFindsChromaticColorAndSkipsGrayscale() {
        val grey = IntArray(64) { 0xFF808080.toInt() }
        assertNull(BrandSkinRules.extractVibrantAccentArgb(grey))

        val transparentAndGold = IntArray(128) { idx ->
            if (idx < 64) 0x00000000 else 0xFFD4A12A.toInt()
        }
        val extracted = BrandSkinRules.extractVibrantAccentArgb(transparentAndGold)
        assertNotNull(extracted)
        val r = ((extracted!! ushr 16) and 0xFF).toInt()
        val g = ((extracted ushr 8) and 0xFF).toInt()
        val b = (extracted and 0xFF).toInt()
        assertTrue("Expected warm gold/yellow balance (R >= G > B), got ($r,$g,$b)", r >= g && g > b)

        val customConfig = BrandSkinConfig(
            customAccentArgb = extracted,
            useCustomLogoAccent = true,
        )
        assertEquals(extracted, BrandSkinRules.activeAccentArgb(customConfig))
    }
}
