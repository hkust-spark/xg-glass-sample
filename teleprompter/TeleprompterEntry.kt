package com.example.teleprompter.logic

import com.xgglass.appcontract.UniversalAppContext
import com.xgglass.appcontract.UniversalAppEntrySimple
import com.xgglass.appcontract.UniversalCommand
import com.xgglass.appcontract.UserSettingField
import com.xgglass.appcontract.UserSettingInputType
import com.xgglass.core.DisplayMode
import com.xgglass.core.DisplayOptions
import com.xgglass.core.GlassesEvent
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class TeleprompterEntry : UniversalAppEntrySimple {
    override val id: String = "teleprompter_demo"
    override val displayName: String = "Teleprompter Demo"

    override fun userSettings(): List<UserSettingField> = listOf(
        UserSettingField(
            key = KEY_SCRIPT,
            label = "Prompt Script",
            hint = "Text to stream on the glasses display",
            defaultValue = DEFAULT_SCRIPT,
            inputType = UserSettingInputType.TEXT,
        ),
        UserSettingField(
            key = KEY_SECONDS_PER_CHUNK,
            label = "Seconds Per Chunk",
            hint = "e.g. 2",
            defaultValue = "2",
            inputType = UserSettingInputType.NUMBER,
        ),
    )

    override fun commands(): List<UniversalCommand> = listOf(PromptCommand())
}

private class PromptCommand : UniversalCommand {
    override val id: String = "prompt_demo"
    override val title: String = "Prompt Demo"

    override suspend fun run(ctx: UniversalAppContext): Result<Unit> = coroutineScope {
        val client = ctx.client
        val caps = client.capabilities
        val script = ctx.settings[KEY_SCRIPT].orEmpty().ifBlank { DEFAULT_SCRIPT }
        val delayMs = parseSeconds(ctx.settings[KEY_SECONDS_PER_CHUNK]) * 1_000L

        ctx.log(
            "Teleprompter: start model=${client.model}, display=${caps.canDisplayText}, " +
                "streaming=${caps.supportsStreamingTextUpdates}, tap=${caps.supportsTapEvents}, " +
                "longPress=${caps.supportsLongPressEvents}"
        )

        if (!caps.canDisplayText) {
            ctx.log("Teleprompter: selected device cannot display text; script follows in the phone log.")
            ctx.log(script.take(LOG_PREVIEW_CHARS))
            return@coroutineScope Result.success(Unit)
        }

        val paused = MutableStateFlow(false)
        val stopped = MutableStateFlow(false)
        val eventsJob = if (caps.supportsTapEvents || caps.supportsLongPressEvents) {
            launch {
                client.events.collect { event ->
                    when (event) {
                        is GlassesEvent.Tap -> {
                            if (caps.supportsTapEvents) {
                                val nowPaused = !paused.value
                                paused.value = nowPaused
                                ctx.log(
                                    if (nowPaused) {
                                        "Teleprompter: paused by tap"
                                    } else {
                                        "Teleprompter: resumed by tap"
                                    }
                                )
                            }
                        }
                        GlassesEvent.LongPress -> {
                            if (caps.supportsLongPressEvents) {
                                stopped.value = true
                                ctx.log("Teleprompter: stopped by long press")
                            }
                        }
                        is GlassesEvent.Log -> ctx.log("Device: ${event.message}")
                        is GlassesEvent.Warning -> ctx.log("Device warning: ${event.message}")
                    }
                }
            }
        } else {
            ctx.log("Teleprompter: tap/long-press controls unavailable on this device; auto-advancing only.")
            null
        }

        try {
            val chunks = script.chunked(CHUNK_CHARS)
            if (caps.supportsStreamingTextUpdates) {
                runStreaming(ctx, chunks, delayMs, paused, stopped)
            } else {
                runPaged(ctx, chunks, delayMs, paused, stopped)
            }

            val ending = if (stopped.value) "Teleprompter stopped." else "Teleprompter complete."
            client.display(ending, DisplayOptions(mode = DisplayMode.REPLACE, force = true))
            ctx.log("Teleprompter: done")
            Result.success(Unit)
        } catch (error: Throwable) {
            Result.failure(error)
        } finally {
            eventsJob?.cancelAndJoin()
        }
    }

    private suspend fun runStreaming(
        ctx: UniversalAppContext,
        chunks: List<String>,
        delayMs: Long,
        paused: MutableStateFlow<Boolean>,
        stopped: MutableStateFlow<Boolean>,
    ) {
        ctx.client.display("Teleprompter\n", DisplayOptions(mode = DisplayMode.REPLACE, force = true)).getOrThrow()
        chunks.forEachIndexed { index, chunk ->
            waitIfPaused(paused, stopped)
            if (stopped.value) return
            val mode = if (index == 0) DisplayMode.REPLACE else DisplayMode.APPEND
            ctx.client.display(chunk, DisplayOptions(mode = mode, force = true)).getOrThrow()
            ctx.log("Teleprompter: streamed chunk ${index + 1}/${chunks.size}")
            waitAdvance(delayMs, paused, stopped)
        }
    }

    private suspend fun runPaged(
        ctx: UniversalAppContext,
        pages: List<String>,
        delayMs: Long,
        paused: MutableStateFlow<Boolean>,
        stopped: MutableStateFlow<Boolean>,
    ) {
        pages.forEachIndexed { index, page ->
            waitIfPaused(paused, stopped)
            if (stopped.value) return
            val text = "Teleprompter ${index + 1}/${pages.size}\n\n$page"
            ctx.client.display(text, DisplayOptions(mode = DisplayMode.REPLACE, force = true)).getOrThrow()
            ctx.log("Teleprompter: displayed page ${index + 1}/${pages.size}")
            waitAdvance(delayMs, paused, stopped)
        }
    }

    private suspend fun waitAdvance(
        totalMs: Long,
        paused: MutableStateFlow<Boolean>,
        stopped: MutableStateFlow<Boolean>,
    ) {
        var remaining = totalMs
        while (remaining > 0 && !stopped.value) {
            currentCoroutineContext().ensureActive()
            waitIfPaused(paused, stopped)
            val step = minOf(100L, remaining)
            delay(step)
            remaining -= step
        }
    }

    private suspend fun waitIfPaused(
        paused: MutableStateFlow<Boolean>,
        stopped: MutableStateFlow<Boolean>,
    ) {
        while (paused.value && !stopped.value) {
            currentCoroutineContext().ensureActive()
            delay(100L)
        }
    }

    private fun parseSeconds(raw: String?): Int {
        return raw?.toIntOrNull()?.coerceIn(1, 10) ?: 2
    }
}

private const val KEY_SCRIPT = "prompt_script"
private const val KEY_SECONDS_PER_CHUNK = "seconds_per_chunk"
private const val CHUNK_CHARS = 180
private const val LOG_PREVIEW_CHARS = 1_000

private const val DEFAULT_SCRIPT =
    "Welcome to the xg.glass teleprompter demo. This example keeps text flowing on the glasses " +
        "display while the speaker keeps eye contact. Tap once to pause when you need to breathe. " +
        "Tap again to resume. On devices with a long-press event, hold the action button to stop " +
        "the script early. The simulator exposes the same controls with Simulate Tap and Simulate " +
        "Long-press, so you can build and rehearse the interaction without hardware. When a device " +
        "supports streaming text updates, the app appends chunks. Otherwise it falls back to clean " +
        "paged replacement so display-only devices still work."
