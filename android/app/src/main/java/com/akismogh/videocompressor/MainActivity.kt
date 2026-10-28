package com.akismogh.videocompressor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
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
import android.window.OnBackInvokedDispatcher
import androidx.core.content.FileProvider
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
        private const val REQ_CAMERA = 4
        private const val REQ_PICK = 5
        private const val STATE_CAMERA_FILE = "cameraFile"
    }

    private lateinit var webView: WebView
    private var wantDeviceAudio = true
    private var wantMic = false
    private var leaveWhenRecording = false
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraFile: File? = null

    private var outFile: File? = null
    private var outStream: FileOutputStream? = null
    private var lastSaved: Uri? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(true)
        // The system may recreate us while the camera app is in front.
        cameraFile = savedInstanceState?.getString(STATE_CAMERA_FILE)?.let(::File)

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

            override fun onPageFinished(view: WebView, url: String) = injectInsets()
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
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }
        handleIntent(intent)
        webView.loadUrl("${MediaFiles.HOST}/assets/index.html")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        cameraFile?.let { outState.putString(STATE_CAMERA_FILE, it.path) }
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
        RecState.importing = true
        RecState.changed()
        Thread {
            try {
                RecState.pendingMedia = MediaFiles.importShared(this, uri)
            } catch (e: Exception) {
                Log.e("MainActivity", "import failed", e)
                RecState.error = "Couldn't open that video."
            }
            RecState.importing = false
            RecState.changed()
        }.start()
    }

    /** Library: the system photo picker, limited to videos. */
    private fun pickVideo() {
        val intent = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).setType("video/*")
        } else {
            Intent(Intent.ACTION_GET_CONTENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PICK)
        } catch (e: ActivityNotFoundException) {
            toast("No photo picker found")
        }
    }

    // ── Back button: let the page step back through its screens first ───────

    private fun handleBack() {
        webView.evaluateJavascript("(window.__vcBack && window.__vcBack()) ? 'y' : 'n'") { result ->
            if (result != "\"y\"") finish()
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = handleBack()

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

    private fun beginRecording(deviceAudio: Boolean, mic: Boolean) {
        if (RecState.phase != RecState.Phase.IDLE) return
        wantDeviceAudio = deviceAudio
        wantMic = mic
        val need = mutableListOf<String>()
        if ((deviceAudio || mic) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isEmpty()) proceedToRecord() else requestPermissions(need.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code != REQ_PERMS) return
        if ((wantDeviceAudio || wantMic) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Recording without sound (audio permission was denied)")
            wantDeviceAudio = false
            wantMic = false
        }
        proceedToRecord()
    }

    /** Reuses the live capture session if there is one; otherwise asks for screen capture. */
    private fun proceedToRecord() {
        if (RecState.sessionActive) {
            leaveWhenRecording = true
            RecordService.record(this, wantDeviceAudio, wantMic)
        } else requestCapture()
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

    // ── Camera ───────────────────────────────────────────────────────────────

    /** Opens the camera app in video mode, writing straight into the input dir. */
    private fun openCamera() {
        val file = MediaFiles.newCameraFile(this)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        cameraFile = file
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_CAMERA)
        } catch (e: ActivityNotFoundException) {
            cameraFile = null
            toast("No camera app found")
        }
    }

    private fun onCameraResult(resultCode: Int, data: Intent?) {
        val file = cameraFile
        cameraFile = null
        if (resultCode != RESULT_OK) {
            file?.delete()
            return
        }
        when {
            file != null && file.length() > 0 -> {
                RecState.pendingMedia = MediaFiles.describe(file)
                RecState.changed()
            }
            // Some camera apps ignore EXTRA_OUTPUT and hand back their own uri instead.
            data?.data != null -> importShared(data.data!!)
            else -> toast("The camera didn't return a video")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_CAMERA -> onCameraResult(resultCode, data)
            REQ_PICK -> if (resultCode == RESULT_OK) data?.data?.let { importShared(it) }
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
                    .setAction(RecordService.ACTION_START_SESSION)
                    .putExtra(RecordService.EXTRA_CODE, resultCode)
                    .putExtra(RecordService.EXTRA_DATA, data)
                    .putExtra(RecordService.EXTRA_DEVICE_AUDIO, wantDeviceAudio)
                    .putExtra(RecordService.EXTRA_MIC, wantMic)
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

    /**
     * Always-dark UI drawn edge to edge: the page's gradient runs behind the status
     * and navigation bars, and their sizes are handed to it as --sat / --sab.
     */
    private fun applySystemBars(root: FrameLayout) {
        val bg = Color.parseColor("#4A4A48")
        root.setBackgroundColor(bg)
        webView.setBackgroundColor(bg)
        // Light (white) bar icons regardless of the system theme.
        window.insetsController?.setSystemBarsAppearance(0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val density = resources.displayMetrics.density
            barTopCss = (bars.top / density).toInt()
            barBottomCss = (bars.bottom / density).toInt()
            injectInsets()
            WindowInsets.CONSUMED
        }
    }

    private var barTopCss = 0
    private var barBottomCss = 0

    private fun injectInsets() {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript(
            "document.documentElement.style.setProperty('--sat','${barTopCss}px');" +
                "document.documentElement.style.setProperty('--sab','${barBottomCss}px');",
            null,
        )
    }

    // ── JS bridge ────────────────────────────────────────────────────────────

    inner class Bridge {
        @JavascriptInterface
        fun getRecordingState(): String {
            val expires = RecState.sessionExpiresAt
            return JSONObject()
                .put("phase", RecState.phase.js)
                .put("startedAt", RecState.startedAt)
                .put("sessionActive", RecState.sessionActive)
                .put("importing", RecState.importing)
                // Wall-clock time the idle session ends, for the page's countdown.
                .put("sessionEndsAt",
                    if (expires == 0L) 0L
                    else System.currentTimeMillis() + (expires - SystemClock.elapsedRealtime()))
                .toString()
        }

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
        fun startRecording(deviceAudio: Boolean, mic: Boolean) =
            runOnUiThread { beginRecording(deviceAudio, mic) }

        @JavascriptInterface
        fun stopRecording() = RecordService.stop(this@MainActivity)

        @JavascriptInterface
        fun endSession() = RecordService.end(this@MainActivity)

        @JavascriptInterface
        fun recordCamera() = runOnUiThread { openCamera() }

        @JavascriptInterface
        fun pickVideo() = runOnUiThread { this@MainActivity.pickVideo() }

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

        /** Opens the saved copy in the default video viewer (Photos). */
        @JavascriptInterface
        fun openSaved(): Boolean {
            val uri = lastSaved ?: return false
            runOnUiThread {
                val view = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    // Straight into Google Photos, skipping the "Open with" picker.
                    startActivity(Intent(view).setPackage("com.google.android.apps.photos"))
                } catch (e: ActivityNotFoundException) {
                    try { startActivity(view) } catch (e: ActivityNotFoundException) {
                        toast("No app found to play the video")
                    }
                }
            }
            return true
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
