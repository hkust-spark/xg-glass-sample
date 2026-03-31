package com.example.indoornav.logic

import android.util.Log
import com.aallam.openai.api.audio.TranscriptionRequest
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ImagePart
import com.aallam.openai.api.chat.TextPart
import com.aallam.openai.api.file.FileSource
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIHost
import com.universalglasses.appcontract.AIApiSettings
import com.universalglasses.appcontract.UniversalAppContext
import com.universalglasses.appcontract.UniversalAppEntrySimple
import com.universalglasses.appcontract.UniversalCommand
import com.universalglasses.appcontract.UserSettingField
import com.universalglasses.appcontract.UserSettingInputType
import com.universalglasses.core.AudioEncoding
import com.universalglasses.core.AudioSource
import com.universalglasses.core.DisplayOptions
import com.universalglasses.core.MicrophoneOptions
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.asSource
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Indoor Wayfinding — AR glasses navigation assistant for large hospitals
 * and shopping centers.
 *
 * Uses the glasses camera to capture visual cues (signs, directories, store
 * fronts, floor markers) and AI vision to identify the user's location,
 * provide step-by-step navigation, find nearby facilities, and read signs.
 *
 * Commands:
 * 1. Where Am I   — capture photo → AI identifies current location
 * 2. Navigate To  — voice destination + photo → AI gives step-by-step directions
 * 3. Find Nearest — voice what to find + photo → AI locates nearest facility
 * 4. Read Signs   — capture photo → AI reads and interprets all visible signage
 */
class IndoorNavEntry : UniversalAppEntrySimple {
    override val id: String = "indoor_wayfinding"
    override val displayName: String = "Indoor Wayfinding"

    override fun userSettings(): List<UserSettingField> = AIApiSettings.fields(
        defaultBaseUrl = "https://api.openai.com/v1/",
        defaultModel = "gpt-4o",
    ) + listOf(
        UserSettingField(
            key = KEY_VENUE_INFO,
            label = "Venue Name / Description",
            hint = "e.g. Beijing Chaoyang Hospital Building A, or Wanda Plaza",
            inputType = UserSettingInputType.TEXT,
        ),
        UserSettingField(
            key = KEY_FLOOR_HINT,
            label = "Current Floor",
            hint = "e.g. 3F, B1",
            inputType = UserSettingInputType.TEXT,
        ),
    )

    override fun commands(): List<UniversalCommand> = listOf(
        WhereAmICommand(),
        NavigateToCommand(),
        FindNearestCommand(),
        ReadSignsCommand(),
    )

    companion object {
        private const val TAG = "IndoorNav"
        const val KEY_VENUE_INFO = "venue_info"
        const val KEY_FLOOR_HINT = "floor_hint"
    }
}

// ---------------------------------------------------------------------------
// Shared navigation state (persists across commands within one session)
// ---------------------------------------------------------------------------

private object NavState {
    /** Accumulated context about the venue built from previous photo analyses. */
    @Volatile
    var venueContext: String = ""

    /** Breadcrumb trail of identified locations. */
    val locationHistory = mutableListOf<String>()

    fun addLocation(location: String) {
        locationHistory.add(location)
        if (locationHistory.size > 10) locationHistory.removeAt(0)
    }

    fun historyString(): String {
        if (locationHistory.isEmpty()) return ""
        return "Recent locations visited: ${locationHistory.joinToString(" → ")}"
    }
}

// ---------------------------------------------------------------------------
// Shared AI helper
// ---------------------------------------------------------------------------

private class AIHelper(private val ctx: UniversalAppContext) {
    val openAI: OpenAI
    val model: String

    init {
        val baseUrl = AIApiSettings.baseUrl(ctx.settings)
        val apiKey = AIApiSettings.apiKey(ctx.settings)
        model = AIApiSettings.model(ctx.settings)

        require(baseUrl.isNotBlank()) { "API Base URL is not configured." }
        require(apiKey.isNotBlank()) { "API Key is not configured." }
        require(model.isNotBlank()) { "Model is not configured." }

        openAI = OpenAI(
            token = apiKey,
            host = OpenAIHost(baseUrl = baseUrl),
        )
    }

    fun buildSystemPrompt(task: String): String {
        val venue = ctx.settings[IndoorNavEntry.KEY_VENUE_INFO].orEmpty()
        val floor = ctx.settings[IndoorNavEntry.KEY_FLOOR_HINT].orEmpty()
        return buildString {
            appendLine("You are an indoor navigation assistant displayed on AR smart glasses.")
            appendLine("Your job: $task")
            appendLine()
            if (venue.isNotBlank()) appendLine("Venue: $venue")
            if (floor.isNotBlank()) appendLine("Current floor hint: $floor")
            if (NavState.venueContext.isNotBlank()) {
                appendLine("Previously observed context: ${NavState.venueContext}")
            }
            val history = NavState.historyString()
            if (history.isNotBlank()) appendLine(history)
            appendLine()
            appendLine("Rules:")
            appendLine("- Be concise — the user reads on a small AR display.")
            appendLine("- Use directional language: left, right, straight ahead, behind you.")
            appendLine("- Reference visible landmarks the user can see (signs, store names, colors).")
            appendLine("- Number your steps if giving multi-step directions (max 4 steps).")
            appendLine("- If you cannot determine location from the photo, say so clearly.")
            appendLine("- Respond in the same language as the venue name or signs visible in the image. Default to Chinese if the venue is in China.")
        }
    }

    suspend fun captureAndEncode(): Pair<ByteArray, String>? {
        val img = ctx.client.capturePhoto().getOrElse { error ->
            ctx.client.display("Camera error: ${error.message}", DisplayOptions())
            return null
        }
        ctx.onCapturedImage?.invoke(img)
        val b64 = Base64.getEncoder().encodeToString(img.jpegBytes)
        return img.jpegBytes to b64
    }

    suspend fun visionChat(systemPrompt: String, userText: String, imageBase64: String): String {
        val response = openAI.chatCompletion(
            ChatCompletionRequest(
                model = ModelId(model),
                messages = listOf(
                    ChatMessage(role = ChatRole.System, content = systemPrompt),
                    ChatMessage(
                        role = ChatRole.User,
                        content = listOf(
                            TextPart(userText),
                            ImagePart("data:image/jpeg;base64,$imageBase64"),
                        ),
                    ),
                ),
                temperature = 0.3,
            )
        )
        return response.choices.firstOrNull()?.message?.content ?: "No response from AI."
    }

    suspend fun recordVoice(durationMs: Long = 5000): String {
        if (!ctx.client.capabilities.canRecordAudio) {
            return ""
        }

        ctx.client.display("Listening...", DisplayOptions())
        val session = ctx.client.startMicrophone(
            MicrophoneOptions(preferredEncoding = AudioEncoding.PCM_S16_LE)
        ).getOrThrow()

        val chunks = withTimeoutOrNull(durationMs) {
            session.audio.toList()
        } ?: emptyList()
        session.stop()

        val pcmBytes = chunks.flatMap { it.bytes.toList() }.toByteArray()
        if (pcmBytes.isEmpty()) return ""

        val sampleRate = session.format.sampleRateHz ?: 16000
        val channels = session.format.channelCount ?: 1
        val wavBytes = buildWav(pcmBytes, sampleRate, channels, 16)

        ctx.client.display("Recognizing speech...", DisplayOptions())
        val transcription = openAI.transcription(
            TranscriptionRequest(
                audio = FileSource(
                    name = "audio.wav",
                    source = ByteArrayInputStream(wavBytes).asSource(),
                ),
                model = ModelId("whisper-1"),
            )
        )
        return transcription.text
    }

    suspend fun displayAndSpeak(text: String) {
        if (ctx.client.capabilities.canPlayTts) {
            ctx.client.playAudio(AudioSource.Tts(text))
        }
        ctx.client.display(text, DisplayOptions())
    }
}

// ---------------------------------------------------------------------------
// Command 1: Where Am I
// ---------------------------------------------------------------------------

private class WhereAmICommand : UniversalCommand {
    override val id: String = "where_am_i"
    override val title: String = "Where Am I"

    override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
        val ai = try {
            AIHelper(ctx)
        } catch (e: IllegalArgumentException) {
            ctx.log("ERROR: ${e.message}")
            return Result.failure(e)
        }

        ctx.client.display("Capturing surroundings...", DisplayOptions())

        val (_, b64) = ai.captureAndEncode() ?: return Result.success(Unit)

        ctx.client.display("Identifying location...", DisplayOptions())

        val systemPrompt = ai.buildSystemPrompt(
            "Identify the user's current location inside the venue based on visible signs, landmarks, store fronts, and any other visual cues."
        )

        val result = try {
            ai.visionChat(
                systemPrompt,
                "Where am I? Identify my current location based on what you see in this photo.",
                b64,
            )
        } catch (e: Exception) {
            Log.e("IndoorNav", "Vision API failed", e)
            "Error: ${e.message}"
        }

        // Update state
        NavState.addLocation(result.take(100))
        if (NavState.venueContext.length < 500) {
            NavState.venueContext += " | Location: $result"
        }

        ai.displayAndSpeak(result)
        return Result.success(Unit)
    }
}

// ---------------------------------------------------------------------------
// Command 2: Navigate To
// ---------------------------------------------------------------------------

private class NavigateToCommand : UniversalCommand {
    override val id: String = "navigate_to"
    override val title: String = "Navigate To"

    override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
        val ai = try {
            AIHelper(ctx)
        } catch (e: IllegalArgumentException) {
            ctx.log("ERROR: ${e.message}")
            return Result.failure(e)
        }

        // Step 1: Get destination via voice
        val destination = ai.recordVoice()
        if (destination.isBlank()) {
            ctx.client.display("No destination heard. Please try again.", DisplayOptions())
            return Result.success(Unit)
        }
        ctx.log("Destination: $destination")

        // Step 2: Capture current surroundings
        ctx.client.display("Capturing surroundings...", DisplayOptions())
        val (_, b64) = ai.captureAndEncode() ?: return Result.success(Unit)

        // Step 3: Get navigation directions
        ctx.client.display("Planning route to: $destination", DisplayOptions())

        val systemPrompt = ai.buildSystemPrompt(
            "Provide step-by-step walking directions from the user's current visible location to their destination. Max 4 concise steps."
        )

        val result = try {
            ai.visionChat(
                systemPrompt,
                "I want to go to: $destination\nBased on the photo of my current location, give me step-by-step directions.",
                b64,
            )
        } catch (e: Exception) {
            Log.e("IndoorNav", "Navigation failed", e)
            "Error: ${e.message}"
        }

        NavState.addLocation("→ $destination")

        ai.displayAndSpeak(result)
        return Result.success(Unit)
    }
}

// ---------------------------------------------------------------------------
// Command 3: Find Nearest
// ---------------------------------------------------------------------------

private class FindNearestCommand : UniversalCommand {
    override val id: String = "find_nearest"
    override val title: String = "Find Nearest"

    override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
        val ai = try {
            AIHelper(ctx)
        } catch (e: IllegalArgumentException) {
            ctx.log("ERROR: ${e.message}")
            return Result.failure(e)
        }

        // Step 1: Get what to find via voice
        val target = ai.recordVoice()
        if (target.isBlank()) {
            ctx.client.display("Didn't catch that. Please try again.", DisplayOptions())
            return Result.success(Unit)
        }
        ctx.log("Looking for: $target")

        // Step 2: Capture surroundings for context
        ctx.client.display("Scanning area...", DisplayOptions())
        val (_, b64) = ai.captureAndEncode() ?: return Result.success(Unit)

        // Step 3: Ask AI to locate nearest facility
        ctx.client.display("Finding nearest: $target", DisplayOptions())

        val systemPrompt = ai.buildSystemPrompt(
            "Help the user find the nearest facility or point of interest. Use visible signs, directories, and typical venue layouts to suggest the most likely direction."
        )

        val result = try {
            ai.visionChat(
                systemPrompt,
                "I need to find the nearest: $target\nBased on the photo and any visible signs or directories, where is the closest one and how do I get there?",
                b64,
            )
        } catch (e: Exception) {
            Log.e("IndoorNav", "Find nearest failed", e)
            "Error: ${e.message}"
        }

        ai.displayAndSpeak(result)
        return Result.success(Unit)
    }
}

// ---------------------------------------------------------------------------
// Command 4: Read Signs
// ---------------------------------------------------------------------------

private class ReadSignsCommand : UniversalCommand {
    override val id: String = "read_signs"
    override val title: String = "Read Signs"

    override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
        val ai = try {
            AIHelper(ctx)
        } catch (e: IllegalArgumentException) {
            ctx.log("ERROR: ${e.message}")
            return Result.failure(e)
        }

        ctx.client.display("Capturing signs...", DisplayOptions())
        val (_, b64) = ai.captureAndEncode() ?: return Result.success(Unit)

        ctx.client.display("Reading signs...", DisplayOptions())

        val systemPrompt = ai.buildSystemPrompt(
            "Read, translate, and interpret all visible signs, directories, floor maps, room numbers, and wayfinding information in the image. Organize the information clearly."
        )

        val result = try {
            ai.visionChat(
                systemPrompt,
                "Read all signs and directories visible in this photo. For each sign, tell me what it says, what it means, and which direction it points to (if applicable). If signs are in a language different from the venue description, translate them.",
                b64,
            )
        } catch (e: Exception) {
            Log.e("IndoorNav", "Read signs failed", e)
            "Error: ${e.message}"
        }

        // Update venue context with sign information
        if (NavState.venueContext.length < 800) {
            NavState.venueContext += " | Signs: ${result.take(200)}"
        }

        ai.displayAndSpeak(result)
        return Result.success(Unit)
    }
}

// ---------------------------------------------------------------------------
// Utility: WAV builder
// ---------------------------------------------------------------------------

private fun buildWav(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt(36 + pcmData.size)
        put("WAVE".toByteArray(Charsets.US_ASCII))
        put("fmt ".toByteArray(Charsets.US_ASCII))
        putInt(16)
        putShort(1) // PCM format
        putShort(channels.toShort())
        putInt(sampleRate)
        putInt(byteRate)
        putShort(blockAlign.toShort())
        putShort(bitsPerSample.toShort())
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(pcmData.size)
    }.array()
    return header + pcmData
}
