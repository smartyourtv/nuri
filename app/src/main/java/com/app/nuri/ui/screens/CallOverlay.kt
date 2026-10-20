package com.app.nuri.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.app.nuri.call.CallCoordinator

/**
 * The senior phone's call surface (design §17). While a call is
 * live this fills the screen and cannot be swiped away — the
 * persistent, unmissable live state. Controls are exactly the big
 * ones the design allows: Talk / No before a call opens, camera
 * off/on and End during it, and the emergency offer in the
 * staying-with-him state. No other controls exist here, on purpose.
 */
@Composable
fun CallOverlay() {
    val context = LocalContext.current
    val state by CallCoordinator.uiState.collectAsState()
    if (state.phase == CallCoordinator.Phase.IDLE) return
    // The caregiver's waiting/answer states render in the dashboard
    // panel, not over the senior UI — phases here are senior-side.
    if (state.phase == CallCoordinator.Phase.WAITING_CAREGIVER) return

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.phase == CallCoordinator.Phase.IN_CALL) {
                Text(
                    "● CALL IS LIVE",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color(0xFFB00020),
                )
            }
            Text(state.headline, style = MaterialTheme.typography.headlineMedium)
            if (state.detail.isNotBlank()) Text(state.detail, style = MaterialTheme.typography.titleMedium)

            if (state.phase == CallCoordinator.Phase.IN_CALL ||
                state.phase == CallCoordinator.Phase.CONNECTING
            ) {
                SeniorVideoViews()
            }

            Spacer(Modifier.height(8.dp))
            if (state.showAnswer) {
                Button(
                    onClick = { CallCoordinator.uiAnswer() },
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                ) { Text("Talk", style = MaterialTheme.typography.headlineSmall) }
            }
            if (state.showDecline) {
                OutlinedButton(
                    onClick = { CallCoordinator.uiDecline() },
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                ) { Text("No", style = MaterialTheme.typography.headlineSmall) }
            }
            if (state.phase == CallCoordinator.Phase.IN_CALL && state.media == com.app.nuri.call.CallMedia.VIDEO_AND_AUDIO) {
                OutlinedButton(
                    onClick = { CallCoordinator.uiToggleVideo() },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                ) { Text(if (state.videoOn) "Turn off camera" else "Turn camera on", style = MaterialTheme.typography.titleLarge) }
            }
            if (state.showEmergencyOffer) {
                Button(
                    onClick = { CallCoordinator.uiEmergencyOffer(context) },
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                ) { Text("Call for help now", style = MaterialTheme.typography.headlineSmall) }
            }
            if (state.showEnd) {
                Button(
                    onClick = { CallCoordinator.uiEnd(context) },
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                ) { Text("End call", style = MaterialTheme.typography.headlineSmall) }
            }
            if (state.phase == CallCoordinator.Phase.ENDED) {
                Button(
                    onClick = { CallCoordinator.uiDismissEnded() },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                ) { Text("OK", style = MaterialTheme.typography.titleLarge) }
            }
        }
    }
}

/** His camera picture (small) + the caregiver's picture (large), for the live call. */
@Composable
private fun SeniorVideoViews() {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            val remote = org.webrtc.SurfaceViewRenderer(ctx)
            val local = org.webrtc.SurfaceViewRenderer(ctx)
            val container = android.widget.FrameLayout(ctx)
            container.addView(
                remote,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            val localParams = android.widget.FrameLayout.LayoutParams(240, 320).apply {
                gravity = android.view.Gravity.END or android.view.Gravity.BOTTOM
            }
            container.addView(local, localParams)
            CallCoordinator.attachViews(local, remote)
            container
        },
        modifier = Modifier.fillMaxWidth().height(360.dp),
    )
}
