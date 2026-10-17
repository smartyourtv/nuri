package com.app.nuri.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * Appearance — ADJUSTABLE WITHIN THE STANDARD (brief §3 readability law,
 * Shawn 2026-10-16). Font size, theme, and colors are settings, but every
 * choice stays inside the "very easy" range: curated presets only (no
 * free-form picker, so contrast can't be broken), and the font steps only
 * ever scale the standard sizes UP.
 *
 * Who sets it: the senior anytime in Settings; in Hybrid/Managed the
 * caregiver can too. Choices persist in the settings repository and survive
 * updates — an update must NEVER reset them (interface freeze).
 */

/** Font size steps. EASY is the readability standard itself (scale 1.0). */
enum class FontStep(val label: String, val scale: Float) {
    EASY("Easy", 1.0f),
    EASIER("Easier", 1.15f),
    EASIEST("Easiest", 1.3f)
}

/**
 * Curated themes. Each preset's body-text contrast was computed with the
 * WCAG formula (see [contrastRatio]) and meets the AAA 7:1 target:
 * CLASSIC 21.0:1, SOFT 16.0:1, DARK 21.0:1, YELLOW_ON_BLACK 19.6:1 —
 * primary-button pairs meet it too (asserted by AppearanceTest).
 */
enum class AppearanceTheme(val label: String, val bodyTextHex: Long, val backgroundHex: Long) {
    CLASSIC("Classic", 0xFF000000, 0xFFFFFFFF),
    SOFT("Soft", 0xFF211A12, 0xFFFFF6E0),
    DARK("Dark", 0xFFFFFFFF, 0xFF000000),
    YELLOW_ON_BLACK("Yellow on black", 0xFFFFFF00, 0xFF000000);

    /** Body-text contrast ratio of this preset (WCAG formula). */
    fun bodyContrastRatio(): Double = contrastRatio(bodyTextHex, backgroundHex)

    fun colorScheme(): ColorScheme {
        val bg = Color(backgroundHex)
        val fg = Color(bodyTextHex)
        // Secondary text (onSurfaceVariant) is set to the SAME color as
        // primary text on purpose: senior-facing labels must hold the same
        // AAA contrast as body text — no grey-on-grey, ever.
        return if (this == DARK || this == YELLOW_ON_BLACK) {
            darkColorScheme(
                background = bg, surface = bg, onBackground = fg, onSurface = fg,
                onSurfaceVariant = fg, surfaceVariant = bg,
                primary = fg, onPrimary = bg
            )
        } else {
            lightColorScheme(
                background = bg, surface = bg, onBackground = fg, onSurface = fg,
                onSurfaceVariant = fg, surfaceVariant = bg,
                primary = fg, onPrimary = bg
            )
        }
    }
}

/** WCAG relative luminance of an ARGB color packed in a Long. */
fun relativeLuminance(argb: Long): Double {
    fun channel(v: Long): Double {
        val c = (v and 0xFF) / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    val r = channel(argb shr 16)
    val g = channel(argb shr 8)
    val b = channel(argb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/** WCAG contrast ratio between two ARGB colors packed in Longs (1.0–21.0). */
fun contrastRatio(first: Long, second: Long): Double {
    val l1 = relativeLuminance(first)
    val l2 = relativeLuminance(second)
    val lighter = maxOf(l1, l2)
    val darker = minOf(l1, l2)
    return (lighter + 0.05) / (darker + 0.05)
}
