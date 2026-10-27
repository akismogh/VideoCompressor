package com.akismogh.videocompressor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Foreground service (type mediaProjection, plus microphone while the mic is used)
 * that owns a screen-capture session. Android asks for consent per MediaProjection,
 * so the session is kept between takes and only ended after [IDLE_TIMEOUT_MS]
 * without recording, from the notification, or when sharing is stopped from the
 * status bar. Finished takes reach the web app through [RecState.pendingMedia].
 */
class RecordService : Service() {

    companion object {
        const val ACTION_START_SESSION = "start_session"
        const val ACTION_RECORD = "record"
        const val ACTION_STOP = "stop"
        const val ACTION_END = "end"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_DEVICE_AUDIO = "device_audio"
        const val EXTRA_MIC = "mic"
        const val EXTRA_DPI = "dpi"
        const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L
        private const val CHANNEL_RECORDING = "recording"
        private const val CHANNEL_DONE = "recording_done"
        private const val NOTIF_SESSION = 1
        const val NOTIF_DONE = 2

        /** Records again from the live session (no consent prompt). */
        fun record(ctx: Context, deviceAudio: Boolean, mic: Boolean) {
            ctx.startService(Intent(ctx, RecordService::class.java)
                .setAction(ACTION_RECORD)
                .putExtra(EXTRA_DEVICE_AUDIO, deviceAudio)
                .putExtra(EXTRA_MIC, mic))
        }

        fun stop(ctx: Context) {
            if (RecState.phase == RecState.Phase.RECORDING) send(ctx, ACTION_STOP)
        }

        fun end(ctx: Context) {
            if (RecState.sessionActive) send(ctx, ACTION_END)
        }

        private fun send(ctx: Context, action: String) {
            ctx.startService(Intent(ctx, RecordService::class.java).setAction(action))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var recorder: ScreenRecorder? = null
    private var takeActive = false
    private var finishing = false
    private var endAfterTake = false
    private val idleTimeout = Runnable { endSession() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> startSession(intent)
            ACTION_RECORD -> {
                if (recorder == null) {
                    // The session is gone (e.g. the service was restarted): ask again.
                    RecState.sessionActive = false
                    fail("Screen sharing ended. Tap Record again.")
                } else startTake(intent)
            }
            ACTION_STOP -> finishTake()
            ACTION_END -> endSession()
            else -> if (recorder == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startSession(intent: Intent) {
        if (recorder != null) return startTake(intent)
        goForeground(withMic = wantsMic(intent))

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)

        val mp = try {
            data?.let { getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, it) }
        } catch (e: Exception) { null }
        if (mp == null) return fail("Couldn't start screen capture.")

        mp.registerCallback(object : MediaProjection.Callback() {
            // Fired when sharing is stopped from the status bar chip or the system.
            override fun onStop() { main.post { onProjectionStopped() } }
        }, main)
        projection = mp
        recorder = ScreenRecorder(this, mp, intent.getIntExtra(EXTRA_DPI, 420))
        RecState.sessionActive = true
        startTake(intent)
    }

    private fun startTake(intent: Intent) {
        val rec = recorder ?: return
        if (takeActive) return
        main.removeCallbacks(idleTimeout)
        RecState.sessionExpiresAt = 0L

        val micOk = wantsMic(intent)
        goForeground(withMic = micOk)
        try {
            rec.startTake(
                deviceAudio = intent.getBooleanExtra(EXTRA_DEVICE_AUDIO, true) && hasAudioPermission(),
                mic = micOk,
                dir = MediaFiles.inputDir(this),
                baseName = MediaFiles.newRecordingBase(this),
            )
        } catch (e: Exception) {
            Log.e("RecordService", "recorder failed to start", e)
            endSession()
            return fail("Couldn't start the recorder. Tap Record again.")
        }
        takeActive = true
        RecState.startedAt = System.currentTimeMillis()
        RecState.phase = RecState.Phase.RECORDING
        RecState.changed()
        notifySession()
    }

    private fun finishTake() {
        val rec = recorder ?: return
        if (!takeActive || finishing) return
        finishing = true
        RecState.phase = RecState.Phase.FINISHING
        RecState.changed()

        Thread {
            val file = try { rec.stopTake() } catch (e: Exception) {
                Log.e("RecordService", "recorder failed to stop", e)
                null
            }
            main.post {
                takeActive = false
                finishing = false
                if (file != null) {
                    RecState.pendingMedia = MediaFiles.describe(file)
                } else {
                    RecState.error = "The recording came out empty. Try again."
                }
                RecState.phase = RecState.Phase.IDLE
                if (file != null && !RecState.activityResumed) doneNotification()
                if (endAfterTake || projection == null) {
                    endSession()
                } else {
                    RecState.sessionExpiresAt = SystemClock.elapsedRealtime() + IDLE_TIMEOUT_MS
                    main.postDelayed(idleTimeout, IDLE_TIMEOUT_MS)
                    goForeground(withMic = false) // drop the mic until the next take
                    notifySession()
                }
                RecState.changed()
            }
        }.start()
    }

    private fun onProjectionStopped() {
        projection = null
        if (takeActive) {
            endAfterTake = true
            finishTake()
        } else endSession()
    }

    private fun endSession() {
        main.removeCallbacks(idleTimeout)
        if (takeActive) {
            endAfterTake = true
            finishTake()
            return
        }
        recorder?.release()
        recorder = null
        val mp = projection
        projection = null
        try { mp?.stop() } catch (_: Exception) {}
        endAfterTake = false
        RecState.sessionActive = false
        RecState.sessionExpiresAt = 0L
        if (RecState.phase != RecState.Phase.IDLE) RecState.phase = RecState.Phase.IDLE
        RecState.changed()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(message: String) {
        RecState.error = message
        RecState.phase = RecState.Phase.IDLE
        RecState.changed()
        if (recorder == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun hasAudioPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun wantsMic(intent: Intent) = intent.getBooleanExtra(EXTRA_MIC, false) && hasAudioPermission()

    /** (Re)declares the foreground types; the mic type is only held while the mic records. */
    private fun goForeground(withMic: Boolean) {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (withMic) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(NOTIF_SESSION, sessionNotification(), types)
    }

    // ── Notifications ────────────────────────────────────────────────────────

    private fun openAppIntent(action: String?): PendingIntent =
        PendingIntent.getActivity(this, if (action == null) 0 else 1,
            Intent(this, MainActivity::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notifySession() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_SESSION, sessionNotification())
    }

    private fun sessionNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, "Screen recording", NotificationManager.IMPORTANCE_LOW))
        val b = Notification.Builder(this, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentIntent(openAppIntent(null))
            .setOngoing(true)
        if (RecState.phase == RecState.Phase.RECORDING) {
            b.setContentTitle("Recording screen")
                .setContentText("Tap Stop to trim & compress")
                .setUsesChronometer(true).setWhen(RecState.startedAt).setShowWhen(true)
                .addAction(Notification.Action.Builder(null, "Stop",
                    openAppIntent(MainActivity.ACTION_STOP_RECORDING)).build())
        } else {
            val endPi = PendingIntent.getService(this, 2,
                Intent(this, RecordService::class.java).setAction(ACTION_END), PendingIntent.FLAG_IMMUTABLE)
            b.setContentTitle("Ready to record again")
                .setContentText("No permission prompt for 10 minutes")
                .addAction(Notification.Action.Builder(null, "End", endPi).build())
        }
        return b.build()
    }

    private fun doneNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Recording finished", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(NOTIF_DONE, Notification.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Recording ready")
            .setContentText("Tap to trim, compress and share")
            .setContentIntent(openAppIntent(null))
            .setAutoCancel(true)
            .build())
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App swiped away: keep a running take going, but don't hold an idle session open.
        if (!takeActive) endSession()
    }

    override fun onDestroy() {
        main.removeCallbacks(idleTimeout)
        recorder?.let { rec ->
            // Killed mid-take: finalize what we have rather than leave a broken file.
            if (takeActive) Thread { try { rec.stopTake() } catch (_: Exception) {}; rec.release() }.start()
            else rec.release()
        }
        recorder = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        RecState.sessionActive = false
        RecState.sessionExpiresAt = 0L
        if (RecState.phase != RecState.Phase.IDLE) {
            RecState.phase = RecState.Phase.IDLE
            RecState.changed()
        }
        super.onDestroy()
    }
}
