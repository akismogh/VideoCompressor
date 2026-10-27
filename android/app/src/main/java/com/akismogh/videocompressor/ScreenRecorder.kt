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
 * Records takes from one MediaProjection, following the screen's orientation.
 *
 * Android allows a single VirtualDisplay per projection, so it is created on the
 * first take and kept (detached from any encoder between takes) for the next ones.
 *
 * An encoder's frame size is fixed, so a landscape video played after starting in
 * portrait would end up letterboxed in a tall frame. Instead, every rotation closes
 * the current [Segment] and starts a new one sized for the new orientation. When a
 * take stops, its longest segment is kept — the part the user actually came to
 * record — and the short stretches around it (getting there, stopping) are dropped.
 */
class ScreenRecorder(
    context: Context,
    private val projection: MediaProjection,
    private val dpi: Int,
) {
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Object()
    private var display: VirtualDisplay? = null

    // Current take.
    private var recording = false
    private var current: Segment? = null
    private var deviceAudio = false
    private var mic = false
    private var dir = File("")
    private var baseName = ""
    private var landscape = false
    private var index = 0
    private val closing = ArrayList<Thread>()
    private val results = ArrayList<Segment.Result>()

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) onRotation()
        }
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
    }

    init {
        displays.registerDisplayListener(listener, main)
    }

    fun startTake(deviceAudio: Boolean, mic: Boolean, dir: File, baseName: String) = synchronized(lock) {
        check(!recording) { "already recording" }
        this.deviceAudio = deviceAudio
        this.mic = mic
        this.dir = dir
        this.baseName = baseName
        index = 0
        closing.clear()
        synchronized(results) { results.clear() }

        val (w, h) = screenSize()
        landscape = w > h
        val seg = newSegment(w, h)
        attach(seg)
        seg.start()
        current = seg
        recording = true
    }

    /** Finalizes the take; returns the kept file, or null if nothing usable was recorded. */
    fun stopTake(): File? {
        val last = synchronized(lock) {
            recording = false
            display?.surface = null
            current.also { current = null }
        }
        last?.stop()?.let { synchronized(results) { results += it } }
        synchronized(lock) { closing.toList() }.forEach { it.join(8000) }

        val all = synchronized(results) { results.toList() }
        val best = all.maxByOrNull { it.durationUs } ?: return null
        all.filter { it !== best }.forEach { it.file.delete() }
        val target = File(dir, "$baseName.mp4")
        return if (best.file.renameTo(target)) target else best.file
    }

    fun release() {
        main.post { displays.unregisterDisplayListener(listener) }
        try { display?.release() } catch (_: Exception) {}
        display = null
    }

    /** Must hold [lock]. Points the VirtualDisplay at [seg], creating it on first use. */
    private fun attach(seg: Segment) {
        val vd = display
        if (vd == null) {
            display = projection.createVirtualDisplay(
                "VideoCompressor", seg.width, seg.height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, seg.surface, null, null,
            )
        } else {
            vd.surface = null
            vd.resize(seg.width, seg.height, dpi)
            vd.surface = seg.surface
        }
    }

    private fun onRotation() = synchronized(lock) {
        if (!recording) return@synchronized
        val (w, h) = screenSize()
        if ((w > h) == landscape) return@synchronized
        val old = current ?: return@synchronized
        val seg = try { newSegment(w, h) } catch (e: Exception) {
            Log.w("ScreenRecorder", "couldn't start a segment for the new orientation", e)
            return@synchronized
        }
        landscape = w > h
        attach(seg)
        seg.start()
        current = seg
        closing += thread(name = "vc-close") {
            old.stop()?.let { synchronized(results) { results += it } }
        }
    }

    private fun newSegment(w: Int, h: Int): Segment {
        index++
        return Segment(projection, w, h, deviceAudio, mic, File(dir, "$baseName-$index.mp4"))
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
