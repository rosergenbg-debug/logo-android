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
 * Acoustic end-to-end latency measurement.
 *
 * The earbud is held very close to the phone microphone. Logo plays a distinctive four-chirp
 * pattern through the selected output and records the same pattern with the selected microphone.
 * The measured delay therefore includes Android buffering + Bluetooth transport + the earbud.
 *
 * v1.2 deliberately uses a STREAM/STEREO AudioTrack. Some ColorOS/OnePlus builds refuse the
 * previous large MODE_STATIC mono track on an A2DP route even though normal media playback works.
 */
class CalibrationEngine(
    private val routeManager: AudioRouteManager
) {
    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 2
        private const val CAPTURE_MS = 4_800
        private const val PRELOAD_MS = 220
        private const val BURST_MS = 32
        private const val SEARCH_MIN_MS = 15
        private const val SEARCH_MAX_MS = 850
        private const val ENERGY_WINDOW_MS = 28
        private val CLICK_TIMES_MS = intArrayOf(520, 1_150, 1_920, 2_780)
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
        ).coerceAtLeast(SAMPLE_RATE / 10)

        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(inputFormat)
            .setBufferSizeInBytes(max(recordMin, SAMPLE_RATE))
            .build()

        if (input != null) runCatching { recorder.setPreferredDevice(input) }
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "Не удалось открыть микрофон для калибровки"
        }

        val signal = buildCalibrationSignalStereo()
        val outputFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()

        val minOut = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(0)
        val bytesPerSecond = SAMPLE_RATE * CHANNELS * 2
        val streamBufferBytes = max(minOut, bytesPerSecond * 420 / 1000)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(outputFormat)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(streamBufferBytes)
            .build()

        check(track.state == AudioTrack.STATE_INITIALIZED) {
            "Телефон не смог открыть тестовый звук. Оставьте выбранными Sony и повторите"
        }
        if (output != null) runCatching { track.setPreferredDevice(output) }
        track.setVolume(0.92f)

        val captureSamples = SAMPLE_RATE * CAPTURE_MS / 1000
        val captured = ShortArray(captureSamples)
        var capturedCount = 0
        var captureFailure: Throwable? = null
        var writerFailure: Throwable? = null

        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Микрофон не начал запись для калибровки"
            }
            val recordStartedNanos = System.nanoTime()

            val captureThread = Thread({
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                    val chunk = ShortArray(960)
                    while (capturedCount < captured.size &&
                        recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
                    ) {
                        val read = recorder.read(
                            chunk,
                            0,
                            minOf(chunk.size, captured.size - capturedCount),
                            AudioRecord.READ_BLOCKING
                        )
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

            // Preload only silence, so MODE_STREAM has enough data before play() without blocking.
            val preloadFrames = SAMPLE_RATE * PRELOAD_MS / 1000
            val preloadShorts = minOf(preloadFrames * CHANNELS, signal.size)
            val prewritten = track.write(signal, 0, preloadShorts, AudioTrack.WRITE_BLOCKING)
            check(prewritten == preloadShorts) {
                "Не удалось подготовить тестовый звук"
            }

            val playCalledNanos = System.nanoTime()
            track.play()
            check(track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                "Тестовый звук не запустился"
            }

            val writerThread = Thread({
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                    var offset = preloadShorts
                    while (offset < signal.size) {
                        val count = minOf(4096, signal.size - offset)
                        val written = track.write(signal, offset, count, AudioTrack.WRITE_BLOCKING)
                        if (written > 0) {
                            offset += written
                        } else if (written < 0) {
                            error("Ошибка вывода тестового звука: $written")
                        }
                    }
                } catch (t: Throwable) {
                    writerFailure = t
                }
            }, "Logo-Calibration-Playback").also { it.start() }

            writerThread.join(5_500)
            writerFailure?.let { throw it }
            if (writerThread.isAlive) error("Тестовый звук воспроизводится слишком долго")

            // Let the last chirp travel through Bluetooth and reach the phone microphone.
            Thread.sleep(900)
            runCatching { recorder.stop() }
            captureThread.join(1_000)

            captureFailure?.let { throw it }
            check(capturedCount >= SAMPLE_RATE * 3) {
                "Слишком мало данных с микрофона"
            }

            val playOffsetSamples = (
                ((playCalledNanos - recordStartedNanos) / 1_000_000_000.0) * SAMPLE_RATE
                ).toInt()

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

    private fun buildCalibrationSignalStereo(): ShortArray {
        val totalMs = CLICK_TIMES_MS.last() + 500
        val frames = SAMPLE_RATE * totalMs / 1000
        val signal = ShortArray(frames * CHANNELS)
        val burstFrames = SAMPLE_RATE * BURST_MS / 1000

        CLICK_TIMES_MS.forEachIndexed { burstIndex, clickMs ->
            val startFrame = SAMPLE_RATE * clickMs / 1000
            for (i in 0 until burstFrames) {
                // A short chirp survives Bluetooth encoding and phone voice processing better
                // than the old 12 ms single-frequency click.
                val p = i.toDouble() / burstFrames.coerceAtLeast(1)
                val frequency = 1_900.0 + (2_400.0 * p) + burstIndex * 70.0
                val envelope = sin(PI * p).coerceAtLeast(0.0)
                val carrier = sin(2.0 * PI * frequency * i / SAMPLE_RATE)
                val sample = (carrier * envelope * 28_000.0).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
                val frame = startFrame + i
                val base = frame * CHANNELS
                signal[base] = sample
                signal[base + 1] = sample
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
                val from = (center - windowSamples / 4).coerceAtLeast(0)
                val to = minOf(from + windowSamples, count)
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

        val positive = scores.filter { it > 0f }.sorted()
        val baseline = if (positive.isEmpty()) 1f else positive[positive.size / 2].coerceAtLeast(1f)
        val confidence = bestScore / baseline
        check(confidence >= 1.28f) {
            "Щелчки слышны слишком слабо. Поднимите громкость мультимедиа и прижмите наушник ближе к нижнему микрофону"
        }

        return CalibrationResult(bestLatency, confidence)
    }
}
