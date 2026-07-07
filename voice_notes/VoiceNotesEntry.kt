package com.example.voicenotes.logic

import com.xgglass.appcontract.AIApiSettings
import com.xgglass.appcontract.UniversalAppContext
import com.xgglass.appcontract.UniversalAppEntrySimple
import com.xgglass.appcontract.UniversalCommand
import com.xgglass.appcontract.UserSettingField
import com.xgglass.appcontract.UserSettingInputType
import com.xgglass.core.AudioCaptureHint
import com.xgglass.core.AudioChunk
import com.xgglass.core.AudioEncoding
import com.xgglass.core.AudioFormat
import com.xgglass.core.DisplayOptions
import com.xgglass.core.MicrophoneOptions
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class VoiceNotesEntry : UniversalAppEntrySimple {
    override val id: String = "voice_notes_demo"
    override val displayName: String = "Voice Notes Demo"

    override fun userSettings(): List<UserSettingField> =
        AIApiSettings.fields(
            defaultBaseUrl = "https://api.openai.com/v1/",
            defaultModel = "whisper-1",
        ) + UserSettingField(
            key = KEY_RECORD_SECONDS,
            label = "Record Seconds",
            hint = "e.g. 3",
            defaultValue = "3",
            inputType = UserSettingInputType.NUMBER,
        )

    override fun commands(): List<UniversalCommand> = listOf(RecordCommand())
}

private class RecordCommand : UniversalCommand {
    override val id: String = "record_voice_note"
    override val title: String = "Record 3s"

    override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
        val client = ctx.client
        val caps = client.capabilities
        val seconds = parseSeconds(ctx.settings[KEY_RECORD_SECONDS])

        ctx.log(
            "VoiceNotes: start model=${client.model}, seconds=$seconds, " +
                "mic=${caps.canRecordAudio}, display=${caps.canDisplayText}"
        )

        if (!caps.canRecordAudio) {
            val message = "VoiceNotes: selected device cannot record audio; no note captured."
            ctx.log(message)
            if (caps.canDisplayText) {
                ctx.client.display(message, DisplayOptions()).getOrElse { ctx.log("VoiceNotes: display failed: ${it.message}") }
            }
            return Result.success(Unit)
        }

        val session = client.startMicrophone(
            MicrophoneOptions(audioHint = AudioCaptureHint.VOICE_ASSISTANT)
        ).getOrElse { error ->
            ctx.log("VoiceNotes: microphone failed to start: ${error.message ?: error::class.simpleName}")
            return Result.failure(error)
        }

        val chunks = mutableListOf<AudioChunk>()
        var sawEndOfStream = false
        try {
            ctx.log(
                "VoiceNotes: recording ${seconds}s, format=" +
                    formatLabel(session.format)
            )
            withTimeoutOrNull(seconds * 1_000L) {
                session.audio
                    .takeWhile { chunk ->
                        if (chunk.endOfStream) {
                            sawEndOfStream = true
                            false
                        } else {
                            true
                        }
                    }
                    .collect { chunk ->
                        currentCoroutineContext().ensureActive()
                        chunks += chunk
                    }
            }
        } catch (error: Throwable) {
            try {
                session.stop()
            } catch (stopError: Throwable) {
                error.addSuppressed(stopError)
            }
            return Result.failure(error)
        } finally {
            try {
                session.stop()
            } catch (stopError: Throwable) {
                ctx.log("VoiceNotes: microphone stop failed: ${stopError.message ?: stopError::class.simpleName}")
            }
        }

        val audioBytes = chunks.combineAudioBytes()
        val summary = "VoiceNotes: ${chunks.size} chunks, ${audioBytes.size} bytes, " +
            "duration=${seconds}s, format=${formatLabel(session.format)}, endOfStream=$sawEndOfStream"
        ctx.log(summary)

        val transcript = maybeTranscribe(ctx, audioBytes, session.format).getOrElse { error ->
            ctx.log("VoiceNotes: transcription failed: ${error.message ?: error::class.simpleName}")
            null
        }

        val displayText = if (transcript.isNullOrBlank()) {
            "$summary\nNo transcription configured; summary logged."
        } else {
            "Transcript:\n$transcript"
        }
        if (caps.canDisplayText) {
            ctx.client.display(displayText, DisplayOptions()).getOrElse { error ->
                ctx.log("VoiceNotes: display failed: ${error.message ?: error::class.simpleName}")
            }
        }

        return Result.success(Unit)
    }

    private suspend fun maybeTranscribe(
        ctx: UniversalAppContext,
        audioBytes: ByteArray,
        format: AudioFormat,
    ): Result<String?> {
        val config = TranscriptionConfig.from(ctx.settings)
        if (!config.isConfigured) {
            ctx.log("VoiceNotes: AI API settings are not configured; skipping transcription.")
            return Result.success(null)
        }
        if (audioBytes.isEmpty()) {
            ctx.log("VoiceNotes: no audio bytes captured; skipping transcription.")
            return Result.success(null)
        }

        // LC3 devices such as Even G1 stream compressed LC3 frames. Decode compressed frames
        // to PCM before sending them to PCM-only transcription APIs.
        if (format.encoding != AudioEncoding.PCM_S16_LE && format.encoding != AudioEncoding.PCM_S8) {
            ctx.log("VoiceNotes: ${format.encoding} is compressed or not WAV-ready; decode before ASR.")
            return Result.success(null)
        }

        return runCatching {
            transcribeOpenAiCompatible(config, audioBytes, format)
        }
    }

    private suspend fun transcribeOpenAiCompatible(
        config: TranscriptionConfig,
        audioBytes: ByteArray,
        format: AudioFormat,
    ): String = withContext(Dispatchers.IO) {
        val wavBytes = encodeWav(audioBytes, format)
        val boundary = "XgGlassVoiceNotes${System.currentTimeMillis()}"
        val endpoint = "${config.baseUrl.trimEnd('/')}/audio/transcriptions"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        DataOutputStream(connection.outputStream).use { out ->
            out.writeTextPart(boundary, "model", config.model)
            out.writeTextPart(boundary, "response_format", "text")
            out.writeFilePart(boundary, "file", "voice-note.wav", "audio/wav", wavBytes)
            out.writeBytes("--$boundary--\r\n")
        }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) {
            error("transcription HTTP $code: ${body.take(300)}")
        }
        body.trim()
    }

    private fun parseSeconds(raw: String?): Int = raw?.toIntOrNull()?.coerceIn(1, 30) ?: 3
}

private data class TranscriptionConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
) {
    val isConfigured: Boolean = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    companion object {
        fun from(settings: Map<String, String>): TranscriptionConfig =
            TranscriptionConfig(
                baseUrl = AIApiSettings.baseUrl(settings),
                model = AIApiSettings.model(settings),
                apiKey = AIApiSettings.apiKey(settings),
            )
    }
}

private fun List<AudioChunk>.combineAudioBytes(): ByteArray {
    val out = ByteArrayOutputStream(sumOf { it.bytes.size })
    forEach { out.write(it.bytes) }
    return out.toByteArray()
}

private fun formatLabel(format: AudioFormat): String =
    "${format.encoding}/${format.sampleRateHz ?: "?"}Hz/${format.channelCount ?: "?"}ch"

private fun encodeWav(bytes: ByteArray, format: AudioFormat): ByteArray {
    val sampleRate = format.sampleRateHz ?: 16_000
    val channels = format.channelCount ?: 1
    val bitsPerSample = when (format.encoding) {
        AudioEncoding.PCM_S16_LE -> 16
        AudioEncoding.PCM_S8 -> 8
        AudioEncoding.OPUS, AudioEncoding.LC3 -> error("Cannot write ${format.encoding} as PCM WAV")
    }
    val pcmBytes = if (format.encoding == AudioEncoding.PCM_S8) {
        ByteArray(bytes.size) { index -> (bytes[index].toInt() + 128).toByte() }
    } else {
        bytes
    }
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8

    return ByteArrayOutputStream(44 + pcmBytes.size).use { out ->
        out.writeAscii("RIFF")
        out.writeIntLe(36 + pcmBytes.size)
        out.writeAscii("WAVE")
        out.writeAscii("fmt ")
        out.writeIntLe(16)
        out.writeShortLe(1)
        out.writeShortLe(channels)
        out.writeIntLe(sampleRate)
        out.writeIntLe(byteRate)
        out.writeShortLe(blockAlign)
        out.writeShortLe(bitsPerSample)
        out.writeAscii("data")
        out.writeIntLe(pcmBytes.size)
        out.write(pcmBytes)
        out.toByteArray()
    }
}

private fun DataOutputStream.writeTextPart(boundary: String, name: String, value: String) {
    writeBytes("--$boundary\r\n")
    writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
    writeBytes(value)
    writeBytes("\r\n")
}

private fun DataOutputStream.writeFilePart(
    boundary: String,
    name: String,
    fileName: String,
    contentType: String,
    bytes: ByteArray,
) {
    writeBytes("--$boundary\r\n")
    writeBytes("Content-Disposition: form-data; name=\"$name\"; filename=\"$fileName\"\r\n")
    writeBytes("Content-Type: $contentType\r\n\r\n")
    write(bytes)
    writeBytes("\r\n")
}

private fun ByteArrayOutputStream.writeAscii(value: String) {
    write(value.toByteArray(Charsets.US_ASCII))
}

private fun ByteArrayOutputStream.writeIntLe(value: Int) {
    write(value and 0xff)
    write((value shr 8) and 0xff)
    write((value shr 16) and 0xff)
    write((value shr 24) and 0xff)
}

private fun ByteArrayOutputStream.writeShortLe(value: Int) {
    write(value and 0xff)
    write((value shr 8) and 0xff)
}

private const val KEY_RECORD_SECONDS = "record_seconds"
