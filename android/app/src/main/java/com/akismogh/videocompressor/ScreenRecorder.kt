package com.akismogh.videocompressor

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import java.io.File
import kotlin.concurrent.thread

/**
 * Records a MediaProjection, following the screen's orientation.
 *
 * An encoder's frame size is fixed, so a landscape video played after starting in
 * portrait would end up letterboxed in a tall frame. Instead, every rotation closes
 * the current [Segment] and starts a new one sized for the new orientation. When
 * recording stops, the longest segment is kept — the part the user actually came to
 * record — and the short stretches around it (getting there, stopping) are dropped.
 */
class ScreenRecorder(
    context: Context,
    private val projection: MediaProjection,
    private val dpi: Int,
    private val withAudio: Boolean,
    private val dir: File,
    private val baseName: String,
) {
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Object()
    private var display: VirtualDisplay? = null
    private var current: Segment? = null
    private var landscape = false
    private var index = 0
    private var stopped = false
    private val closing = ArrayList<Thread>()
    private val results = ArrayList<Segment.Result>()

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) onRotation()
        }
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
    }

    fun start() {
        val (w, h) = screenSize()
        landscape = w > h
        val seg = newSegment(w, h)
        display = projection.createVirtualDisplay(
            "VideoCompressor", seg.width, seg.height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, seg.surface, null, null,
        )
        seg.start()
        current = seg
        displays.registerDisplayListener(listener, main)
    }

    /** Finalizes the recording; returns the kept file, or null if nothing usable was recorded. */
    fun stop(): File? {
        main.post { displays.unregisterDisplayListener(listener) }
        val last = synchronized(lock) {
            stopped = true
            current.also { current = null }
        }
        last?.stop()?.let { synchronized(results) { results += it } }
        synchronized(lock) { closing.toList() }.forEach { it.join(8000) }
        try { display?.release() } catch (_: Exception) {}

        val all = synchronized(results) { results.toList() }
        val best = all.maxByOrNull { it.durationUs } ?: return null
        all.filter { it !== best }.forEach { it.file.delete() }
        val target = File(dir, "$baseName.mp4")
        return if (best.file.renameTo(target)) target else best.file
    }

    private fun onRotation() = synchronized(lock) {
        if (stopped) return@synchronized
        val (w, h) = screenSize()
        if ((w > h) == landscape) return@synchronized
        val vd = display ?: return@synchronized
        val old = current ?: return@synchronized
        val seg = try { newSegment(w, h) } catch (e: Exception) {
            Log.w("ScreenRecorder", "couldn't start a segment for the new orientation", e)
            return@synchronized
        }
        landscape = w > h
        vd.surface = null
        vd.resize(seg.width, seg.height, dpi)
        vd.surface = seg.surface
        seg.start()
        current = seg
        closing += thread(name = "vc-close") {
            old.stop()?.let { synchronized(results) { results += it } }
        }
    }

    private fun newSegment(w: Int, h: Int): Segment {
        index++
        return Segment(projection, w, h, withAudio, File(dir, "$baseName-$index.mp4"))
            .also { it.prepare() }
    }

    /** Current screen size in the current orientation. */
    private fun screenSize(): Pair<Int, Int> {
        val d = displays.getDisplay(Display.DEFAULT_DISPLAY)
        val mode = d.mode
        val rotated = d.rotation == Surface.ROTATION_90 || d.rotation == Surface.ROTATION_270
        val naturalPortrait = mode.physicalWidth < mode.physicalHeight
        val short = minOf(mode.physicalWidth, mode.physicalHeight)
        val long = maxOf(mode.physicalWidth, mode.physicalHeight)
        // Phones are naturally portrait; rotating 90°/270° makes them landscape.
        val portraitNow = naturalPortrait != rotated
        return if (portraitNow) short to long else long to short
    }
}
