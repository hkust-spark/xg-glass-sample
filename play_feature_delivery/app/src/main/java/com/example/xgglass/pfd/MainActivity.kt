package com.example.xgglass.pfd

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.play.core.splitcompat.SplitCompat
import com.google.android.play.core.splitinstall.SplitInstallManager
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallSessionState
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import com.xgglass.core.GlassesClient
import kotlinx.coroutines.flow.emptyFlow

class MainActivity : AppCompatActivity() {
    private lateinit var splitInstallManager: SplitInstallManager
    private lateinit var logView: TextView
    private var activeSessionId: Int? = null

    private val splitInstallListener = SplitInstallStateUpdatedListener { state ->
        if (activeSessionId == null || activeSessionId == state.sessionId()) {
            onSplitInstallState(state)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SplitCompat.install(this)
        splitInstallManager = SplitInstallManagerFactory.create(this)
        setContentView(createContentView())
        splitInstallManager.registerListener(splitInstallListener)

        appendLog("Base app started; installedModules=${splitInstallManager.installedModules.sorted()}")
        requestMetaAdapter()
    }

    override fun onDestroy() {
        splitInstallManager.unregisterListener(splitInstallListener)
        super.onDestroy()
    }

    private fun createContentView(): ScrollView {
        logView = TextView(this).apply {
            textSize = 14f
            setPadding(32, 24, 32, 24)
        }
        val loadButton = Button(this).apply {
            text = getString(R.string.load_meta_button)
            setOnClickListener { requestMetaAdapter() }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(loadButton)
            addView(logView)
        }
        return ScrollView(this).apply {
            addView(content)
        }
    }

    private fun requestMetaAdapter() {
        val adapter = OptionalAdapter.META
        if (splitInstallManager.installedModules.contains(adapter.moduleName)) {
            appendLog("${adapter.moduleName} feature already installed")
            instantiateAdapter(adapter)
            return
        }

        appendLog("Requesting ${adapter.moduleName} feature install")
        val request = SplitInstallRequest.newBuilder()
            .addModule(adapter.moduleName)
            .build()

        splitInstallManager.startInstall(request)
            .addOnSuccessListener { sessionId ->
                activeSessionId = sessionId
                appendLog("Split install requested; session=$sessionId")
            }
            .addOnFailureListener { error ->
                appendLog("Split install failed: ${error.javaClass.simpleName}: ${error.message}")
            }
    }

    private fun onSplitInstallState(state: SplitInstallSessionState) {
        appendLog(
            "Split session ${state.sessionId()} ${statusName(state.status())} " +
                "modules=${state.moduleNames()} bytes=${state.bytesDownloaded()}/${state.totalBytesToDownload()}",
        )

        when (state.status()) {
            SplitInstallSessionStatus.INSTALLED -> {
                SplitCompat.install(this)
                appendLog("Meta feature module installed")
                instantiateAdapter(OptionalAdapter.META)
            }
            SplitInstallSessionStatus.FAILED -> {
                appendLog("Meta feature install failed with errorCode=${state.errorCode()}")
            }
            SplitInstallSessionStatus.CANCELED -> {
                appendLog("Meta feature install canceled")
            }
        }
    }

    private fun instantiateAdapter(adapter: OptionalAdapter) {
        runCatching {
            createInstalledAdapter(adapter)
        }.onSuccess { client ->
            appendLog("Meta adapter class instantiated: ${client.javaClass.name}; model=${client.model}")
        }.onFailure { error ->
            appendLog("Meta adapter instantiation failed: ${rootCause(error)}")
        }
    }

    private fun createInstalledAdapter(adapter: OptionalAdapter): GlassesClient {
        SplitCompat.install(this)
        val clazz = Class.forName(adapter.className)
        val externalActivityBridgeClass = Class.forName("com.xgglass.core.ExternalActivityBridge")
        val options = createMetaOptionsForLocalProof()
        return clazz.getConstructor(
            AppCompatActivity::class.java,
            externalActivityBridgeClass,
            options.javaClass,
        ).newInstance(this, null, options) as GlassesClient
    }

    private fun createMetaOptionsForLocalProof(): Any {
        val selectorInterface = Class.forName("com.meta.wearable.dat.core.selectors.DeviceSelector")
        val optionsClass =
            Class.forName("com.xgglass.device.meta.MetaWearablesGlassesClient\$MetaWearablesOptions")

        val selector = java.lang.reflect.Proxy.newProxyInstance(
            selectorInterface.classLoader,
            arrayOf(selectorInterface),
        ) { _, method, _ ->
            when (method.name) {
                "activeDevice" -> null
                "activeDeviceFlow" -> emptyFlow<Any?>()
                "toString" -> "LocalTestingDeviceSelector"
                "hashCode" -> 0
                "equals" -> false
                else -> null
            }
        }

        // Avoid DAT global state during local-testing construction; real apps can use the default selector.
        return optionsClass.getConstructor(
            selectorInterface,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
        ).newInstance(selector, 90_000L, 30_000L, 30_000L, 1_000L)
    }

    private fun appendLog(message: String) {
        Log.i(TAG, message)
        logView.append("$message\n")
    }

    private fun rootCause(error: Throwable): String {
        var current = error
        while (current.cause != null && current.cause !== current) {
            current = current.cause!!
        }
        return "${current.javaClass.simpleName}: ${current.message}"
    }

    private fun statusName(status: Int): String =
        when (status) {
            SplitInstallSessionStatus.PENDING -> "PENDING"
            SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION -> "REQUIRES_USER_CONFIRMATION"
            SplitInstallSessionStatus.DOWNLOADING -> "DOWNLOADING"
            SplitInstallSessionStatus.DOWNLOADED -> "DOWNLOADED"
            SplitInstallSessionStatus.INSTALLING -> "INSTALLING"
            SplitInstallSessionStatus.INSTALLED -> "INSTALLED"
            SplitInstallSessionStatus.FAILED -> "FAILED"
            SplitInstallSessionStatus.CANCELING -> "CANCELING"
            SplitInstallSessionStatus.CANCELED -> "CANCELED"
            else -> "UNKNOWN($status)"
        }

    private enum class OptionalAdapter(
        val moduleName: String,
        val className: String,
    ) {
        META(
            moduleName = "meta",
            className = "com.xgglass.device.meta.MetaWearablesGlassesClient",
        ),
    }

    private companion object {
        const val TAG = "XG_PFD_SAMPLE"
    }
}
