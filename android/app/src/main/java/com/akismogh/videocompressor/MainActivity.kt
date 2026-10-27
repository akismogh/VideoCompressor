package com.akismogh.videocompressor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * WebView shell for the Video Compressor web app (bundled into assets by
 * build-apk.ps1) plus the native pieces a browser can't do on Android:
 *   • screen recording via MediaProjection ([RecordService])
 *   • receiving videos from the system share sheet
 *   • saving the compressed result to the gallery and sharing it
 * The page talks to this through the `VCAndroid` JS interface and is poked with a
 * `vc-native` window event whenever native state changes.
 */
class MainActivity : Activity() {

    companion object {
        const val ACTION_STOP_RECORDING = "com.akismogh.videocompressor.STOP_RECORDING"
        private const val REQ_CAPTURE = 1
        private const val REQ_PERMS = 2
        private const val REQ_FILE = 3
    }

    private lateinit var webView: WebView
    private var wantAudio = true
    private var leaveWhenRecording = false
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private var outFile: File? = null
    private var outStream: FileOutputStream? = null
    private var lastSaved: Uri? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(true)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/media/", WebViewAssetLoader.InternalStoragePathHandler(this, MediaFiles.inputDir(this)))
            .build()

        val root = FrameLayout(this)
        webView = WebView(this)
        root.addView(webView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(root)
        applySystemBars(root)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
        }
        webView.addJavascriptInterface(Bridge(), "VCAndroid")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.toString().startsWith(MediaFiles.HOST)) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, url)) } catch (_: Exception) {}
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                Log.d("WebViewConsole", "${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
                return true
            }

            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams,
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(params.createIntent(), REQ_FILE)
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }
        }

        RecState.listener = { onNativeStateChanged() }
        handleIntent(intent)
        webView.loadUrl("${MediaFiles.HOST}/assets/index.html")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        RecState.activityResumed = true
        getSystemService(NotificationManager::class.java).cancel(RecordService.NOTIF_DONE)
        pokePage()
    }

    override fun onPause() {
        RecState.activityResumed = false
        super.onPause()
    }

    override fun onDestroy() {
        RecState.listener = null
        webView.destroy()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_STOP_RECORDING -> RecordService.stop(this)
            Intent.ACTION_SEND -> {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
                if (uri != null) importShared(uri)
            }
        }
    }

    private fun importShared(uri: Uri) {
        Thread {
            try {
                RecState.pendingMedia = MediaFiles.importShared(this, uri)
            } catch (e: Exception) {
                Log.e("MainActivity", "import failed", e)
                RecState.error = "Couldn't open the shared video."
            }
            RecState.changed()
        }.start()
    }

    private fun onNativeStateChanged() {
        if (leaveWhenRecording && RecState.phase == RecState.Phase.RECORDING) {
            // Get out of the way so the user lands on what they want to record.
            leaveWhenRecording = false
            moveTaskToBack(true)
        } else if (RecState.phase == RecState.Phase.IDLE) {
            leaveWhenRecording = false
        }
        pokePage()
    }

    private fun pokePage() {
        if (::webView.isInitialized) {
            webView.evaluateJavascript("window.dispatchEvent(new Event('vc-native'))", null)
        }
    }

    // ── Recording flow ───────────────────────────────────────────────────────

    private fun beginRecording(withAudio: Boolean) {
        if (RecState.phase != RecState.Phase.IDLE) return
        wantAudio = withAudio
        val need = mutableListOf<String>()
        if (withAudio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isEmpty()) requestCapture() else requestPermissions(need.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code != REQ_PERMS) return
        if (wantAudio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Recording without device sound (audio permission was denied)")
            wantAudio = false
        }
        requestCapture()
    }

    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // Whole screen only: single-app sharing would go black once the user switches apps.
        val intent = if (Build.VERSION.SDK_INT >= 34)
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        RecState.phase = RecState.Phase.STARTING
        RecState.changed()
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_FILE -> {
                filePathCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
                filePathCallback = null
            }
            REQ_CAPTURE -> {
                if (resultCode != RESULT_OK || data == null) {
                    RecState.phase = RecState.Phase.IDLE
                    RecState.changed()
                    return
                }
                leaveWhenRecording = true
                startForegroundService(Intent(this, RecordService::class.java)
                    .setAction(RecordService.ACTION_START)
                    .putExtra(RecordService.EXTRA_CODE, resultCode)
                    .putExtra(RecordService.EXTRA_DATA, data)
                    .putExtra(RecordService.EXTRA_AUDIO, wantAudio)
                    .putExtra(RecordService.EXTRA_DPI, resources.displayMetrics.densityDpi))
            }
        }
    }

    // ── Save / share the compressed result ───────────────────────────────────

    private fun shareUri(uri: Uri, name: String) {
        // ClipData lets the share sheet itself read the file, so its preview shows the real name.
        val clip = ClipData.newUri(contentResolver, name, uri)
        val send = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = clip
        val chooser = Intent.createChooser(send, null)
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, MainActivity::class.java)))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        chooser.clipData = clip
        startActivity(chooser)
    }

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    // ── System bars ──────────────────────────────────────────────────────────

    private fun applySystemBars(root: FrameLayout) {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val bg = Color.parseColor(if (dark) "#020617" else "#F8FAFC")
        root.setBackgroundColor(bg)
        webView.setBackgroundColor(bg)
        if (!dark) {
            window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
    }

    // ── JS bridge ────────────────────────────────────────────────────────────

    inner class Bridge {
        @JavascriptInterface
        fun getRecordingState(): String = JSONObject()
            .put("phase", RecState.phase.js)
            .put("startedAt", RecState.startedAt)
            .toString()

        @JavascriptInterface
        fun takePendingMedia(): String {
            val media = RecState.pendingMedia
            RecState.pendingMedia = null
            return media ?: ""
        }

        @JavascriptInterface
        fun takeError(): String {
            val err = RecState.error
            RecState.error = null
            return err ?: ""
        }

        @JavascriptInterface
        fun startRecording(withAudio: Boolean) = runOnUiThread { beginRecording(withAudio) }

        @JavascriptInterface
        fun stopRecording() = RecordService.stop(this@MainActivity)

        @JavascriptInterface
        fun beginOutput(name: String) {
            outStream?.close()
            lastSaved = null
            val file = MediaFiles.newOutputFile(this@MainActivity, name)
            outFile = file
            outStream = FileOutputStream(file)
        }

        @JavascriptInterface
        fun appendOutput(base64: String) {
            outStream?.write(Base64.decode(base64, Base64.DEFAULT))
        }

        /** Publishes the uploaded file to the gallery; optionally opens the share sheet. */
        @JavascriptInterface
        fun finishOutput(share: Boolean): Boolean {
            val file = outFile ?: return false
            return try {
                outStream?.close()
                outStream = null
                val uri = MediaFiles.saveToGallery(this@MainActivity, file)
                lastSaved = uri
                toast("Saved to ${MediaFiles.SAVE_FOLDER}")
                if (share) runOnUiThread { shareUri(uri, file.name) }
                true
            } catch (e: Exception) {
                Log.e("MainActivity", "save failed", e)
                toast("Couldn't save the video: ${e.message}")
                false
            }
        }

        @JavascriptInterface
        fun shareSaved(): Boolean {
            val uri = lastSaved ?: return false
            val name = outFile?.name ?: "compressed.mp4"
            runOnUiThread { shareUri(uri, name) }
            return true
        }
    }
}
