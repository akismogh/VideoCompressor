package com.akismogh.videocompressor

import android.os.Handler
import android.os.Looper

/**
 * Process-wide recording state shared by [RecordService] and the web bridge in
 * [MainActivity]. The page pulls it (getRecordingState / takePendingMedia) whenever
 * the activity pokes it with a `vc-native` event.
 */
object RecState {
    enum class Phase(val js: String) {
        IDLE("idle"), STARTING("starting"), RECORDING("recording"), FINISHING("finishing")
    }

    @Volatile var phase = Phase.IDLE
    @Volatile var startedAt = 0L

    /** A consented capture session is alive, so recording again needs no prompt. */
    @Volatile var sessionActive = false
    /** elapsedRealtime() at which the idle session ends; 0 while recording or with no session. */
    @Volatile var sessionExpiresAt = 0L
    @Volatile var error: String? = null
    /** A picked / shared / filmed video is being copied into place. */
    @Volatile var importing = false

    /** JSON {url, name, mime} of a finished recording or shared video, waiting for the page. */
    @Volatile var pendingMedia: String? = null

    @Volatile var activityResumed = false
    @Volatile var listener: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    fun changed() {
        main.post { listener?.invoke() }
    }
}
