package com.app.nuri.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.nuri.games.MatchingGameLogic
import com.app.nuri.tier.EffectiveSettings

/**
 * One complete game: tap the matching shape. Big targets (96dp), NO timers,
 * NO fail state, NO score. Caller must gate on EffectiveSettings — this
 * composable double-checks and hides itself when games are not allowed
 * (Guided: hidden by law).
 */
@Composable
fun MatchingGameScreen(effective: EffectiveSettings, modifier: Modifier = Modifier) {
    if (!effective.gamesAllowed) {
        Column(modifier.fillMaxSize().padding(24.dp)) { Text("Games are turned off for this person.") }
        return
    }
    var roundIndex by remember { mutableStateOf(0) }
    var round by remember { mutableStateOf(MatchingGameLogic.newRound(0)) }
    var feedback by remember { mutableStateOf("Tap the shape that matches the big one. There is no hurry.") }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Matching shapes — just for enjoyment", style = MaterialTheme.typography.headlineMedium)
        Text("Find this one:", style = MaterialTheme.typography.titleLarge)
        Text(round.target.symbol, fontSize = 72.sp)
        round.options.forEach { option ->
            Button(onClick = {
                feedback = MatchingGameLogic.feedbackFor(option, round)
                if (MatchingGameLogic.isCorrect(option, round)) { roundIndex++; round = MatchingGameLogic.newRound(roundIndex) }
            }, modifier = Modifier.fillMaxWidth().height(96.dp)) {
                Text("${option.symbol}  ${option.label}", fontSize = 28.sp)
            }
        }
        Text(feedback, style = MaterialTheme.typography.titleMedium)
        Text("No timer, no score — play as long as you like, stop any time.")
    }
}
