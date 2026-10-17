package com.app.nuri.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Home — SCAFFOLD placeholder.
 *
 * The real home is the one-voice senior interface (tap-to-talk, check-in,
 * routine anchors) — GATED until Scout lessons are folded in. Nothing here
 * is a feature; this screen exists so the frame compiles and navigates.
 */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    Column(
        // verticalScroll: readability standard — at large system font scales
        // the content scrolls instead of clipping (brief design laws).
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Nuri", style = MaterialTheme.typography.headlineLarge)
        Text("Your companion is being built. This is a starter screen — " +
            "the voice home arrives in a later build.")
    }
}
