package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class RecordedAudioResult(
    val samples: ShortArray,
    val durationMs: Long,
    val sampleRateHz: Int,
    val peakAmplitude: Int
)

data class ProcessedAudioChunk(
    val samples: ShortArray,
    val hasSpeech: Boolean,
    val activeSpeechMs: Int,
    val speechRms: Float,
    val updatedNoiseFloor: Float
)

/**
 * Pure DSP audio cleaner & Voice Activity Detector (VAD):
 * - Applies a DC/low-frequency rumble high-pass filter (~95Hz at 16kHz).
 * - Maintains an adaptive ambient noise floor.
 * - Attenuates non-speech frames (noise gate) and rejects silent/noise-only chunks.
 */
object SpeechAudioFilter {
    private const val FRAME_SAMPLES = 320 // 20ms at 16kHz
    private const val MIN_SPEECH_RMS = 0.022f
    private const val MIN_ACTIVE_SPEECH_FRAMES = 6 // >= 120ms of speech energy
    private const val NOISE_MULTIPLIER = 2.6f

    fun processChunk(
        rawSamples: ShortArray,
        currentNoiseFloor: Float = 0.008f
    ): ProcessedAudioChunk {
        if (rawSamples.isEmpty()) {
            return ProcessedAudioChunk(rawSamples, false, 0, 0f, currentNoiseFloor)
        }

        // 1. 1st-order high-pass filter to remove DC offset, wind, & 50/60Hz hum
        val filtered = FloatArray(rawSamples.size)
        val alpha = 0.96f
        var prevX = rawSamples[0] / 32768f
        var prevY = 0f
        filtered[0] = 0f
        for (i in 1 until rawSamples.size) {
            val x = rawSamples[i] / 32768f
            val y = alpha * (prevY + x - prevX)
            filtered[i] = y
            prevX = x
            prevY = y
        }

        // 2. Compute per-20ms-frame RMS & ZCR to separate speech from stationary noise
        val frameCount = filtered.size / FRAME_SAMPLES
        if (frameCount == 0) {
            return ProcessedAudioChunk(rawSamples, false, 0, 0f, currentNoiseFloor)
        }

        val frameRms = FloatArray(frameCount)
        val frameZcr = FloatArray(frameCount)
        for (f in 0 until frameCount) {
            val start = f * FRAME_SAMPLES
            var sumSq = 0.0
            var crossings = 0
            for (k in 0 until FRAME_SAMPLES) {
                val s = filtered[start + k]
                sumSq += s * s
                if (k > 0 && ((filtered[start + k] >= 0f) != (filtered[start + k - 1] >= 0f))) {
                    crossings++
                }
            }
            frameRms[f] = sqrt(sumSq / FRAME_SAMPLES).toFloat()
            frameZcr[f] = crossings.toFloat() / FRAME_SAMPLES
        }

        // Estimate ambient floor from the quietest 25% of frames
        val sortedRms = frameRms.sorted()
        val p25Index = (sortedRms.size / 4).coerceIn(0, sortedRms.lastIndex)
        val chunkFloor = sortedRms[p25Index].coerceIn(0.002f, 0.06f)
        val adaptedFloor = (currentNoiseFloor * 0.7f + chunkFloor * 0.3f).coerceIn(0.003f, 0.05f)
        val speechThreshold = max(MIN_SPEECH_RMS, adaptedFloor * NOISE_MULTIPLIER)

        // 3. Identify active speech frames (energy above threshold & speech-like ZCR)
        val isSpeechFrame = BooleanArray(frameCount)
        var activeFrames = 0
        var activeRmsSum = 0f
        for (f in 0 until frameCount) {
            val rms = frameRms[f]
            val zcr = frameZcr[f]
            val speechLike = rms >= speechThreshold && zcr in 0.01f..0.48f
            isSpeechFrame[f] = speechLike
            if (speechLike) {
                activeFrames++
                activeRmsSum += rms
            }
        }

        // Expand speech mask by 1 hangover frame on each side so word endings aren't clipped
        val gateOpen = isSpeechFrame.copyOf()
        for (f in 0 until frameCount) {
            if (isSpeechFrame[f]) {
                if (f > 0) gateOpen[f - 1] = true
                if (f + 1 < frameCount) gateOpen[f + 1] = true
            }
        }

        val hasSpeech = activeFrames >= MIN_ACTIVE_SPEECH_FRAMES
        val meanSpeechRms = if (activeFrames > 0) activeRmsSum / activeFrames else 0f

        // 4. Apply smooth noise attenuation to non-speech frames
        val outSamples = ShortArray(rawSamples.size)
        for (f in 0 until frameCount) {
            val gain = if (gateOpen[f]) 1.0f else 0.08f
            val start = f * FRAME_SAMPLES
            for (k in 0 until FRAME_SAMPLES) {
                val scaled = (filtered[start + k] * gain * 32767f).toInt().coerceIn(-32768, 32767)
                outSamples[start + k] = scaled.toShort()
            }
        }
        val tailStart = frameCount * FRAME_SAMPLES
        for (i in tailStart until rawSamples.size) {
            val scaled = (filtered[i] * 0.08f * 32767f).toInt().coerceIn(-32768, 32767)
            outSamples[i] = scaled.toShort()
        }

        return ProcessedAudioChunk(
            samples = outSamples,
            hasSpeech = hasSpeech,
            activeSpeechMs = activeFrames * 20,
            speechRms = meanSpeechRms,
            updatedNoiseFloor = adaptedFloor
        )
    }
}

/**
 * Real Android AudioRecord capture and AudioTrack playback engine.
 * Uses VOICE_RECOGNITION source + hardware NoiseSuppressor + adaptive VAD to suppress
 * background noise and avoid hallucinated dictation during silence.
 */
class AudioRecorderEngine {

    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var captureJob: Job? = null
    private var playbackJob: Job? = null

    private val recordedBuffer = mutableListOf<Short>()
    private var recordingStartTimeMs: Long = 0L
    private var adaptiveNoiseFloor: Float = 0.008f

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
        chunkDurationMs: Long = 1800L,
        filterSilence: Boolean = false,
        onChunkReady: ((ShortArray) -> Unit)? = null,
        onSilenceFiltered: (() -> Unit)? = null,
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

        val bufferSize = maxOf(minBuf * 2, SAMPLE_RATE)
        val recorder = createBestSpeechRecorder(bufferSize, onError) ?: return

        attachAudioEffects(recorder.audioSessionId)

        synchronized(recordedBuffer) {
            recordedBuffer.clear()
        }
        audioRecord = recorder
        adaptiveNoiseFloor = 0.008f
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
                        val displayLevel = ((rms - adaptiveNoiseFloor * 0.8f) * 4.5f).coerceIn(0f, 1f)
                        _amplitudeLevel.value = displayLevel
                        _elapsedMs.value = System.currentTimeMillis() - recordingStartTimeMs

                        if (onChunkReady != null && chunkAccumulator.size >= chunkSamplesTarget) {
                            val chunkArray = chunkAccumulator.toShortArray()
                            chunkAccumulator.clear()
                            dispatchChunk(
                                chunkArray = chunkArray,
                                filterSilence = filterSilence,
                                onChunkReady = onChunkReady,
                                onSilenceFiltered = onSilenceFiltered
                            )
                        }
                    }
                }
                if (onChunkReady != null && chunkAccumulator.size >= SAMPLE_RATE / 4) {
                    dispatchChunk(
                        chunkArray = chunkAccumulator.toShortArray(),
                        filterSilence = filterSilence,
                        onChunkReady = onChunkReady,
                        onSilenceFiltered = onSilenceFiltered
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio capture error", e)
                withContext(Dispatchers.Main) {
                    onError(e.localizedMessage ?: "Audio capture error.")
                }
            } finally {
                releaseAudioEffects()
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

    private fun dispatchChunk(
        chunkArray: ShortArray,
        filterSilence: Boolean,
        onChunkReady: (ShortArray) -> Unit,
        onSilenceFiltered: (() -> Unit)?
    ) {
        val processed = SpeechAudioFilter.processChunk(chunkArray, adaptiveNoiseFloor)
        adaptiveNoiseFloor = processed.updatedNoiseFloor
        if (!filterSilence || processed.hasSpeech) {
            onChunkReady(processed.samples)
        } else {
            onSilenceFiltered?.invoke()
        }
    }

    @SuppressLint("MissingPermission")
    private fun createBestSpeechRecorder(bufferSize: Int, onError: (String) -> Unit): AudioRecord? {
        val sources = listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
        for (source in sources) {
            try {
                val rec = AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    return rec
                }
                rec.release()
            } catch (e: SecurityException) {
                onError("Microphone permission is required to record speech.")
                return null
            } catch (e: Exception) {
                Log.w(TAG, "AudioSource $source unavailable", e)
            }
        }
        onError("Microphone failed to initialize.")
        return null
    }

    private fun attachAudioEffects(audioSessionId: Int) {
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "NoiseSuppressor unavailable", e)
        }
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AcousticEchoCanceler unavailable", e)
        }
    }

    private fun releaseAudioEffects() {
        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {
        }
        noiseSuppressor = null
        try {
            echoCanceler?.release()
        } catch (_: Exception) {
        }
        echoCanceler = null
    }

    fun stopRecording(): RecordedAudioResult {
        _isRecording.value = false
        _amplitudeLevel.value = 0f
        captureJob?.cancel()
        captureJob = null
        audioRecord = null

        val rawSamples: ShortArray
        synchronized(recordedBuffer) {
            rawSamples = recordedBuffer.toShortArray()
            recordedBuffer.clear()
        }
        val cleaned = if (rawSamples.size >= SAMPLE_RATE / 4) {
            SpeechAudioFilter.processChunk(rawSamples, adaptiveNoiseFloor).samples
        } else {
            rawSamples
        }
        var peak = 0
        for (s in cleaned) {
            val a = abs(s.toInt())
            if (a > peak) peak = a
        }
        val durationMs = ((cleaned.size.toLong() * 1000L) / SAMPLE_RATE).coerceAtLeast(_elapsedMs.value)
        return RecordedAudioResult(
            samples = cleaned,
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
                    val toWrite = min(chunkSize, pcmBytes.size - offset)
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
                    val toWrite = min(chunkSize, pcmBytes.size - offset)
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
        releaseAudioEffects()
    }

    companion object {
        private const val TAG = "AudioRecorderEngine"
        const val SAMPLE_RATE = 16000
    }
}
