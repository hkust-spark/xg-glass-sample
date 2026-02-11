package com.example.examsolver.logic

import android.util.Log
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ContentPart
import com.aallam.openai.api.chat.ImagePart
import com.aallam.openai.api.chat.TextPart
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIHost
import com.universalglasses.appcontract.UniversalAppContext
import com.universalglasses.appcontract.UniversalAppEntrySimple
import com.universalglasses.appcontract.UniversalCommand
import com.universalglasses.core.CaptureOptions
import com.universalglasses.core.DisplayOptions
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.job
import java.util.Base64

/**
 * Exam Solver with memory — the Rokid "memory" AI-glasses workflow ported to the
 * Universal Glasses SDK, using the aallam/openai-kotlin library for AI calls.
 *
 * Behaviour:
 * - Runs an auto-capture loop: capture photo → call AI (streaming, with conversation memory)
 *   → display answer on glasses → wait → repeat.
 * - Conversation history is maintained across rounds (up to [MAX_HISTORY_ROUNDS] rounds).
 * - Valid answers are streamed to the glasses in real-time; invalid rounds are rolled back
 *   and the previous answer is kept on display.
 * - The host cancels the coroutine to stop the loop.
 */
class ExamSolverEntry : UniversalAppEntrySimple {
    override val id: String = "exam_solver_demo"
    override val displayName: String = "Exam Solver Demo"

    override fun commands(): List<UniversalCommand> {
        return listOf(ExamSolverCommand())
    }
}

// ---------------------------------------------------------------------------
// Internal data class for AI call results
// ---------------------------------------------------------------------------

private data class AIResult(
    val displayText: String,
    /** true = valid answer; false = "invalid request" response from the model. */
    val isValid: Boolean,
    /** true = system / network / parsing error (not a model-level "invalid request"). */
    val isError: Boolean,
)

// ---------------------------------------------------------------------------
// The core auto-capture-loop command
// ---------------------------------------------------------------------------

private class ExamSolverCommand : UniversalCommand {
    override val id: String = "exam_solver"
    override val title: String = "Exam Solver"

    companion object {
        private const val TAG = "ExamSolver"

        // --- Timing ---
        private const val INITIAL_DELAY_MS = 10_000L
        private const val CAPTURE_INTERVAL_MS = 15_000L
        private const val INVALID_RETRY_DELAY_MS = 5_000L
        private const val STREAM_UPDATE_MIN_INTERVAL_MS = 350L

        // --- History ---
        private const val MAX_HISTORY_ROUNDS = 5

        // --- AI API defaults (OpenAI-compatible Chat Completions, e.g., Poe) ---
        private const val API_BASE_URL = "https://api.poe.com/v1/"
        private const val API_MODEL = "GPT-5.2"
        // TODO: Replace with your actual Poe API key.
        private const val API_KEY = ""
    }

    // ===== OpenAI client (aallam/openai-kotlin, configured for Poe) =====
    private val openAI = OpenAI(
        token = API_KEY,
        host = OpenAIHost(baseUrl = API_BASE_URL),
    )

    // ===== Conversation history (always starts with the system prompt) =====
    private val messages: MutableList<ChatMessage> = mutableListOf(
        ChatMessage(
            role = ChatRole.System,
            content = "You are a helpful assistant. Answer concisely and correctly.",
        )
    )

    // ===== Glasses display state =====
    /** Last valid answer text; kept on-screen across rounds. */
    private var lastAnswerForGlasses: String = ""

    // ===================================================================
    // Main loop
    // ===================================================================

    override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
        val client = ctx.client
        ctx.log("Exam Solver started. model=${client.model}")

        // Initial delay with countdown.
        showCountdown(ctx, INITIAL_DELAY_MS)

        while (currentCoroutineContext().job.isActive) {
            currentCoroutineContext().ensureActive()

            // --- Capture ---
            ctx.log("Capturing...")
            displayStatus(ctx, "Capturing...")

            val captureResult = client.capturePhoto(
                CaptureOptions(quality = 90, targetWidth = 2400, targetHeight = 1800)
            )
            val image = captureResult.getOrNull()
            if (image == null) {
                ctx.log("Capture failed: ${captureResult.exceptionOrNull()?.message}")
                displayStatus(ctx, "Capture failed")
                showCountdown(ctx, CAPTURE_INTERVAL_MS)
                continue
            }

            ctx.log("Captured ${image.jpegBytes.size} bytes")
            ctx.onCapturedImage?.invoke(image)

            // --- Call AI (streaming, with memory) ---
            displayStatus(ctx, "Calling AI...")
            ctx.log("Calling AI...")

            val aiResult = callAIStreaming(ctx, image.jpegBytes)

            when {
                aiResult.isValid -> {
                    // Commit the new answer text.
                    lastAnswerForGlasses = aiResult.displayText
                    client.display(lastAnswerForGlasses, DisplayOptions(force = true))
                    ctx.log("AI answered (valid)")
                    showCountdown(ctx, CAPTURE_INTERVAL_MS)
                }
                aiResult.isError -> {
                    // System error — keep previous answer, show brief status.
                    ctx.log("AI error: ${aiResult.displayText}")
                    displayStatus(ctx, "AI call failed")
                    showCountdown(ctx, CAPTURE_INTERVAL_MS)
                }
                else -> {
                    // Model returned "invalid request" — keep previous answer, retry sooner.
                    val invalidText = aiResult.displayText
                    ctx.log("Invalid: $invalidText")
                    displayStatus(ctx, invalidText)
                    showCountdown(ctx, INVALID_RETRY_DELAY_MS, prefix = invalidText)
                }
            }
        }

        return Result.success(Unit)
    }

    // ===================================================================
    // Display helpers
    // ===================================================================

    /**
     * Compose the full text shown on the glasses: last valid answer + a status line below it.
     */
    private fun composeGlassesText(status: String = ""): String {
        val answer = lastAnswerForGlasses.trimEnd()
        val s = status.trim()
        return when {
            answer.isBlank() && s.isBlank() -> ""
            answer.isBlank() -> s
            s.isBlank() -> answer
            else -> "$answer\n$s"
        }
    }

    /** Update the status line without overwriting the last valid answer text. */
    private suspend fun displayStatus(ctx: UniversalAppContext, status: String) {
        val text = composeGlassesText(status)
        if (text.isNotBlank()) {
            ctx.client.display(text, DisplayOptions(force = false))
        }
    }

    /** Show a countdown timer on the glasses (1-second ticks). */
    private suspend fun showCountdown(
        ctx: UniversalAppContext,
        totalMs: Long,
        prefix: String? = null,
    ) {
        var remaining = totalMs
        val stepMs = 1_000L
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            val secs = ((remaining + 999) / 1000).coerceAtLeast(1)
            val status = if (!prefix.isNullOrBlank()) {
                "$prefix (next capture in ${secs}s)"
            } else {
                "Next capture in ${secs}s"
            }
            displayStatus(ctx, status)
            val sleep = minOf(stepMs, remaining)
            delay(sleep)
            remaining -= sleep
        }
    }

    // ===================================================================
    // AI with memory — streaming via aallam/openai-kotlin
    // ===================================================================

    private suspend fun callAIStreaming(
        ctx: UniversalAppContext,
        imageBytes: ByteArray,
    ): AIResult {
        val base64Image = Base64.getEncoder().encodeToString(imageBytes)

        val prompt =
            "Please answer all questions shown in the current image. " +
            "A request is considered invalid if the image is irrelevant to this task. " +
            "If the request is valid, provide concise and correct answers with minimal analysis. " +
            "For a valid request, first return 'valid request' and then return one or more lines " +
            "in the format: '<question_id>: (<analysis>) **<answer>**'. " +
            "If the request is invalid due to text too small or blurred, still try your best to answer, " +
            "i.e., return 'valid request', a warning of 'the answer might be incorrect due to text too " +
            "small, please stay closer' and then return one or more lines in the format: " +
            "'<question_id>: (<analysis>) **answer**'. " +
            "If it is really hard to parse or invalid due to other reasons, return: " +
            "'invalid request: <brief_reason>'. " +
            "Ensure the output strictly matches the format above."

        // Snapshot messages before this round so we can rollback on failure / invalid.
        val messagesSnapshot = ArrayList(messages)

        // Build multimodal user message (text + base64 image).
        val userMsg = ChatMessage(
            role = ChatRole.User,
            messageContent = com.aallam.openai.api.chat.ListContent(
                listOf(
                    TextPart(text = prompt),
                    ImagePart(url = "data:image/jpeg;base64,$base64Image"),
                )
            ),
        )
        messages.add(userMsg)
        trimHistory()

        val request = ChatCompletionRequest(
            model = ModelId(API_MODEL),
            messages = messages.toList(),
        )

        Log.d(TAG, "Sending streaming request: model=$API_MODEL, historyMessages=${messages.size}")

        try {
            // --- Collect streaming chunks ---
            var isValidRound: Boolean? = null
            val chunks = StringBuilder()
            var lastEmitAt = 0L
            var streamError: Throwable? = null

            openAI.chatCompletions(request)
                .catch { e ->
                    streamError = e
                    Log.e(TAG, "Stream error", e)
                }
                .onCompletion { cause ->
                    if (cause != null) streamError = streamError ?: cause
                }
                .collect { chunk ->
                    val deltaText = chunk.choices.firstOrNull()?.delta?.content ?: return@collect
                    if (deltaText.isEmpty()) return@collect

                    // Determine valid / invalid from the very first non-empty content.
                    if (isValidRound == null && deltaText.isNotBlank()) {
                        val head = (chunks.toString() + deltaText).trim().lowercase()
                        isValidRound = when {
                            head.startsWith("valid") -> true
                            head.startsWith("invalid") -> false
                            else -> null // still undetermined
                        }
                    }

                    chunks.append(deltaText)

                    // Stream partial results to the glasses (throttled, valid rounds only).
                    if (isValidRound == true) {
                        val now = System.currentTimeMillis()
                        if (now - lastEmitAt >= STREAM_UPDATE_MIN_INTERVAL_MS) {
                            lastEmitAt = now
                            val textSoFar = stripValidHeader(chunks.toString())
                            if (textSoFar.isNotBlank()) {
                                lastAnswerForGlasses = textSoFar
                                ctx.client.display(textSoFar, DisplayOptions(force = false))
                            }
                        }
                    }
                }

            // If there was a stream-level error, treat as system error.
            if (streamError != null) {
                Log.e(TAG, "AI call failed", streamError)
                rollbackMessages(messagesSnapshot)
                return AIResult("Error: ${streamError!!.message}", isValid = false, isError = true)
            }

            val rawText = chunks.toString()

            // --- Handle result ---
            if (isValidRound == false) {
                // Model said "invalid request" — rollback, don't commit to history.
                rollbackMessages(messagesSnapshot)
                return AIResult(rawText.trim(), isValid = false, isError = false)
            }

            if (isValidRound != true) {
                // Could not determine valid/invalid — treat as system error.
                rollbackMessages(messagesSnapshot)
                return AIResult(
                    "Error: Cannot determine valid/invalid from stream.", isValid = false, isError = true
                )
            }

            // Valid round — commit assistant message to history.
            val finalText = stripValidHeader(rawText).trim()
            if (finalText.isBlank()) {
                rollbackMessages(messagesSnapshot)
                return AIResult("Error: Empty assistant output.", isValid = false, isError = true)
            }

            messages.add(
                ChatMessage(
                    role = ChatRole.Assistant,
                    content = rawText,
                )
            )
            trimHistory()

            return AIResult(finalText, isValid = true, isError = false)

        } catch (e: Exception) {
            Log.e(TAG, "AI call failed", e)
            rollbackMessages(messagesSnapshot)
            return AIResult("Error: ${e.message}", isValid = false, isError = true)
        }
    }

    // ===================================================================
    // Message / history helpers
    // ===================================================================

    private fun rollbackMessages(snapshot: List<ChatMessage>) {
        messages.clear()
        messages.addAll(snapshot)
    }

    /**
     * Keep at most [MAX_HISTORY_ROUNDS] rounds (1 round = user + assistant).
     * The first system message is always preserved.
     */
    private fun trimHistory() {
        if (messages.isEmpty()) return
        val systemPrefix = mutableListOf<ChatMessage>()
        var rest = messages.toList()
        if (messages[0].role == ChatRole.System) {
            systemPrefix.add(messages[0])
            rest = messages.drop(1)
        }
        val keepN = 2 * MAX_HISTORY_ROUNDS
        val trimmedRest = if (rest.size > keepN) rest.takeLast(keepN) else rest
        messages.clear()
        messages.addAll(systemPrefix + trimmedRest)
    }

    // ===================================================================
    // Text helpers
    // ===================================================================

    /**
     * Strip the leading "valid request" header that the model emits before the actual answer.
     * Handles variations like "valid", "Valid Request:", "valid request -", etc.
     */
    private fun stripValidHeader(text: String): String {
        val t = text.trimStart()
        val stripped = t.replaceFirst(Regex("(?i)^valid(?:\\s+request)?\\b[:\\-]?\\s*"), "")
        return stripped.trimStart('\n', '\r', ' ', '\t')
    }
}
