package com.akismogh.videocompressor

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * One stretch of a screen recording in a single orientation, encoded into its own
 * MP4: the frames [ScreenRecorder]'s VirtualDisplay draws into [surface] as H.264,
 * plus an AAC track mixing whichever of the device's playback audio
 * (AudioPlaybackCapture) and the microphone were asked for.
 * Both tracks are stamped on the System.nanoTime() clock so they line up in the muxer.
 */
class Segment(
    private val projection: MediaProjection,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val deviceAudio: Boolean,
    private val mic: Boolean,
    val output: File,
) {
    class Result(val file: File, val durationUs: Long)

    private companion object {
        const val TAG = "Segment"
        const val VIDEO = 0
        const val AUDIO = 1
        const val VIDEO_BITRATE = 6_000_000
        const val FRAME_RATE = 30
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = CHANNELS * 2
        const val CHUNK_FRAMES = SAMPLE_RATE / 50 // 20 ms
        const val AUDIO_CHUNK_BYTES = CHUNK_FRAMES * BYTES_PER_FRAME
        const val MAX_PENDING = 600
        val LONG_SIDE_LIMITS = intArrayOf(1920, 1280)
    }

    private class Sample(val kind: Int, val data: ByteArray, val ptsUs: Long, val flags: Int)

    private var audioEnabled = deviceAudio || mic
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
    private var audioCodec: MediaCodec? = null
    private var deviceRecord: AudioRecord? = null
    private var micRecord: AudioRecord? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var startUs = 0L
    @Volatile private var stopping = false

    var width = 0
        private set
    var height = 0
        private set
    val surface: Surface get() = inputSurface!!

    /** Configures the encoders; point the VirtualDisplay at [surface] afterwards. */
    fun prepare() {
        try {
            val (w, h) = configureVideo()
            width = w
            height = h
        } catch (e: Exception) {
            try { muxer.release() } catch (_: Exception) {}
            output.delete()
            throw e
        }
        if (audioEnabled) audioEnabled = configureAudio()
    }

    fun start() {
        startUs = System.nanoTime() / 1000
        videoThread = thread(name = "vc-video") { drainVideo() }
        if (audioEnabled) audioThread = thread(name = "vc-audio") { pumpAudio() }
    }

    /** Stops both encoders and finalizes the file. Returns null if nothing was recorded. */
    fun stop(): Result? {
        stopping = true
        audioThread?.let { t ->
            t.join(1500)
            if (t.isAlive) {
                try { deviceRecord?.stop() } catch (_: Exception) {}
                try { micRecord?.stop() } catch (_: Exception) {}
                t.join(2000)
            }
        }
        videoThread?.join(5000)

        try { videoCodec.stop() } catch (_: Exception) {}
        try { videoCodec.release() } catch (_: Exception) {}
        inputSurface?.release()
        try { audioCodec?.stop() } catch (_: Exception) {}
        try { audioCodec?.release() } catch (_: Exception) {}
        try { deviceRecord?.release() } catch (_: Exception) {}
        try { micRecord?.release() } catch (_: Exception) {}

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
        return Result(output, synchronized(lock) { lastPts[VIDEO] })
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

    private fun configureAudio(): Boolean {
        if (deviceAudio) deviceRecord = openDeviceCapture()
        if (mic) micRecord = openMic()
        if (deviceRecord == null && micRecord == null) return false
        return try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_CHUNK_BYTES * 2)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            audioCodec = codec
            true
        } catch (e: Exception) {
            Log.w(TAG, "AAC encoder unavailable, recording video only", e)
            deviceRecord?.release()
            micRecord?.release()
            deviceRecord = null
            micRecord = null
            false
        }
    }

    @SuppressLint("MissingPermission") // RecordService checks RECORD_AUDIO before asking for audio
    private fun openDeviceCapture(): AudioRecord? = try {
        val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        AudioRecord.Builder()
            .setAudioFormat(pcmFormat(AudioFormat.CHANNEL_IN_STEREO))
            .setBufferSizeInBytes(max(minBuf * 4, AUDIO_CHUNK_BYTES * 8))
            .setAudioPlaybackCaptureConfig(capture)
            .build()
            .takeIfInitialized()
    } catch (e: Exception) {
        Log.w(TAG, "device audio unavailable", e)
        null
    }

    /**
     * Mono microphone. With device audio on as well, the voice-communication source
     * lets the platform's echo canceller keep the speaker's sound from being picked
     * up a second time.
     */
    @SuppressLint("MissingPermission")
    private fun openMic(): AudioRecord? = try {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        AudioRecord.Builder()
            .setAudioSource(
                if (deviceAudio) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                else MediaRecorder.AudioSource.MIC)
            .setAudioFormat(pcmFormat(AudioFormat.CHANNEL_IN_MONO))
            .setBufferSizeInBytes(max(minBuf * 4, CHUNK_FRAMES * 2 * 8))
            .build()
            .takeIfInitialized()
    } catch (e: Exception) {
        Log.w(TAG, "microphone unavailable", e)
        null
    }

    private fun pcmFormat(channelMask: Int): AudioFormat = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(channelMask)
        .build()

    private fun AudioRecord.takeIfInitialized(): AudioRecord? =
        if (state == AudioRecord.STATE_INITIALIZED) this else { release(); null }

    /** Reads the next chunk as stereo PCM into [out]; returns frames read, or -1 on error. */
    private fun readChunk(out: ShortArray, micBuf: ShortArray): Int {
        val dev = deviceRecord
        val mic = micRecord
        if (dev != null) {
            val n = dev.read(out, 0, CHUNK_FRAMES * CHANNELS)
            if (n < 0) return -1
            val frames = n / CHANNELS
            if (mic != null) {
                // The device capture sets the pace; mix in whatever the mic has ready.
                val m = max(0, mic.read(micBuf, 0, frames, AudioRecord.READ_NON_BLOCKING))
                for (i in 0 until m) {
                    val v = micBuf[i].toInt()
                    out[2 * i] = clip(out[2 * i] + v)
                    out[2 * i + 1] = clip(out[2 * i + 1] + v)
                }
            }
            return frames
        }
        val m = mic?.read(micBuf, 0, CHUNK_FRAMES) ?: return -1
        if (m < 0) return -1
        for (i in 0 until m) {
            out[2 * i] = micBuf[i]
            out[2 * i + 1] = micBuf[i]
        }
        return m
    }

    private fun clip(v: Int): Short =
        v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    private fun pumpAudio() {
        val codec = audioCodec ?: return
        val info = MediaCodec.BufferInfo()
        val pcm = ShortArray(CHUNK_FRAMES * CHANNELS)
        val micBuf = ShortArray(CHUNK_FRAMES)
        var frames = 0L
        var base = -1L
        fun ptsNow() = max(base + frames * 1_000_000L / SAMPLE_RATE, 0)
        try {
            deviceRecord?.startRecording()
            micRecord?.startRecording()
            while (true) {
                val n = if (stopping) -1 else readChunk(pcm, micBuf)
                if (n == 0) continue
                val inIdx = dequeueInput(codec, info)
                if (n < 0) {
                    codec.queueInputBuffer(inIdx, 0, 0, ptsNow(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    drainAudio(codec, info, 2000)
                    break
                }
                if (base < 0) base = System.nanoTime() / 1000 - n * 1_000_000L / SAMPLE_RATE
                val inBuf = codec.getInputBuffer(inIdx)!!
                inBuf.clear()
                inBuf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, 0, n * CHANNELS)
                codec.queueInputBuffer(inIdx, 0, n * BYTES_PER_FRAME, ptsNow(), 0)
                frames += n
                drainAudio(codec, info, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio pump stopped", e)
        } finally {
            try { deviceRecord?.stop() } catch (_: Exception) {}
            try { micRecord?.stop() } catch (_: Exception) {}
            synchronized(lock) {
                // No audio ever reached the muxer: fall back to a video-only file.
                if (tracks[AUDIO] < 0) {
                    audioEnabled = false
                    maybeStartMuxer()
                }
            }
        }
    }

    /** Waits for a free encoder input buffer, draining output meanwhile so it can't stall. */
    private fun dequeueInput(codec: MediaCodec, info: MediaCodec.BufferInfo): Int {
        while (true) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) return idx
            drainAudio(codec, info, 0)
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
