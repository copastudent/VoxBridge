package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

data class RecordedAudioResult(
    val samples: ShortArray,
    val durationMs: Long,
    val sampleRateHz: Int,
    val peakAmplitude: Int
)

/**
 * Real Android AudioRecord capture and AudioTrack playback engine.
 * Supports both full-phrase recording and streaming audio chunk emission for real-time dictation.
 */
class AudioRecorderEngine {

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var playbackJob: Job? = null

    private val recordedBuffer = mutableListOf<Short>()
    private var recordingStartTimeMs: Long = 0L

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _amplitudeLevel = MutableStateFlow(0f)
    val amplitudeLevel: StateFlow<Float> = _amplitudeLevel.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    @SuppressLint("MissingPermission")
    fun startRecording(
        scope: CoroutineScope,
        chunkDurationMs: Long = 1600L,
        onChunkReady: ((ShortArray) -> Unit)? = null,
        onError: (String) -> Unit = {}
    ) {
        if (_isRecording.value) return

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            onError("Microphone buffer initialization failed on this device.")
            return
        }

        val bufferSize = maxOf(minBuf * 2, SAMPLE_RATE) // at least ~0.5s buffer
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: SecurityException) {
            onError("Microphone permission is required to record speech.")
            return
        } catch (e: Exception) {
            onError(e.localizedMessage ?: "Unable to access microphone.")
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            onError("Microphone failed to initialize.")
            return
        }

        synchronized(recordedBuffer) {
            recordedBuffer.clear()
        }
        audioRecord = recorder
        recordingStartTimeMs = System.currentTimeMillis()
        _elapsedMs.value = 0L
        _amplitudeLevel.value = 0f
        _isRecording.value = true

        captureJob = scope.launch(Dispatchers.IO) {
            val readBuf = ShortArray(1024)
            val chunkSamplesTarget = ((SAMPLE_RATE * chunkDurationMs) / 1000L).toInt().coerceAtLeast(1600)
            val chunkAccumulator = mutableListOf<Short>()

            try {
                recorder.startRecording()
                while (isActive && _isRecording.value) {
                    val readCount = recorder.read(readBuf, 0, readBuf.size)
                    if (readCount > 0) {
                        var sumSquares = 0.0
                        synchronized(recordedBuffer) {
                            for (i in 0 until readCount) {
                                val s = readBuf[i]
                                recordedBuffer.add(s)
                                chunkAccumulator.add(s)
                                val norm = s / 32768.0
                                sumSquares += norm * norm
                            }
                        }
                        val rms = sqrt(sumSquares / readCount).toFloat()
                        _amplitudeLevel.value = (rms * 4.5f).coerceIn(0f, 1f)
                        _elapsedMs.value = System.currentTimeMillis() - recordingStartTimeMs

                        if (onChunkReady != null && chunkAccumulator.size >= chunkSamplesTarget) {
                            val chunkArray = chunkAccumulator.toShortArray()
                            chunkAccumulator.clear()
                            onChunkReady(chunkArray)
                        }
                    }
                }
                if (onChunkReady != null && chunkAccumulator.size >= SAMPLE_RATE / 4) {
                    onChunkReady(chunkAccumulator.toShortArray())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio capture error", e)
                withContext(Dispatchers.Main) {
                    onError(e.localizedMessage ?: "Audio capture error.")
                }
            } finally {
                try {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                    }
                } catch (_: Exception) {
                }
                recorder.release()
            }
        }
    }

    fun stopRecording(): RecordedAudioResult {
        _isRecording.value = false
        _amplitudeLevel.value = 0f
        captureJob?.cancel()
        captureJob = null
        audioRecord = null

        val samples: ShortArray
        synchronized(recordedBuffer) {
            samples = recordedBuffer.toShortArray()
            recordedBuffer.clear()
        }
        var peak = 0
        for (s in samples) {
            val a = abs(s.toInt())
            if (a > peak) peak = a
        }
        val durationMs = ((samples.size.toLong() * 1000L) / SAMPLE_RATE).coerceAtLeast(_elapsedMs.value)
        return RecordedAudioResult(
            samples = samples,
            durationMs = durationMs,
            sampleRateHz = SAMPLE_RATE,
            peakAmplitude = peak
        )
    }

    fun playWavFile(scope: CoroutineScope, filePath: String, onFinished: () -> Unit = {}) {
        stopPlayback()
        val file = File(filePath)
        if (!file.exists()) {
            onFinished()
            return
        }
        playbackJob = scope.launch(Dispatchers.IO) {
            _isPlaying.value = true
            var track: AudioTrack? = null
            try {
                val allBytes = file.readBytes()
                if (allBytes.size <= 44) return@launch
                val pcmBytes = allBytes.copyOfRange(44, allBytes.size)
                val minBuffer = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuffer, 4096))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                track.play()
                var offset = 0
                val chunkSize = 4096
                while (isActive && offset < pcmBytes.size) {
                    val toWrite = minOf(chunkSize, pcmBytes.size - offset)
                    track.write(pcmBytes, offset, toWrite)
                    offset += toWrite
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio playback error", e)
            } finally {
                try {
                    track?.stop()
                    track?.release()
                } catch (_: Exception) {
                }
                _isPlaying.value = false
                withContext(Dispatchers.Main) {
                    onFinished()
                }
            }
        }
    }

    fun playPcmBytes(scope: CoroutineScope, pcmBytes: ByteArray, sampleRateHz: Int = 24000, onFinished: () -> Unit = {}) {
        stopPlayback()
        if (pcmBytes.isEmpty()) {
            onFinished()
            return
        }
        playbackJob = scope.launch(Dispatchers.IO) {
            _isPlaying.value = true
            var track: AudioTrack? = null
            try {
                val minBuffer = AudioTrack.getMinBufferSize(
                    sampleRateHz,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRateHz)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuffer, 4096))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                track.play()
                var offset = 0
                val chunkSize = 4096
                while (isActive && offset < pcmBytes.size) {
                    val toWrite = minOf(chunkSize, pcmBytes.size - offset)
                    track.write(pcmBytes, offset, toWrite)
                    offset += toWrite
                }
            } catch (e: Exception) {
                Log.e(TAG, "PCM playback error", e)
            } finally {
                try {
                    track?.stop()
                    track?.release()
                } catch (_: Exception) {
                }
                _isPlaying.value = false
                withContext(Dispatchers.Main) {
                    onFinished()
                }
            }
        }
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        _isPlaying.value = false
    }

    fun release() {
        if (_isRecording.value) {
            stopRecording()
        }
        stopPlayback()
    }

    companion object {
        private const val TAG = "AudioRecorderEngine"
        const val SAMPLE_RATE = 16000
    }
}
