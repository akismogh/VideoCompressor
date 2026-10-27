package com.akismogh.videocompressor

import android.annotation.SuppressLint
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * Records a MediaProjection into one MP4: the screen as H.264 (VirtualDisplay →
 * encoder input surface) plus, optionally, the device's playback audio as AAC
 * (AudioPlaybackCapture). Both tracks are stamped on the System.nanoTime() clock
 * so they line up in the muxer.
 */
class ScreenRecorder(
    private val projection: MediaProjection,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val dpi: Int,
    withAudio: Boolean,
    private val output: File,
) {
    private companion object {
        const val TAG = "ScreenRecorder"
        const val VIDEO = 0
        const val AUDIO = 1
        const val VIDEO_BITRATE = 6_000_000
        const val FRAME_RATE = 30
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = CHANNELS * 2
        const val AUDIO_CHUNK_BYTES = SAMPLE_RATE * BYTES_PER_FRAME / 50 // 20 ms
        const val MAX_PENDING = 600
        val LONG_SIDE_LIMITS = intArrayOf(1920, 1280)
    }

    private class Sample(val kind: Int, val data: ByteArray, val ptsUs: Long, val flags: Int)

    private var audioEnabled = withAudio
    private val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val lock = Object()
    private val tracks = intArrayOf(-1, -1)
    private val lastPts = longArrayOf(-1, -1)
    private val ptsBase = longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE)
    private val pending = ArrayList<Sample>()
    private var muxerStarted = false
    private var wroteVideo = false

    private lateinit var videoCodec: MediaCodec
    private var inputSurface: Surface? = null
    private var display: VirtualDisplay? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var startUs = 0L
    @Volatile private var stopping = false

    fun start() {
        val (w, h) = configureVideo()
        if (audioEnabled) audioEnabled = configureAudio()

        startUs = System.nanoTime() / 1000
        display = projection.createVirtualDisplay(
            "VideoCompressor", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, null,
        )
        videoThread = thread(name = "vc-video") { drainVideo() }
        if (audioEnabled) audioThread = thread(name = "vc-audio") { pumpAudio() }
    }

    /** Stops both encoders and finalizes the file. Returns it, or null if nothing was recorded. */
    fun stop(): File? {
        stopping = true
        audioThread?.let { t ->
            t.join(1500)
            if (t.isAlive) {
                try { audioRecord?.stop() } catch (_: Exception) {}
                t.join(2000)
            }
        }
        videoThread?.join(5000)

        try { display?.release() } catch (_: Exception) {}
        try { videoCodec.stop() } catch (_: Exception) {}
        try { videoCodec.release() } catch (_: Exception) {}
        inputSurface?.release()
        try { audioCodec?.stop() } catch (_: Exception) {}
        try { audioCodec?.release() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}

        val ok = synchronized(lock) {
            var finished = muxerStarted && wroteVideo
            if (muxerStarted) {
                try { muxer.stop() } catch (e: Exception) {
                    Log.w(TAG, "muxer.stop failed", e)
                    finished = false
                }
            }
            try { muxer.release() } catch (_: Exception) {}
            finished
        }
        if (!ok) {
            output.delete()
            return null
        }
        return output
    }

    // ── Video ────────────────────────────────────────────────────────────────

    /** Configures the H.264 encoder, stepping down the size if the encoder rejects it. */
    private fun configureVideo(): Pair<Int, Int> {
        var lastError: Exception? = null
        for (limit in LONG_SIDE_LIMITS) {
            val (w, h) = fitSize(limit)
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
                    setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    // Keep frames flowing on a static screen so the muxer starts promptly.
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
                }
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = codec.createInputSurface()
                codec.start()
                videoCodec = codec
                return w to h
            } catch (e: Exception) {
                lastError = e
                codec.release()
            }
        }
        throw IllegalStateException("No H.264 encoder accepted the screen size", lastError)
    }

    private fun fitSize(longLimit: Int): Pair<Int, Int> {
        val long = max(screenWidth, screenHeight)
        val short = min(screenWidth, screenHeight)
        val scale = min(1.0, min(longLimit.toDouble() / long, 1080.0 / short))
        val w = (screenWidth * scale).toInt() / 16 * 16
        val h = (screenHeight * scale).toInt() / 16 * 16
        return w to h
    }

    private fun drainVideo() {
        val info = MediaCodec.BufferInfo()
        var eosDeadline = 0L
        try {
            while (true) {
                if (stopping && eosDeadline == 0L) {
                    try { videoCodec.signalEndOfInputStream() } catch (_: Exception) {}
                    eosDeadline = SystemClock.elapsedRealtime() + 3000
                }
                if (eosDeadline != 0L && SystemClock.elapsedRealtime() > eosDeadline) break
                val idx = videoCodec.dequeueOutputBuffer(info, 10_000)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    addTrack(VIDEO, videoCodec.outputFormat)
                } else if (idx >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val buf = videoCodec.getOutputBuffer(idx)
                    if (buf != null) write(VIDEO, buf, info)
                    videoCodec.releaseOutputBuffer(idx, false)
                    if (eos) break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "video drain stopped", e)
        }
    }

    // ── Audio ────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission") // RecordService checks RECORD_AUDIO before asking for audio
    private fun configureAudio(): Boolean {
        return try {
            val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val pcm = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord.Builder()
                .setAudioFormat(pcm)
                .setBufferSizeInBytes(max(minBuf * 4, AUDIO_CHUNK_BYTES * 8))
                .setAudioPlaybackCaptureConfig(capture)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return false
            }
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_CHUNK_BYTES * 2)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            audioRecord = rec
            audioCodec = codec
            true
        } catch (e: Exception) {
            Log.w(TAG, "device audio unavailable, recording video only", e)
            false
        }
    }

    private fun pumpAudio() {
        val codec = audioCodec ?: return
        val rec = audioRecord ?: return
        val info = MediaCodec.BufferInfo()
        var frames = 0L
        var base = -1L
        fun ptsNow() = base + frames * 1_000_000L / SAMPLE_RATE
        try {
            rec.startRecording()
            var eosQueued = false
            while (!eosQueued) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    if (stopping) {
                        codec.queueInputBuffer(inIdx, 0, 0, max(ptsNow(), 0), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosQueued = true
                    } else {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        val n = rec.read(inBuf, min(inBuf.remaining(), AUDIO_CHUNK_BYTES))
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, max(ptsNow(), 0), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosQueued = true
                        } else {
                            if (base < 0) {
                                base = System.nanoTime() / 1000 - (n / BYTES_PER_FRAME) * 1_000_000L / SAMPLE_RATE
                            }
                            val pts = ptsNow()
                            frames += n / BYTES_PER_FRAME
                            codec.queueInputBuffer(inIdx, 0, n, pts, 0)
                        }
                    }
                }
                if (drainAudio(codec, info, if (eosQueued) 2000 else 0)) break
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio pump stopped", e)
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            synchronized(lock) {
                // No audio ever reached the muxer: fall back to a video-only file.
                if (tracks[AUDIO] < 0) {
                    audioEnabled = false
                    maybeStartMuxer()
                }
            }
        }
    }

    /** Drains encoded audio; waits up to [waitMs] for end of stream. Returns true at EOS. */
    private fun drainAudio(codec: MediaCodec, info: MediaCodec.BufferInfo, waitMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + waitMs
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (waitMs > 0) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER ->
                    if (waitMs == 0L || SystemClock.elapsedRealtime() > deadline) return waitMs > 0
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> addTrack(AUDIO, codec.outputFormat)
                idx >= 0 -> {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val buf = codec.getOutputBuffer(idx)
                    if (buf != null) write(AUDIO, buf, info)
                    codec.releaseOutputBuffer(idx, false)
                    if (eos) return true
                }
            }
        }
    }

    // ── Muxer ────────────────────────────────────────────────────────────────

    private fun addTrack(kind: Int, format: MediaFormat) = synchronized(lock) {
        if (tracks[kind] >= 0 || muxerStarted) return@synchronized
        tracks[kind] = muxer.addTrack(format)
        maybeStartMuxer()
    }

    /** Must hold [lock]. Starts once every expected track is known, then flushes the backlog. */
    private fun maybeStartMuxer() {
        if (muxerStarted || tracks[VIDEO] < 0 || (audioEnabled && tracks[AUDIO] < 0)) return
        muxer.start()
        muxerStarted = true
        for (s in pending) {
            if (s.kind == AUDIO && tracks[AUDIO] < 0) continue
            val info = MediaCodec.BufferInfo().apply { set(0, s.data.size, s.ptsUs, s.flags) }
            muxer.writeSampleData(tracks[s.kind], ByteBuffer.wrap(s.data), info)
            if (s.kind == VIDEO) wroteVideo = true
        }
        pending.clear()
    }

    private fun write(kind: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.size <= 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        synchronized(lock) {
            val raw = info.presentationTimeUs
            if (ptsBase[kind] == Long.MIN_VALUE) {
                // Encoders normally stamp on the nanoTime clock; if one doesn't, anchor it to now.
                val rel = raw - startUs
                ptsBase[kind] = if (rel < -1_000_000 || rel > 10_000_000) {
                    raw - (System.nanoTime() / 1000 - startUs)
                } else startUs
            }
            var pts = raw - ptsBase[kind]
            if (pts < 0) return
            if (pts <= lastPts[kind]) pts = lastPts[kind] + 1
            lastPts[kind] = pts

            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            val flags = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
            if (!muxerStarted) {
                if (pending.size < MAX_PENDING) {
                    val copy = ByteArray(info.size)
                    buf.get(copy)
                    pending += Sample(kind, copy, pts, flags)
                }
                return
            }
            if (kind == AUDIO && tracks[AUDIO] < 0) return
            val out = MediaCodec.BufferInfo().apply { set(info.offset, info.size, pts, flags) }
            muxer.writeSampleData(tracks[kind], buf, out)
            if (kind == VIDEO) wroteVideo = true
        }
    }
}
