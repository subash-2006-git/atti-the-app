package com.subash.atti

import com.subash.atti.data.VoiceRecorder
import com.subash.atti.data.waveformBars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRecorderTest {
    @Test fun `silence is zero and full scale is one`() {
        assertEquals(0f, VoiceRecorder.loudness(0))
        assertEquals(1f, VoiceRecorder.loudness(32767), 0.001f)
    }

    @Test fun `loudness rises with amplitude`() {
        assertTrue(VoiceRecorder.loudness(300) < VoiceRecorder.loudness(3000))
        assertTrue(VoiceRecorder.loudness(3000) < VoiceRecorder.loudness(30000))
    }

    @Test fun `waveform has the requested number of bars`() {
        assertEquals(40, waveformBars(List(123) { it / 123f }, 40).size)
        assertEquals(40, waveformBars(List(5) { 0.5f }, 40).size)
    }

    @Test fun `waveform is scaled so the loudest bar is full height`() {
        val bars = waveformBars(listOf(0.1f, 0.2f, 0.1f, 0.2f), 2)
        assertEquals(listOf(1f, 1f), bars)
        assertEquals(1f, waveformBars(listOf(0.05f, 0.2f, 0.4f, 0.1f), 4).max())
    }

    @Test fun `empty or silent recordings give flat waveforms`() {
        assertEquals(List(3) { 0f }, waveformBars(emptyList(), 3))
        assertEquals(List(3) { 0f }, waveformBars(List(30) { 0f }, 3))
    }
}
