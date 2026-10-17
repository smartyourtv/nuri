package com.app.nuri.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * READABILITY STANDARD (brief design laws — Shawn 2026-10-16: "I want Nuri
 * to be very easy to read"). This object is the ONE place the standard is
 * enforced: every Material 3 text style is overridden here, so any style a
 * screen picks is compliant by construction. Do not set senior-facing
 * fontSize literals outside this file (game symbols excepted — they are
 * pictures, not reading text).
 *
 * Floors (senior-facing UI, all tiers, both flavors):
 *  - body text >= 20sp; secondary/labels >= 18sp; NOTHING below 16sp
 *    anywhere senior-facing (settings, consent, errors, empty states —
 *    no fine print);
 *  - headings >= 28sp; primary button text >= 22sp (M3 buttons use
 *    labelLarge);
 *  - weights: Regular and SemiBold only (no thin/light);
 *  - line height 1.35–1.5; slightly increased letter spacing;
 *  - font: clean humanist sans (system SansSerif);
 *  - contrast: the theme is a light scheme with dark text (NuriTheme),
 *    targeting WCAG AAA (7:1) for body text; nothing important is conveyed
 *    by color alone;
 *  - system font scaling is HONORED (sp units throughout — never lock text
 *    size against the Android font-size setting); layouts must scroll
 *    rather than clip at 1.3x scale.
 * Caregiver dashboard (different audience): AA minimum, body >= 16sp —
 * these tokens exceed that too.
 */
object NuriText {
    const val BODY_MIN_SP = 20
    const val LABEL_MIN_SP = 18
    const val ABSOLUTE_MIN_SP = 16
    const val HEADING_MIN_SP = 28
    const val BUTTON_MIN_SP = 22
}

private fun style(size: Float, lineHeight: Float, weight: FontWeight) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = (size * 0.02).sp
)

/**
 * The standard typography at a FontStep scale (Appearance.kt). Steps only
 * scale UP from the standard (1.0 / 1.15 / 1.3) — the floors in [NuriText]
 * are the scale-1.0 sizes, so no choice can go below them.
 */
fun nuriTypography(scale: Float): Typography {
    fun s(size: Int, lineHeight: Int, weight: FontWeight) =
        style(size * scale, lineHeight * scale, weight)
    return Typography(
        displayLarge = s(34, 46, FontWeight.SemiBold),
        displayMedium = s(32, 44, FontWeight.SemiBold),
        displaySmall = s(30, 41, FontWeight.SemiBold),
        headlineLarge = s(32, 44, FontWeight.SemiBold),
        headlineMedium = s(28, 38, FontWeight.SemiBold),
        headlineSmall = s(28, 38, FontWeight.SemiBold),
        titleLarge = s(24, 33, FontWeight.SemiBold),
        titleMedium = s(18, 25, FontWeight.SemiBold),
        titleSmall = s(18, 25, FontWeight.SemiBold),
        bodyLarge = s(20, 28, FontWeight.Normal),
        bodyMedium = s(20, 28, FontWeight.Normal),
        bodySmall = s(18, 25, FontWeight.Normal),
        labelLarge = s(22, 30, FontWeight.SemiBold),
        labelMedium = s(18, 25, FontWeight.SemiBold),
        labelSmall = s(18, 25, FontWeight.SemiBold)
    )
}

/** The standard at its default step (FontStep.EASY). */
val NuriTypography = nuriTypography(FontStep.EASY.scale)
