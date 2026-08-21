package com.rosergenbg.logo

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Small real-time DAF engine for v1.0.
 *
 * Audio path: AudioRecord -> circular delay buffer -> AudioTrack.
 * The selected delay is additional application delay; transport latency from Bluetooth remains.
 */
class DafAudioEngine(
    private val routeManager: AudioRouteManager
) {
    companion object {
        const val SAMPLE_RATE = 48_000
        const val MAX_DELAY_MS = 250
        private const val CHUNK_FRAMES = 480 // ~10 ms at 48 kHz
    }

    private val running = AtomicBoolean(false)

    @Volatile
    var delayMs: Int = 75
        set(value) {
            field = value.coerceIn(0, MAX_DELAY_MS)
        }

    @Volatile
    var volume: Float = 0.8f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    var onUnexpectedStop: ((String) -> Unit)? = null

    private var worker: Thread? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null

    fun isRunning(): Boolean = running.get()

    @SuppressLint("MissingPermission")
    fun start(input: AudioDeviceInfo?, output: AudioDeviceInfo?): Result<Unit> {
        if (running.get()) return Result.success(Unit)

        return runCatching {
            routeManager.beginRoute(input, output)

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val recordMin = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val recordBytes = max(recordMin, CHUNK_FRAMES * 2 * 4)

            val newRecorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format)
                .setBufferSizeInBytes(recordBytes)
                .build()

            if (input != null) {
                newRecorder.setPreferredDevice(input)
            }

            val outputFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val trackMin = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val trackBytes = max(trackMin, CHUNK_FRAMES * 2 * 4)
            val communication = routeManager.isBluetoothMic(input)

            val attributes = AudioAttributes.Builder()
                .setUsage(
                    if (communication) AudioAttributes.USAGE_VOICE_COMMUNICATION
                    else AudioAttributes.USAGE_MEDIA
                )
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val newPlayer = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(outputFormat)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(trackBytes)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            if (output != null) {
                newPlayer.setPreferredDevice(output)
            }

            check(newRecorder.state == AudioRecord.STATE_INITIALIZED) {
                "Не удалось инициализировать микрофон"
            }
            check(newPlayer.state == AudioTrack.STATE_INITIALIZED) {
                "Не удалось инициализировать аудиовыход"
            }

            recorder = newRecorder
            player = newPlayer
            running.set(true)

            worker = Thread(
                { audioLoop(newRecorder, newPlayer) },
                "Logo-DAF-Audio"
            ).also { it.start() }
        }.onFailure {
            running.set(false)
            releaseAudio()
            routeManager.clearRoute()
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { recorder?.stop() }
        runCatching { worker?.join(800) }
    }

    private fun audioLoop(record: AudioRecord, track: AudioTrack) {
        var failure: Throwable? = null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            val input = ShortArray(CHUNK_FRAMES)
            val output = ShortArray(CHUNK_FRAMES)
            val maxDelaySamples = SAMPLE_RATE * MAX_DELAY_MS / 1000
            val ring = ShortArray(maxDelaySamples + CHUNK_FRAMES * 4)
            var writePos = 0

            track.setVolume(volume)
            track.play()
            record.startRecording()

            while (running.get()) {
                val read = record.read(
                    input,
                    0,
                    input.size,
                    AudioRecord.READ_BLOCKING
                )
                if (read <= 0) {
                    if (running.get()) error("Ошибка чтения микрофона: $read")
                    break
                }

                val delaySamples = (delayMs * SAMPLE_RATE / 1000)
                    .coerceIn(0, maxDelaySamples)

                for (i in 0 until read) {
                    val currentPos = writePos
                    ring[currentPos] = input[i]

                    output[i] = if (delaySamples == 0) {
                        input[i]
                    } else {
                        var readPos = currentPos - delaySamples
                        if (readPos < 0) readPos += ring.size
                        ring[readPos]
                    }

                    writePos++
                    if (writePos >= ring.size) writePos = 0
                }

                track.setVolume(volume)
                val written = track.write(output, 0, read, AudioTrack.WRITE_BLOCKING)
                if (written < 0 && running.get()) {
                    error("Ошибка вывода звука: $written")
                }
            }
        } catch (t: Throwable) {
            if (running.get()) failure = t
        } finally {
            running.set(false)
            releaseAudio()
            routeManager.clearRoute()
            failure?.let {
                onUnexpectedStop?.invoke(it.message ?: "Аудиодвижок остановлен")
            }
        }
    }

    private fun releaseAudio() {
        val r = recorder
        val p = player
        recorder = null
        player = null
        worker = null

        runCatching {
            if (r?.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop()
        }
        runCatching { r?.release() }
        runCatching {
            if (p?.playState == AudioTrack.PLAYSTATE_PLAYING) p.stop()
        }
        runCatching { p?.release() }
    }
}
