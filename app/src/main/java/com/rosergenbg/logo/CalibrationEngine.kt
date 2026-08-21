package com.rosergenbg.logo

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/** Result of the acoustic loopback calibration. */
data class CalibrationResult(
    val latencyMs: Int,
    val confidence: Float
)

/**
 * Measures the practical microphone -> Android -> selected output path acoustically.
 *
 * The user places one earbud/speaker close to the selected microphone. The app emits
 * four short tone-clicks and searches for the same timing pattern in the captured audio.
 * This intentionally measures the whole real path, including Bluetooth transport.
 */
class CalibrationEngine(
    private val routeManager: AudioRouteManager
) {
    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val CAPTURE_MS = 3_800
        private const val START_LEAD_MS = 180
        private const val BURST_MS = 12
        private const val SEARCH_MIN_MS = 10
        private const val SEARCH_MAX_MS = 700
        private const val ENERGY_WINDOW_MS = 10
        private val CLICK_TIMES_MS = intArrayOf(300, 950, 1_600, 2_250)
    }

    @SuppressLint("MissingPermission")
    fun measure(input: AudioDeviceInfo?, output: AudioDeviceInfo?): Result<CalibrationResult> = runCatching {
        routeManager.beginRoute(input, output)

        val inputFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val recordMin = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(inputFormat)
            .setBufferSizeInBytes(max(recordMin, SAMPLE_RATE / 2))
            .build()

        if (input != null) recorder.setPreferredDevice(input)
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "Не удалось открыть микрофон для калибровки"
        }

        val signal = buildCalibrationSignal()
        val outputFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(outputFormat)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(signal.size * 2)
            .build()

        if (output != null) track.setPreferredDevice(output)
        check(track.state == AudioTrack.STATE_INITIALIZED) {
            "Не удалось открыть выбранный аудиовыход"
        }

        val written = track.write(signal, 0, signal.size, AudioTrack.WRITE_BLOCKING)
        check(written == signal.size) { "Не удалось подготовить тестовый сигнал" }
        track.setVolume(0.72f)

        val captureSamples = SAMPLE_RATE * CAPTURE_MS / 1000
        val captured = ShortArray(captureSamples)
        var capturedCount = 0
        var captureFailure: Throwable? = null

        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            recorder.startRecording()
            val recordStartedNanos = System.nanoTime()

            val captureThread = Thread({
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                    val chunk = ShortArray(960)
                    while (capturedCount < captured.size && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val read = recorder.read(chunk, 0, minOf(chunk.size, captured.size - capturedCount), AudioRecord.READ_BLOCKING)
                        if (read > 0) {
                            System.arraycopy(chunk, 0, captured, capturedCount, read)
                            capturedCount += read
                        } else if (read < 0) {
                            error("Ошибка микрофона при калибровке: $read")
                        }
                    }
                } catch (t: Throwable) {
                    captureFailure = t
                }
            }, "Logo-Calibration-Capture").also { it.start() }

            Thread.sleep(START_LEAD_MS.toLong())
            val playCalledNanos = System.nanoTime()
            track.play()

            captureThread.join(CAPTURE_MS.toLong() + 1_500L)
            if (captureThread.isAlive) {
                runCatching { recorder.stop() }
                captureThread.join(700)
            }

            captureFailure?.let { throw it }
            check(capturedCount >= SAMPLE_RATE * 3) {
                "Слишком мало данных с микрофона"
            }

            val playOffsetSamples = (((playCalledNanos - recordStartedNanos) / 1_000_000_000.0) * SAMPLE_RATE).toInt()
            analyse(captured, capturedCount, playOffsetSamples)
        } finally {
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            }
            runCatching { recorder.release() }
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            }
            runCatching { track.release() }
            routeManager.clearRoute()
        }
    }

    private fun buildCalibrationSignal(): ShortArray {
        val totalMs = CLICK_TIMES_MS.last() + 350
        val signal = ShortArray(SAMPLE_RATE * totalMs / 1000)
        val burstSamples = SAMPLE_RATE * BURST_MS / 1000

        CLICK_TIMES_MS.forEach { clickMs ->
            val start = SAMPLE_RATE * clickMs / 1000
            for (i in 0 until burstSamples) {
                val envelope = 1.0 - i.toDouble() / burstSamples
                val carrier = sin(2.0 * PI * 3_200.0 * i / SAMPLE_RATE)
                signal[start + i] = (carrier * envelope * 25_000.0).toInt().toShort()
            }
        }
        return signal
    }

    private fun analyse(captured: ShortArray, count: Int, playOffsetSamples: Int): CalibrationResult {
        val windowSamples = SAMPLE_RATE * ENERGY_WINDOW_MS / 1000
        val scores = FloatArray(SEARCH_MAX_MS - SEARCH_MIN_MS + 1)
        var bestLatency = -1
        var bestScore = 0f

        for (latencyMs in SEARCH_MIN_MS..SEARCH_MAX_MS) {
            val latencySamples = SAMPLE_RATE * latencyMs / 1000
            var total = 0.0
            var valid = 0

            for (clickMs in CLICK_TIMES_MS) {
                val center = playOffsetSamples + SAMPLE_RATE * clickMs / 1000 + latencySamples
                val from = center.coerceAtLeast(0)
                val to = minOf(center + windowSamples, count)
                if (to <= from) continue

                var energy = 0.0
                for (i in from until to) energy += abs(captured[i].toInt()).toDouble()
                total += energy / (to - from)
                valid++
            }

            val score = if (valid == CLICK_TIMES_MS.size) (total / valid).toFloat() else 0f
            scores[latencyMs - SEARCH_MIN_MS] = score
            if (score > bestScore) {
                bestScore = score
                bestLatency = latencyMs
            }
        }

        check(bestLatency >= 0 && bestScore > 0f) {
            "Тестовые сигналы не обнаружены"
        }

        val sorted = scores.filter { it > 0f }.sorted()
        val baseline = if (sorted.isEmpty()) 1f else sorted[sorted.size / 2].coerceAtLeast(1f)
        val confidence = bestScore / baseline
        check(confidence >= 1.55f) {
            "Сигнал слишком слабый. Приложите наушник ближе к микрофону и повторите"
        }

        return CalibrationResult(bestLatency, confidence)
    }
}
