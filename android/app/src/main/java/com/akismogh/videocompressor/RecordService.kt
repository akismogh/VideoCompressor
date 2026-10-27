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
import android.util.Log

/**
 * Foreground service (type mediaProjection) that owns one screen recording: it turns
 * the consent result into a MediaProjection, runs a [ScreenRecorder], and hands the
 * finished MP4 to the web app through [RecState.pendingMedia].
 */
class RecordService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_DPI = "dpi"
        private const val CHANNEL_RECORDING = "recording"
        private const val CHANNEL_DONE = "recording_done"
        private const val NOTIF_RECORDING = 1
        const val NOTIF_DONE = 2

        fun stop(ctx: Context) {
            if (RecState.phase == RecState.Phase.RECORDING) {
                ctx.startService(Intent(ctx, RecordService::class.java).setAction(ACTION_STOP))
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var recorder: ScreenRecorder? = null
    private var finishing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> finish()
            else -> if (recorder == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (recorder != null) return
        startForeground(NOTIF_RECORDING, recordingNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

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
            override fun onStop() { main.post { finish() } }
        }, main)
        projection = mp

        val wantsAudio = intent.getBooleanExtra(EXTRA_AUDIO, true) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val rec = try {
            ScreenRecorder(
                this,
                mp,
                intent.getIntExtra(EXTRA_DPI, 420),
                wantsAudio,
                MediaFiles.inputDir(this),
                MediaFiles.newRecordingBase(this),
            ).also { it.start() }
        } catch (e: Exception) {
            Log.e("RecordService", "recorder failed to start", e)
            null
        }
        if (rec == null) {
            projection = null
            try { mp.stop() } catch (_: Exception) {}
            return fail("Couldn't start the recorder.")
        }
        recorder = rec
        RecState.startedAt = System.currentTimeMillis()
        RecState.phase = RecState.Phase.RECORDING
        RecState.changed()
        getSystemService(NotificationManager::class.java).notify(NOTIF_RECORDING, recordingNotification())
    }

    private fun finish() {
        val rec = recorder ?: return
        if (finishing) return
        finishing = true
        RecState.phase = RecState.Phase.FINISHING
        RecState.changed()

        Thread {
            val file = try { rec.stop() } catch (e: Exception) {
                Log.e("RecordService", "recorder failed to stop", e)
                null
            }
            main.post {
                recorder = null
                val mp = projection
                projection = null
                try { mp?.stop() } catch (_: Exception) {}
                if (file != null) {
                    RecState.pendingMedia = MediaFiles.describe(file)
                } else {
                    RecState.error = "The recording came out empty. Try again."
                }
                RecState.phase = RecState.Phase.IDLE
                RecState.changed()
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (file != null && !RecState.activityResumed) doneNotification()
                finishing = false
                stopSelf()
            }
        }.start()
    }

    private fun fail(message: String) {
        RecState.error = message
        RecState.phase = RecState.Phase.IDLE
        RecState.changed()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun openAppIntent(action: String?): PendingIntent =
        PendingIntent.getActivity(this, if (action == null) 0 else 1,
            Intent(this, MainActivity::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun recordingNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, "Screen recording", NotificationManager.IMPORTANCE_LOW))
        val stop = Notification.Action.Builder(null, "Stop",
            openAppIntent(MainActivity.ACTION_STOP_RECORDING)).build()
        val b = Notification.Builder(this, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Recording screen")
            .setContentText("Tap Stop to trim & compress")
            .setContentIntent(openAppIntent(null))
            .setOngoing(true)
            .addAction(stop)
        if (RecState.phase == RecState.Phase.RECORDING) {
            b.setUsesChronometer(true).setWhen(RecState.startedAt).setShowWhen(true)
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

    override fun onDestroy() {
        recorder?.let { rec ->
            // Killed mid-recording: finalize what we have rather than leave a broken file.
            recorder = null
            Thread { try { rec.stop() } catch (_: Exception) {} }.start()
        }
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        if (RecState.phase != RecState.Phase.IDLE) {
            RecState.phase = RecState.Phase.IDLE
            RecState.changed()
        }
        super.onDestroy()
    }
}
