package com.example.xgglass.aiassistant

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.xgglass.core.CaptureOptions
import com.xgglass.core.CapturedImage
import com.xgglass.core.ConnectionState
import com.xgglass.core.DisplayOptions
import com.xgglass.core.GlassesClient
import com.xgglass.core.GlassesEvent
import com.xgglass.core.PhotoQuality
import com.xgglass.core.VideoFrame
import com.xgglass.core.VideoFrameRateTier
import com.xgglass.core.VideoStreamOptions
import com.xgglass.core.VideoStreamSession
import com.xgglass.device.sim.SimulatorGlassesClient
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var statusView: TextView
    private lateinit var displayView: TextView
    private lateinit var logView: TextView
    private lateinit var previewView: ImageView
    private lateinit var connectButton: Button
    private lateinit var askButton: Button
    private lateinit var tapButton: Button
    private lateinit var stopButton: Button

    private var client: GlassesClient? = null
    private var simulatorClient: SimulatorGlassesClient? = null
    private var eventsJob: Job? = null
    private var stateJob: Job? = null
    private var framesJob: Job? = null
    private var streamSession: VideoStreamSession? = null
    private var askingJob: Job? = null

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            connectAndStart()
        } else {
            appendLog("ai_assistant: CAMERA permission denied; local video still works if configured.")
            statusView.text = "Camera permission denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())

        connectButton.setOnClickListener { ensureCameraThenConnect() }
        askButton.setOnClickListener { askFromCurrentView("phone button") }
        tapButton.setOnClickListener { simulatorClient?.simulateTap(1) ?: appendLog("ai_assistant: simulator is not connected") }
        stopButton.setOnClickListener { disconnectCurrent() }

        askButton.isEnabled = false
        tapButton.isEnabled = false
        stopButton.visibility = View.GONE

        appendLog("ai_assistant: config baseUrl=${BuildConfig.AI_BASE_URL.ifBlank { "<missing>" }} model=${BuildConfig.AI_MODEL}")
        ensureCameraThenConnect()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createContentView(): ScrollView {
        statusView = TextView(this).apply {
            text = "Disconnected"
            textSize = 18f
            setPadding(32, 28, 32, 12)
        }
        displayView = TextView(this).apply {
            text = "Glasses display"
            textSize = 18f
            setPadding(32, 20, 32, 20)
        }
        previewView = ImageView(this).apply {
            adjustViewBounds = true
            minimumHeight = 260
            setBackgroundColor(0xff202124.toInt())
        }
        connectButton = Button(this).apply { text = "Connect + Stream" }
        askButton = Button(this).apply { text = "Ask From Snapshot" }
        tapButton = Button(this).apply { text = "Simulate Tap" }
        stopButton = Button(this).apply { text = "Disconnect" }
        logView = TextView(this).apply {
            textSize = 13f
            setPadding(32, 16, 32, 32)
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(connectButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(askButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val secondaryButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tapButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(stopButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusView)
            addView(displayView)
            addView(previewView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                360,
            ))
            addView(buttons)
            addView(secondaryButtons)
            addView(logView)
        }
        return ScrollView(this).apply { addView(content) }
    }

    private fun ensureCameraThenConnect() {
        if (BuildConfig.XG_SIM_VIDEO_PATH.isNotBlank()) {
            connectAndStart()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            connectAndStart()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun connectAndStart() {
        scope.launch {
            connectButton.isEnabled = false
            askButton.isEnabled = false
            tapButton.isEnabled = false
            statusView.text = "Connecting..."
            disconnectCurrentNow()

            val sim = SimulatorGlassesClient(
                activity = this@MainActivity,
                displaySink = { text ->
                    displayView.text = text
                },
                videoPath = BuildConfig.XG_SIM_VIDEO_PATH.takeIf { it.isNotBlank() },
            )
            client = sim
            simulatorClient = sim

            stateJob = scope.launch {
                sim.state.collectLatest { state ->
                    statusView.text = "State: $state"
                    val connected = state is ConnectionState.Connected
                    askButton.isEnabled = connected
                    tapButton.isEnabled = connected
                    stopButton.visibility = if (connected) View.VISIBLE else View.GONE
                }
            }

            eventsJob = scope.launch {
                sim.events.collect { event ->
                    when (event) {
                        is GlassesEvent.Log -> appendLog(event.message)
                        is GlassesEvent.Warning -> appendLog("WARN: ${event.message}")
                        is GlassesEvent.Tap -> {
                            appendLog("ai_assistant: tap ${event.count}")
                            askFromCurrentView("tap")
                        }
                        is GlassesEvent.BatteryLevel -> appendLog("ai_assistant: battery ${event.percent}%")
                        GlassesEvent.LongPress -> appendLog("ai_assistant: long press")
                    }
                }
            }

            val result = sim.connect()
            appendLog("ai_assistant: connect => ${result.isSuccess} ${result.exceptionOrNull()?.message.orEmpty()}")
            if (result.isSuccess) {
                startAwarenessStream(sim)
            } else {
                connectButton.isEnabled = true
            }
        }
    }

    private suspend fun startAwarenessStream(activeClient: GlassesClient) {
        val caps = activeClient.capabilities
        if (!caps.canStreamVideo) {
            val message = "ai_assistant: video stream unavailable; falling back to capturePhoto snapshots."
            appendLog(message)
            displayOnGlasses(message)
            connectButton.isEnabled = true
            return
        }

        val result = activeClient.startVideoStream(
            VideoStreamOptions(
                frameRateTier = VideoFrameRateTier.LOW,
                preferredWidth = 640,
                preferredHeight = 480,
            ),
        )
        val session = result.getOrElse { error ->
            val message = "ai_assistant: startVideoStream failed (${error.message}); falling back to capturePhoto snapshots."
            appendLog(message)
            displayOnGlasses(message)
            connectButton.isEnabled = true
            return
        }

        streamSession = session
        appendLog("ai_assistant: stream started format=${session.format}")
        displayOnGlasses("AI assistant ready\nTap or press Ask.")
        framesJob = scope.launch {
            var sawFrame = false
            session.frames.collect { frame ->
                if (frame.endOfStream) {
                    appendLog("ai_assistant: stream ended")
                    return@collect
                }
                if (!sawFrame) {
                    sawFrame = true
                    appendLog("ai_assistant: stream frame ${frame.sequence} ${frame.bytes.size} bytes ${frame.format}")
                }
                showPreview(frame)
            }
        }
        connectButton.isEnabled = true
    }

    private fun askFromCurrentView(trigger: String) {
        if (askingJob?.isActive == true) {
            appendLog("ai_assistant: ask already running")
            return
        }
        val activeClient = client ?: run {
            appendLog("ai_assistant: not connected")
            return
        }
        askingJob = scope.launch {
            askButton.isEnabled = false
            tapButton.isEnabled = false
            try {
                val config = AiConfig.fromBuildConfig()
                if (!config.isConfigured) {
                    val message = "ai_assistant: AI settings missing; set ai.baseUrl, ai.apiKey, and ai.model in local.properties."
                    appendLog(message)
                    displayOnGlasses("AI endpoint not configured")
                    return@launch
                }

                appendLog("ai_assistant: snapshot requested by $trigger")
                displayOnGlasses("Looking...")

                // During an active video stream, capturePhoto() returns the latest stream frame.
                // That is the unified semantic this demo showcases; when streaming is unavailable,
                // the same call falls back to an ordinary still capture.
                val image = activeClient.capturePhoto(
                    CaptureOptions(
                        photoQuality = PhotoQuality.LOW,
                        targetWidth = 640,
                        targetHeight = 480,
                    ),
                ).getOrElse { error ->
                    val message = "ai_assistant: capture failed: ${error.message ?: error.javaClass.simpleName}"
                    appendLog(message)
                    displayOnGlasses("Capture failed")
                    return@launch
                }

                showPreview(image)
                appendLog("ai_assistant: captured ${image.jpegBytes.size} bytes source=${image.sourceModel}")
                displayOnGlasses("Thinking...")

                val answer = withContext(Dispatchers.IO) {
                    callVisionModel(config, image.jpegBytes)
                }.ifBlank { "No answer" }

                displayOnGlasses(answer)
                val marker = "ai_assistant: answer ${answer.length} chars"
                appendLog(marker)
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Exception) {
                val message = "ai_assistant: failed: ${error.message ?: error.javaClass.simpleName}"
                appendLog(message)
                displayOnGlasses("AI request failed")
            } finally {
                askButton.isEnabled = client != null
                tapButton.isEnabled = simulatorClient != null
            }
        }
    }

    private suspend fun displayOnGlasses(text: String) {
        val activeClient = client ?: return
        if (!activeClient.capabilities.canDisplayText) {
            appendLog("ai_assistant: display unavailable; answer is phone-log only.")
            return
        }
        activeClient.display(text, DisplayOptions(force = true)).getOrElse { error ->
            appendLog("ai_assistant: display failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun showPreview(image: CapturedImage) {
        val bytes = image.jpegBytes
        showPreviewBytes(bytes)
    }

    private fun showPreview(frame: VideoFrame) {
        showPreviewBytes(frame.bytes)
    }

    private fun showPreviewBytes(bytes: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        previewView.setImageBitmap(bitmap)
    }

    private fun callVisionModel(config: AiConfig, jpegBytes: ByteArray): String {
        val endpoint = "${config.baseUrl.trimEnd('/')}/chat/completions"
        val dataUrl = "data:image/jpeg;base64,${Base64.encodeToString(jpegBytes, Base64.NO_WRAP)}"
        val body = JSONObject()
            .put("model", config.model)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", "You answer questions about the live camera view. Be concise."),
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                JSONArray()
                                    .put(JSONObject().put("type", "text").put("text", USER_PROMPT))
                                    .put(
                                        JSONObject()
                                            .put("type", "image_url")
                                            .put("image_url", JSONObject().put("url", dataUrl)),
                                    ),
                            ),
                    ),
            )
            .put("max_tokens", 180)
            .toString()

        appendLog("ai_assistant: POST $endpoint imageBytes=${jpegBytes.size}")
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 45_000
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            setRequestProperty("Content-Type", "application/json")
        }

        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
        val code = connection.responseCode
        val responseText = readResponse(connection, code)
        connection.disconnect()
        if (code !in 200..299) {
            error("HTTP $code: ${responseText.take(300)}")
        }
        return parseAssistantContent(responseText)
    }

    private fun readResponse(connection: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        return stream?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
        }.orEmpty()
    }

    private fun parseAssistantContent(body: String): String {
        val choices = JSONObject(body).optJSONArray("choices") ?: return ""
        if (choices.length() == 0) return ""
        val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return ""
        val rawContent = message.opt("content") ?: return ""
        return when (rawContent) {
            is String -> rawContent.trim()
            is JSONArray -> buildString {
                for (index in 0 until rawContent.length()) {
                    val part = rawContent.optJSONObject(index) ?: continue
                    if (part.optString("type") == "text") append(part.optString("text"))
                }
            }.trim()
            else -> rawContent.toString().trim()
        }
    }

    private fun disconnectCurrent() {
        scope.launch {
            disconnectCurrentNow()
        }
    }

    private suspend fun disconnectCurrentNow() {
        askingJob?.cancel()
        framesJob?.cancel()
        eventsJob?.cancel()
        stateJob?.cancel()
        askingJob = null
        framesJob = null
        eventsJob = null
        stateJob = null
        try {
            streamSession?.stop()
        } catch (error: Exception) {
            appendLog("ai_assistant: stream stop failed: ${error.message ?: error.javaClass.simpleName}")
        }
        streamSession = null
        try {
            client?.disconnect()
        } catch (error: Exception) {
            appendLog("ai_assistant: disconnect failed: ${error.message ?: error.javaClass.simpleName}")
        }
        client = null
        simulatorClient = null
        askButton.isEnabled = false
        tapButton.isEnabled = false
        stopButton.visibility = View.GONE
        statusView.text = "Disconnected"
    }

    private fun appendLog(message: String) {
        Log.i(TAG, message)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            logView.append("$message\n")
        } else {
            runOnUiThread { logView.append("$message\n") }
        }
    }

    private data class AiConfig(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
    ) {
        val isConfigured: Boolean = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

        companion object {
            fun fromBuildConfig(): AiConfig = AiConfig(
                baseUrl = BuildConfig.AI_BASE_URL,
                apiKey = BuildConfig.AI_API_KEY,
                model = BuildConfig.AI_MODEL,
            )
        }
    }

    private companion object {
        private const val TAG = "XG_AI_ASSISTANT"
        private const val USER_PROMPT =
            "Look at the image from my smart-glasses camera. Answer what I likely need to know in one or two sentences."
    }
}
