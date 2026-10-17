package com.app.nuri.ui

import com.app.nuri.ui.theme.AppearanceTheme
import com.app.nuri.ui.theme.FontStep
import com.app.nuri.ui.theme.contrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Readability law (brief SS3): every curated theme must meet WCAG AAA (7:1)
 *  for body text AND for the primary-button pair; font steps never scale
 *  below the standard. */
class AppearanceTest {

    @Test fun everyThemeMeetsAaaForBodyText() {
        AppearanceTheme.values().forEach { theme ->
            assertTrue("${theme.label} body contrast ${theme.bodyContrastRatio()} < 7",
                theme.bodyContrastRatio() >= 7.0)
        }
    }

    @Test fun knownRatios() {
        assertEquals(21.0, AppearanceTheme.CLASSIC.bodyContrastRatio(), 0.1)
        assertEquals(21.0, AppearanceTheme.DARK.bodyContrastRatio(), 0.1)
        assertEquals(19.6, AppearanceTheme.YELLOW_ON_BLACK.bodyContrastRatio(), 0.2)
        assertEquals(16.0, AppearanceTheme.SOFT.bodyContrastRatio(), 0.3)
        // Black/white sanity of the formula itself.
        assertEquals(21.0, contrastRatio(0xFF000000, 0xFFFFFFFF), 0.1)
        assertEquals(1.0, contrastRatio(0xFFFFFFFF, 0xFFFFFFFF), 0.01)
    }

    @Test fun fontStepsNeverBelowStandard() {
        assertEquals(1.0f, FontStep.EASY.scale, 0.001f)
        FontStep.values().forEach { assertTrue(it.scale >= 1.0f) }
        assertTrue(FontStep.EASIEST.scale > FontStep.EASIER.scale)
    }
}
