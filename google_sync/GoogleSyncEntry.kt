package com.example.googlesync.logic

import android.util.Log
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.audio.TranscriptionRequest
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
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.asSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Google Ecosystem Sync — automatically extract memories, tasks, and calendar
 * events from conversations and sync them to Google Drive, Calendar, and Tasks.
 *
 * Workflow:
 * 1. Record audio from the glasses microphone (or accept pasted text).
 * 2. Transcribe via OpenAI Whisper.
 * 3. Extract structured data (memories / tasks / events) via LLM.
 * 4. Sync to Google Drive (transcript document), Calendar (events), and Tasks (action items).
 *
 * Google OAuth2 token must be obtained externally (e.g. via OAuth Playground) with scopes:
 *   https://www.googleapis.com/auth/drive.file
 *   https://www.googleapis.com/auth/calendar
 *   https://www.googleapis.com/auth/tasks
 */
class GoogleSyncEntry : UniversalAppEntrySimple {
    override val id: String = "google_ecosystem_sync"
    override val displayName: String = "Google Ecosystem Sync"

    override fun userSettings(): List<UserSettingField> = AIApiSettings.fields(
        defaultBaseUrl = "https://api.openai.com/v1/",
        defaultModel = "gpt-4o-mini",
    ) + listOf(
        UserSettingField(
            key = KEY_GOOGLE_TOKEN,
            label = "Google OAuth2 Access Token",
            hint = "Bearer token with Drive, Calendar, Tasks scopes",
            inputType = UserSettingInputType.PASSWORD,
        ),
        UserSettingField(
            key = KEY_LISTEN_DURATION,
            label = "Listen Duration (seconds)",
            hint = "How long to record audio (default 30)",
            defaultValue = "30",
            inputType = UserSettingInputType.NUMBER,
        ),
        UserSettingField(
            key = KEY_MANUAL_TEXT,
            label = "Manual Text Input",
            hint = "Paste conversation text here for the Sync Text command",
            inputType = UserSettingInputType.TEXT,
        ),
    )

    override fun commands(): List<UniversalCommand> {
        return listOf(
            ListenAndSyncCommand(),
            SyncTextCommand(),
            ViewSyncStatusCommand(),
        )
    }

    companion object {
        private const val TAG = "GoogleSync"
        const val KEY_GOOGLE_TOKEN = "google_oauth_token"
        const val KEY_LISTEN_DURATION = "listen_duration_secs"
        const val KEY_MANUAL_TEXT = "manual_text_input"
    }

}

// ---------------------------------------------------------------------------
// Data models
// ---------------------------------------------------------------------------

data class Memory(val fact: String, val category: String)

data class TaskItem(val title: String, val notes: String, val dueDate: String?)

data class CalendarEvent(
    val title: String,
    val description: String,
    val startDateTime: String,
    val endDateTime: String,
    val location: String?,
)

data class ExtractedData(
    val summary: String,
    val memories: List<Memory>,
    val tasks: List<TaskItem>,
    val events: List<CalendarEvent>,
)

data class SyncResult(
    val timestamp: Long = System.currentTimeMillis(),
    val driveFileId: String? = null,
    val calendarEventsCreated: Int = 0,
    val tasksCreated: Int = 0,
    val memoriesExtracted: Int = 0,
    val errors: List<String> = emptyList(),
)

/** Shared state across commands (the host instantiates entry + commands once). */
private object SyncState {
    @Volatile
    var lastResult: SyncResult? = null
}

// ---------------------------------------------------------------------------
// Command 1: Listen & Sync
// ---------------------------------------------------------------------------

private class ListenAndSyncCommand : UniversalCommand {
    override val id: String = "listen_and_sync"
    override val title: String = "Listen & Sync"

    private var openAI: OpenAI? = null
    private var apiModel: String = ""

    private fun ensureClient(ctx: UniversalAppContext) {
        val baseUrl = AIApiSettings.baseUrl(ctx.settings)
        val apiKey = AIApiSettings.apiKey(ctx.settings)
        apiModel = AIApiSettings.model(ctx.settings)

        require(baseUrl.isNotBlank()) { "API Base URL is not configured. Please fill in Settings and Apply." }
        require(apiKey.isNotBlank()) { "API Key is not configured. Please fill in Settings and Apply." }
        require(apiModel.isNotBlank()) { "Model is not configured. Please fill in Settings and Apply." }

        val googleToken = ctx.settings[GoogleSyncEntry.KEY_GOOGLE_TOKEN].orEmpty()
        require(googleToken.isNotBlank()) { "Google OAuth token is not configured. Please fill in Settings and Apply." }

        openAI = OpenAI(
            token = apiKey,
            host = OpenAIHost(baseUrl = baseUrl),
        )
    }

    override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
        try {
            ensureClient(ctx)
        } catch (e: IllegalArgumentException) {
            ctx.log("ERROR: ${e.message}")
            return Result.failure(e)
        }

        if (!ctx.client.capabilities.canRecordAudio) {
            ctx.log("This device does not support audio recording.")
            return ctx.client.display("Audio recording not supported on this device.", DisplayOptions())
        }

        val googleToken = ctx.settings[GoogleSyncEntry.KEY_GOOGLE_TOKEN]!!
        val durationMs = (ctx.settings[GoogleSyncEntry.KEY_LISTEN_DURATION]?.toLongOrNull() ?: 30) * 1000

        // Step 1: Record audio
        ctx.log("Recording for ${durationMs / 1000}s...")
        ctx.client.display("Listening for ${durationMs / 1000}s...", DisplayOptions())

        val transcript = recordAndTranscribe(ctx, durationMs)
        if (transcript.isBlank()) {
            ctx.log("No speech detected.")
            return ctx.client.display("No speech detected.", DisplayOptions())
        }
        ctx.log("Transcript: $transcript")

        // Step 2 & 3: Extract + Sync
        val result = extractAndSync(ctx, transcript, googleToken)

        SyncState.lastResult = result

        return displaySyncSummary(ctx, result)
    }

    private suspend fun recordAndTranscribe(ctx: UniversalAppContext, durationMs: Long): String {
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

        ctx.client.display("Transcribing...", DisplayOptions())
        val transcription = openAI!!.transcription(
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

    /** Shared pipeline: AI extraction → Google sync. */
    private suspend fun extractAndSync(
        ctx: UniversalAppContext,
        transcript: String,
        googleToken: String,
    ): SyncResult {
        val errors = mutableListOf<String>()
        val httpClient = HttpClient(OkHttp)

        // Extract structured data
        ctx.client.display("Analyzing conversation...", DisplayOptions())
        val extracted = try {
            extractWithAI(transcript)
        } catch (e: Exception) {
            Log.e("GoogleSync", "AI extraction failed", e)
            ctx.log("Extraction failed: ${e.message}")
            errors.add("Extraction: ${e.message}")
            ExtractedData("Extraction failed", emptyList(), emptyList(), emptyList())
        }

        // Sync to Google services
        ctx.client.display("Syncing to Google...", DisplayOptions())

        val driveId = try {
            GoogleApiHelper.syncToDrive(httpClient, googleToken, transcript, extracted)
        } catch (e: Exception) {
            Log.e("GoogleSync", "Drive sync failed", e)
            errors.add("Drive: ${e.message}"); null
        }

        val calCount = try {
            GoogleApiHelper.syncToCalendar(httpClient, googleToken, extracted.events)
        } catch (e: Exception) {
            Log.e("GoogleSync", "Calendar sync failed", e)
            errors.add("Calendar: ${e.message}"); 0
        }

        val taskCount = try {
            GoogleApiHelper.syncToTasks(httpClient, googleToken, extracted.tasks)
        } catch (e: Exception) {
            Log.e("GoogleSync", "Tasks sync failed", e)
            errors.add("Tasks: ${e.message}"); 0
        }

        httpClient.close()

        return SyncResult(
            driveFileId = driveId,
            calendarEventsCreated = calCount,
            tasksCreated = taskCount,
            memoriesExtracted = extracted.memories.size,
            errors = errors,
        )
    }

    private suspend fun extractWithAI(transcript: String): ExtractedData {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val response = openAI!!.chatCompletion(
            ChatCompletionRequest(
                model = ModelId(apiModel),
                messages = listOf(
                    ChatMessage(role = ChatRole.System, content = EXTRACTION_SYSTEM_PROMPT),
                    ChatMessage(
                        role = ChatRole.User,
                        content = "Today's date is $today. Analyze this conversation transcript:\n\n$transcript",
                    ),
                ),
                temperature = 0.1,
            )
        )
        val raw = response.choices.firstOrNull()?.message?.content
            ?: throw RuntimeException("Empty AI response")

        // Strip markdown code fences if the model wraps in ```json ... ```
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()

        return parseExtractedJson(JSONObject(cleaned))
    }
}

// ---------------------------------------------------------------------------
// Command 2: Sync Text
// ---------------------------------------------------------------------------

private class SyncTextCommand : UniversalCommand {
    override val id: String = "sync_text"
    override val title: String = "Sync Text"

    private var openAI: OpenAI? = null
    private var apiModel: String = ""

    private fun ensureClient(ctx: UniversalAppContext) {
        val baseUrl = AIApiSettings.baseUrl(ctx.settings)
        val apiKey = AIApiSettings.apiKey(ctx.settings)
        apiModel = AIApiSettings.model(ctx.settings)

        require(baseUrl.isNotBlank()) { "API Base URL is not configured. Please fill in Settings and Apply." }
        require(apiKey.isNotBlank()) { "API Key is not configured. Please fill in Settings and Apply." }
        require(apiModel.isNotBlank()) { "Model is not configured. Please fill in Settings and Apply." }

        val googleToken = ctx.settings[GoogleSyncEntry.KEY_GOOGLE_TOKEN].orEmpty()
        require(googleToken.isNotBlank()) { "Google OAuth token is not configured. Please fill in Settings and Apply." }
    }

    override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
        try {
            ensureClient(ctx)
        } catch (e: IllegalArgumentException) {
            ctx.log("ERROR: ${e.message}")
            return Result.failure(e)
        }

        val text = ctx.settings[GoogleSyncEntry.KEY_MANUAL_TEXT]
        if (text.isNullOrBlank()) {
            ctx.log("No manual text provided.")
            return ctx.client.display("Set 'Manual Text Input' in Settings first.", DisplayOptions())
        }

        openAI = OpenAI(
            token = AIApiSettings.apiKey(ctx.settings),
            host = OpenAIHost(baseUrl = AIApiSettings.baseUrl(ctx.settings)),
        )

        val googleToken = ctx.settings[GoogleSyncEntry.KEY_GOOGLE_TOKEN]!!
        val errors = mutableListOf<String>()
        val httpClient = HttpClient(OkHttp)

        // Extract
        ctx.client.display("Analyzing text...", DisplayOptions())
        val extracted = try {
            extractWithAI(text)
        } catch (e: Exception) {
            Log.e("GoogleSync", "AI extraction failed", e)
            ctx.log("Extraction failed: ${e.message}")
            errors.add("Extraction: ${e.message}")
            ExtractedData("Extraction failed", emptyList(), emptyList(), emptyList())
        }

        // Sync
        ctx.client.display("Syncing to Google...", DisplayOptions())

        val driveId = try {
            GoogleApiHelper.syncToDrive(httpClient, googleToken, text, extracted)
        } catch (e: Exception) {
            errors.add("Drive: ${e.message}"); null
        }

        val calCount = try {
            GoogleApiHelper.syncToCalendar(httpClient, googleToken, extracted.events)
        } catch (e: Exception) {
            errors.add("Calendar: ${e.message}"); 0
        }

        val taskCount = try {
            GoogleApiHelper.syncToTasks(httpClient, googleToken, extracted.tasks)
        } catch (e: Exception) {
            errors.add("Tasks: ${e.message}"); 0
        }

        httpClient.close()

        val result = SyncResult(
            driveFileId = driveId,
            calendarEventsCreated = calCount,
            tasksCreated = taskCount,
            memoriesExtracted = extracted.memories.size,
            errors = errors,
        )

        SyncState.lastResult = result

        return displaySyncSummary(ctx, result)
    }

    private suspend fun extractWithAI(transcript: String): ExtractedData {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val response = openAI!!.chatCompletion(
            ChatCompletionRequest(
                model = ModelId(apiModel),
                messages = listOf(
                    ChatMessage(role = ChatRole.System, content = EXTRACTION_SYSTEM_PROMPT),
                    ChatMessage(
                        role = ChatRole.User,
                        content = "Today's date is $today. Analyze this conversation transcript:\n\n$transcript",
                    ),
                ),
                temperature = 0.1,
            )
        )
        val raw = response.choices.firstOrNull()?.message?.content
            ?: throw RuntimeException("Empty AI response")
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        return parseExtractedJson(JSONObject(cleaned))
    }
}

// ---------------------------------------------------------------------------
// Command 3: View Sync Status
// ---------------------------------------------------------------------------

private class ViewSyncStatusCommand : UniversalCommand {
    override val id: String = "view_sync_status"
    override val title: String = "View Sync Status"

    override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
        val r = SyncState.lastResult
        if (r == null) {
            return ctx.client.display("No sync performed yet.", DisplayOptions())
        }
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(r.timestamp))
        val status = buildString {
            appendLine("Last sync: $time")
            appendLine("Memories: ${r.memoriesExtracted}")
            appendLine("Calendar: ${r.calendarEventsCreated} events")
            appendLine("Tasks: ${r.tasksCreated} items")
            if (r.driveFileId != null) appendLine("Drive: saved")
            if (r.errors.isNotEmpty()) {
                appendLine("Errors:")
                r.errors.forEach { appendLine("  - $it") }
            }
        }
        return ctx.client.display(status, DisplayOptions())
    }
}

// ---------------------------------------------------------------------------
// Google API helper
// ---------------------------------------------------------------------------

private object GoogleApiHelper {

    suspend fun syncToDrive(
        client: HttpClient,
        token: String,
        transcript: String,
        extracted: ExtractedData,
    ): String? {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val content = buildString {
            appendLine("=== Conversation Transcript ===")
            appendLine("Date: $ts")
            appendLine()
            appendLine("--- Summary ---")
            appendLine(extracted.summary)
            appendLine()
            appendLine("--- Full Transcript ---")
            appendLine(transcript)
            if (extracted.memories.isNotEmpty()) {
                appendLine()
                appendLine("--- Key Memories ---")
                extracted.memories.forEach { appendLine("- [${it.category}] ${it.fact}") }
            }
            if (extracted.tasks.isNotEmpty()) {
                appendLine()
                appendLine("--- Action Items ---")
                extracted.tasks.forEach {
                    val due = if (it.dueDate != null) " (due: ${it.dueDate})" else ""
                    appendLine("- ${it.title}$due")
                    if (it.notes.isNotBlank()) appendLine("  Notes: ${it.notes}")
                }
            }
            if (extracted.events.isNotEmpty()) {
                appendLine()
                appendLine("--- Calendar Events ---")
                extracted.events.forEach {
                    appendLine("- ${it.title}: ${it.startDateTime} ~ ${it.endDateTime}")
                    if (it.description.isNotBlank()) appendLine("  ${it.description}")
                }
            }
        }

        val fileName = "XGGlass_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}.txt"
        val boundary = "xgglass_${System.currentTimeMillis()}"
        val metadata = JSONObject().apply {
            put("name", fileName)
            put("mimeType", "text/plain")
        }.toString()

        val body = buildString {
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata)
            append("\r\n--$boundary\r\n")
            append("Content-Type: text/plain\r\n\r\n")
            append(content)
            append("\r\n--$boundary--")
        }

        val resp = client.post("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.parse("multipart/related; boundary=$boundary"))
            setBody(body)
        }
        if (resp.status.value in 200..299) {
            return JSONObject(resp.bodyAsText()).optString("id", null)
        }
        throw RuntimeException("Drive API ${resp.status.value}: ${resp.bodyAsText().take(200)}")
    }

    suspend fun syncToCalendar(
        client: HttpClient,
        token: String,
        events: List<CalendarEvent>,
    ): Int {
        var created = 0
        for (event in events) {
            val json = JSONObject().apply {
                put("summary", event.title)
                put("description", event.description)
                put("start", JSONObject().put("dateTime", withTimezone(event.startDateTime)))
                put("end", JSONObject().put("dateTime", withTimezone(event.endDateTime)))
                if (event.location != null) put("location", event.location)
            }.toString()

            val resp = client.post("https://www.googleapis.com/calendar/v3/calendars/primary/events") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(json)
            }
            if (resp.status.value in 200..299) created++
        }
        return created
    }

    suspend fun syncToTasks(
        client: HttpClient,
        token: String,
        tasks: List<TaskItem>,
    ): Int {
        var created = 0
        for (task in tasks) {
            val json = JSONObject().apply {
                put("title", task.title)
                if (task.notes.isNotBlank()) put("notes", task.notes)
                if (task.dueDate != null) put("due", "${task.dueDate}T00:00:00.000Z")
            }.toString()

            val resp = client.post("https://www.googleapis.com/tasks/v1/lists/@default/tasks") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(json)
            }
            if (resp.status.value in 200..299) created++
        }
        return created
    }

    /** Append local timezone offset if the datetime string doesn't already include one. */
    private fun withTimezone(dt: String): String {
        if (dt.contains('+') || dt.contains('Z') || dt.matches(Regex(".*-\\d{2}:\\d{2}$"))) return dt
        val tz = TimeZone.getDefault()
        val off = tz.rawOffset
        val h = Math.abs(off) / 3600000
        val m = (Math.abs(off) % 3600000) / 60000
        val sign = if (off >= 0) "+" else "-"
        return "$dt$sign${"%02d".format(h)}:${"%02d".format(m)}"
    }
}

// ---------------------------------------------------------------------------
// Shared utilities
// ---------------------------------------------------------------------------

/** Build a WAV byte array from raw PCM data. */
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

/** Parse the JSON output from the AI extraction prompt. */
private fun parseExtractedJson(json: JSONObject): ExtractedData {
    val memories = mutableListOf<Memory>()
    val mArr = json.optJSONArray("memories") ?: JSONArray()
    for (i in 0 until mArr.length()) {
        val o = mArr.getJSONObject(i)
        memories.add(Memory(o.getString("fact"), o.optString("category", "insight")))
    }

    val tasks = mutableListOf<TaskItem>()
    val tArr = json.optJSONArray("tasks") ?: JSONArray()
    for (i in 0 until tArr.length()) {
        val o = tArr.getJSONObject(i)
        tasks.add(TaskItem(
            title = o.getString("title"),
            notes = o.optString("notes", ""),
            dueDate = o.optString("due_date", null).takeIf { !it.isNullOrBlank() && it != "null" },
        ))
    }

    val events = mutableListOf<CalendarEvent>()
    val eArr = json.optJSONArray("events") ?: JSONArray()
    for (i in 0 until eArr.length()) {
        val o = eArr.getJSONObject(i)
        events.add(CalendarEvent(
            title = o.getString("title"),
            description = o.optString("description", ""),
            startDateTime = o.getString("start"),
            endDateTime = o.getString("end"),
            location = o.optString("location", null).takeIf { !it.isNullOrBlank() && it != "null" },
        ))
    }

    return ExtractedData(
        summary = json.optString("summary", ""),
        memories = memories,
        tasks = tasks,
        events = events,
    )
}

/** Display a summary of the sync result on glasses (+ TTS if supported). */
private suspend fun displaySyncSummary(ctx: UniversalAppContext, r: SyncResult): Result<Unit> {
    val summary = buildString {
        append("Synced!")
        if (r.driveFileId != null) append(" Drive: OK.")
        if (r.calendarEventsCreated > 0) append(" Calendar: ${r.calendarEventsCreated}.")
        if (r.tasksCreated > 0) append(" Tasks: ${r.tasksCreated}.")
        if (r.memoriesExtracted > 0) append(" Memories: ${r.memoriesExtracted}.")
        if (r.errors.isNotEmpty()) append(" Errors: ${r.errors.size}.")
    }
    ctx.log(summary)
    if (ctx.client.capabilities.canPlayTts) {
        ctx.client.playAudio(AudioSource.Tts(summary))
    }
    return ctx.client.display(summary, DisplayOptions())
}

/** AI system prompt for structured extraction. */
private const val EXTRACTION_SYSTEM_PROMPT = """You are an AI assistant that analyzes conversation transcripts. Extract structured information and return ONLY valid JSON with no markdown formatting.

Return a JSON object with this exact structure:
{
  "summary": "1-2 sentence summary of the conversation",
  "memories": [
    {"fact": "key fact or insight", "category": "person|decision|insight|preference|reference"}
  ],
  "tasks": [
    {"title": "action item title", "notes": "additional context", "due_date": "YYYY-MM-DD or null"}
  ],
  "events": [
    {
      "title": "event title",
      "description": "event details",
      "start": "YYYY-MM-DDTHH:MM:SS",
      "end": "YYYY-MM-DDTHH:MM:SS",
      "location": "location or null"
    }
  ]
}

Rules:
- Extract ONLY information explicitly mentioned or strongly implied
- For tasks: include any action items, to-dos, follow-ups, or commitments
- For events: include meetings, appointments, deadlines with specific dates/times
- For memories: include key facts about people, decisions made, important references
- If a date is mentioned without a year, assume the current year
- If a time is mentioned without a date, assume today
- If an event has no explicit end time, default to 1 hour after start
- Return empty arrays if no items of that type are found
- Do NOT invent information not present in the transcript"""
