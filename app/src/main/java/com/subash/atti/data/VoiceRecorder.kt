package com.subash.atti.data

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import kotlin.math.log10

/**
 * Records one voice message at a time as small AAC audio (about 4 KB a second)
 * and samples its loudness so the message can show a waveform.
 */
class VoiceRecorder(private val context: Context) {

    data class Recording(val file: File, val durationMs: Long, val waveform: List<Float>)

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private val levels = mutableListOf<Float>()

    val isRecording: Boolean get() = recorder != null

    /** Returns false if the microphone couldn't be opened (in use by a call, …). */
    fun start(): Boolean {
        cancel()
        val out = File(context.cacheDir, "recordings/rec-${System.nanoTime()}.m4a").apply { parentFile?.mkdirs() }
        val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        return try {
            newRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioEncodingBitRate(BIT_RATE)
                setOutputFile(out.absolutePath)
                prepare()
                start()
            }
            recorder = newRecorder
            file = out
            startedAt = SystemClock.elapsedRealtime()
            levels.clear()
            true
        } catch (e: Exception) {
            Log.w(TAG, "couldn't start recording", e)
            newRecorder.release()
            out.delete()
            false
        }
    }

    val elapsedMs: Long get() = if (isRecording) SystemClock.elapsedRealtime() - startedAt else 0L

    /** Current loudness 0–1; call it about ten times a second while recording. */
    fun sampleLevel(): Float {
        val amplitude = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        return loudness(amplitude).also { if (isRecording) levels += it }
    }

    /** Stops and returns the recording, or null if it was too short or failed. */
    fun stop(): Recording? {
        val active = recorder ?: return null
        val out = file
        val duration = elapsedMs
        val stopped = runCatching { active.stop() }.isSuccess
        active.release()
        recorder = null
        file = null
        if (!stopped || out == null || duration < MIN_DURATION_MS || out.length() == 0L) {
            out?.delete()
            return null
        }
        return Recording(out, duration, waveformBars(levels.toList(), WAVE_BARS))
    }

    fun cancel() {
        recorder?.let { active ->
            runCatching { active.stop() }
            active.release()
        }
        recorder = null
        file?.delete()
        file = null
    }

    companion object {
        private const val TAG = "VoiceRecorder"
        private const val SAMPLE_RATE = 44_100
        private const val BIT_RATE = 32_000

        const val MAX_DURATION_MS = 120_000L
        const val MIN_DURATION_MS = 700L
        const val WAVE_BARS = 40

        /** Maps MediaRecorder's 0–32767 amplitude to 0–1 on a decibel scale, as ears hear it. */
        fun loudness(amplitude: Int): Float {
            if (amplitude <= 0) return 0f
            val db = 20 * log10(amplitude / 32767f)
            return ((db + 50f) / 50f).coerceIn(0f, 1f)
        }
    }
}

/**
 * Squeezes loudness samples into [bars] bars (each the peak of its slice), scaled
 * so the loudest bar is full height and quiet recordings still show their shape.
 */
fun waveformBars(samples: List<Float>, bars: Int): List<Float> {
    if (samples.isEmpty() || bars <= 0) return List(bars.coerceAtLeast(0)) { 0f }
    val peaks = List(bars) { bar ->
        val from = bar * samples.size / bars
        val to = maxOf(from + 1, (bar + 1) * samples.size / bars).coerceAtMost(samples.size)
        if (from >= samples.size) 0f else samples.subList(from, to).max()
    }
    val loudest = peaks.max()
    return if (loudest <= 0f) peaks else peaks.map { it / loudest }
}
