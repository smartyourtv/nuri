package com.app.nuri.speech
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class EnergyVadTest {
    @Test fun silenceDoesNotOpenGate() { val vad = EnergyVad(); assertFalse(vad.acceptWindow(FloatArray(1280))) }
    @Test fun loudWindowOpensGate() { val vad = EnergyVad(); assertTrue(vad.acceptWindow(FloatArray(1280) { 0.5f })) }
}
