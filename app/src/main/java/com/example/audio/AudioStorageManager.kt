package com.example.audio

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Controlled local audio storage instance for recorded speech samples.
 * Stores standard 16kHz 16-bit mono PCM WAV files scoped per authenticated user ID.
 */
class AudioStorageManager(private val context: Context) {

    fun getUserRecordingsDir(userId: String): File {
        val safeUid = userId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        val dir = File(context.filesDir, "voice_recordings/$safeUid")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun saveWavRecording(
        userId: String,
        recordingId: String,
        pcmSamples: ShortArray,
        sampleRateHz: Int = 16000
    ): File {
        val dir = getUserRecordingsDir(userId)
        val outFile = File(dir, "$recordingId.wav")
        val wavBytes = encodePcmToWav(pcmSamples, sampleRateHz)
        FileOutputStream(outFile).use { fos ->
            fos.write(wavBytes)
        }
        return outFile
    }

    fun readAudioBytes(filePath: String, maxBytes: Int = 18 * 1024 * 1024): ByteArray? {
        val file = File(filePath)
        if (!file.exists() || !file.isFile) return null
        val bytes = file.readBytes()
        return if (bytes.size <= maxBytes) bytes else bytes.copyOf(maxBytes)
    }

    fun deleteRecordingFile(filePath: String): Boolean {
        if (filePath.isBlank()) return false
        val file = File(filePath)
        return if (file.exists()) file.delete() else false
    }

    fun getStorageStats(userId: String): StorageStats {
        val dir = getUserRecordingsDir(userId)
        val files = dir.listFiles()?.filter { it.isFile && it.extension == "wav" }.orEmpty()
        val totalBytes = files.sumOf { it.length() }
        return StorageStats(
            fileCount = files.size,
            totalBytes = totalBytes,
            storagePath = dir.absolutePath
        )
    }

    companion object {
        fun encodePcmToWav(
            pcmSamples: ShortArray,
            sampleRateHz: Int = 16000,
            channels: Int = 1,
            bitsPerSample: Int = 16
        ): ByteArray {
            val dataSize = pcmSamples.size * 2
            val totalDataLen = dataSize + 36
            val byteRate = sampleRateHz * channels * bitsPerSample / 8
            val blockAlign = (channels * bitsPerSample / 8).toShort()

            val baos = ByteArrayOutputStream(44 + dataSize)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(totalDataLen)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16) // PCM subchunk size
            header.putShort(1) // PCM format
            header.putShort(channels.toShort())
            header.putInt(sampleRateHz)
            header.putInt(byteRate)
            header.putShort(blockAlign)
            header.putShort(bitsPerSample.toShort())
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataSize)
            baos.write(header.array())

            val pcmBuffer = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in pcmSamples) {
                pcmBuffer.putShort(sample)
            }
            baos.write(pcmBuffer.array())
            return baos.toByteArray()
        }
    }
}

data class StorageStats(
    val fileCount: Int,
    val totalBytes: Long,
    val storagePath: String
)
