package com.app.nuri.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.app.nuri.repository.SettingsRepository

/**
 * Theme — applies the person's APPEARANCE choices (brief §3 readability law,
 * adjustable within the standard): a curated AAA color preset (default
 * CLASSIC — dark text on a light, uncluttered background) + the standard
 * typography at their font step ([nuriTypography], default FontStep.EASY).
 * Choices come from the settings repository, persist across updates, and an
 * update must never reset them. Screens never set their own sizes/colors
 * outside these tokens.
 */
@Composable
fun NuriTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository(context.applicationContext) }
    val appearanceTheme by repo.appearanceTheme.collectAsState(initial = AppearanceTheme.CLASSIC)
    val fontStep by repo.fontStep.collectAsState(initial = FontStep.EASY)
    MaterialTheme(
        colorScheme = appearanceTheme.colorScheme(),
        typography = nuriTypography(fontStep.scale),
        content = content
    )
}
