package com.aarrondo.droneview.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.graphics.scale

class Mp4Recorder(
    private val context: Context,
    val width: Int = 640,
    val height: Int = 480,
) {

    private val encWidth = width and 1.inv()
    private val encHeight = height and 1.inv()

    @Volatile
    var isRecording: Boolean = false
        private set

    @Volatile
    var outputUri: Uri? = null
        private set

    private val _framesOffered = AtomicInteger(0)
    private val _framesEncoded = AtomicInteger(0)
    private val _framesDropped = AtomicInteger(0)

    private val appContext = context.applicationContext
    private val controlMutex = Mutex()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var videoTrack = -1
    private var muxerStarted = false

    @Volatile
    private var eosRequested = false

    @Volatile
    private var startNs = 0L
    private val lastOfferedPtsUs = AtomicLong(-1L)
    private var lastQueuedPtsUs = -1L

    private val epoch = AtomicInteger(0)

    private val pending = ArrayBlockingQueue<PendingFrame>(PENDING_QUEUE_CAPACITY)

    private val pendingSamples = ArrayList<PendingSample>()
    private var drainJob: Job? = null

    init {
        require(encWidth >= 2 && encHeight >= 2) {
            "Mp4Recorder: dimensions must be >= 2x2 after even-alignment (got ${width}x$height)"
        }
    }

    suspend fun start(): Uri = withContext(Dispatchers.IO) {
        controlMutex.withLock {
            check(!isRecording) { "Mp4Recorder: already recording" }
            resetForStart()

            var uri: Uri? = null
            var newPfd: ParcelFileDescriptor? = null
            var newCodec: MediaCodec? = null
            var newMuxer: MediaMuxer? = null
            try {
                val created = MediaStoreSaver.createPendingVideo(appContext)
                uri = created.first
                newPfd = created.second

                val format = MediaFormat.createVideoFormat(MIME_AVC, encWidth, encHeight).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, selectColorFormat())
                    setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                    setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
                }
                newCodec = MediaCodec.createEncoderByType(MIME_AVC)
                newCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                newMuxer = MediaMuxer(newPfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                newCodec.start()
            } catch (e: Exception) {
                Log.e(TAG, "start failed; aborting entry", e)
                try {
                    newCodec?.release()
                } catch (_: Exception) {
                }
                try {
                    newMuxer?.release()
                } catch (_: Exception) {
                }
                try {
                    newPfd?.close()
                } catch (_: Exception) {
                }
                if (uri != null) MediaStoreSaver.abortVideo(appContext, uri)
                if (e is CancellationException) throw e
                throw e as? IOException ?: IOException("Mp4Recorder start failed", e)
            }

            codec = newCodec
            muxer = newMuxer
            pfd = newPfd
            outputUri = checkNotNull(uri)
            startNs = System.nanoTime()
            isRecording = true
            drainJob = ioScope.launch { drainLoop() }
            checkNotNull(outputUri)
        }
    }

    fun onFrame(bitmap: Bitmap) {
        if (!isRecording) return
        val t0 = startNs
        if (t0 == 0L) return
        val observedEpoch = epoch.get()

        val fitted = try {
            fitFrame(bitmap, encWidth, encHeight)
        } catch (e: Exception) {
            Log.w(TAG, "onFrame: scale/crop failed, dropping frame", e)
            _framesDropped.incrementAndGet()
            return
        }
        try {
            val pixels = IntArray(encWidth * encHeight)
            try {
                fitted.getPixels(pixels, 0, encWidth, 0, 0, encWidth, encHeight)
            } catch (e: Exception) {
                Log.w(TAG, "onFrame: getPixels failed, dropping frame", e)
                _framesDropped.incrementAndGet()
                return
            }
            val nv12 = ByteArray(encWidth * encHeight * 3 / 2)
            argbToNv12(pixels, nv12, encWidth, encHeight)

            var ptsUs = (System.nanoTime() - t0) / 1_000L
            if (ptsUs < 0L) ptsUs = 0L
            while (true) {
                val last = lastOfferedPtsUs.get()
                if (ptsUs <= last) {
                    _framesDropped.incrementAndGet()
                    return
                }
                if (lastOfferedPtsUs.compareAndSet(last, ptsUs)) break
            }
            if (!isRecording || epoch.get() != observedEpoch) {
                _framesDropped.incrementAndGet()
                return
            }
            if (!pending.offer(PendingFrame(nv12, ptsUs))) {
                _framesDropped.incrementAndGet()
                return
            }
            _framesOffered.incrementAndGet()
        } finally {
            if (fitted !== bitmap) {
                try {
                    fitted.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }

    suspend fun stop(save: Boolean = true): Uri? = withContext(Dispatchers.IO) {
        controlMutex.withLock {
            if (!isRecording) return@withLock null
            isRecording = false
            eosRequested = true

            val job = drainJob
            if (job != null) {
                val joined = withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS.milliseconds) {
                    job.join()
                    true
                } ?: false
                if (!joined) {
                    Log.w(TAG, "stop: drain did not finish in time; cancelling")
                    runCatching { job.cancel() }
                    try {
                        withTimeoutOrNull(2_000L.milliseconds) { job.join() }
                    } catch (_: Exception) {
                    }
                }
            }

            var muxerUsable = muxerStarted
            try {
                codec?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "stop: codec.stop failed", e)
                muxerUsable = false
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            codec = null
            try {
                if (muxerStarted) {
                    muxer?.stop()
                } else {
                    muxerUsable = false
                }
            } catch (e: Exception) {
                Log.w(TAG, "stop: muxer.stop failed; discarding entry", e)
                muxerUsable = false
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            muxer = null
            try {
                pfd?.close()
            } catch (_: Exception) {
            }
            pfd = null

            val uri = outputUri
            val encoded = _framesEncoded.get()
            val result: Uri? = if (save && muxerUsable && uri != null && encoded > 0) {
                try {
                    MediaStoreSaver.finalizeVideo(appContext, uri)
                    uri
                } catch (e: Exception) {
                    Log.w(TAG, "stop: finalize failed; deleting entry", e)
                    MediaStoreSaver.abortVideo(appContext, uri)
                    null
                }
            } else {
                if (uri != null) MediaStoreSaver.abortVideo(appContext, uri)
                if (save) {
                    Log.w(
                        TAG,
                        "stop: nothing usable to publish " +
                            "(muxerStarted=$muxerStarted encoded=$encoded); entry discarded",
                    )
                }
                null
            }
            outputUri = result
            drainJob = null
            videoTrack = -1
            muxerStarted = false
            startNs = 0L
            lastQueuedPtsUs = -1L
            pending.clear()
            pendingSamples.clear()
            result
        }
    }

    fun release() {
        ioScope.cancel()
    }

    private suspend fun drainLoop() {
        val c = codec ?: return
        try {
            while (true) {
                if (!isLoopActive()) break
                if (eosRequested && pending.isEmpty()) break
                val frame: PendingFrame? = try {
                    withContext(Dispatchers.IO) {
                        pending.poll(INPUT_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CancellationException("drain interrupted")
                }
                if (frame != null) {
                    if (frame.ptsUs > lastQueuedPtsUs) {
                        if (queueFrame(c, frame)) lastQueuedPtsUs = frame.ptsUs
                    } else {
                        _framesDropped.incrementAndGet()
                    }
                }
                drainOutput(c, OUTPUT_DRAIN_TIMEOUT_US)
            }
            signalEos(c)
            val deadlineNs = System.nanoTime() + EOS_DRAIN_TIMEOUT_MS * 1_000_000L
            var eosSeen = false
            while (!eosSeen && isLoopActive() && System.nanoTime() < deadlineNs) {
                eosSeen = drainOutput(c, EOS_POLL_TIMEOUT_US)
            }
            if (!eosSeen) Log.w(TAG, "drain: EOS output not observed before timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "drain loop failed", e)
        }
    }

    private fun queueFrame(c: MediaCodec, frame: PendingFrame): Boolean {
        val inIndex = try {
            c.dequeueInputBuffer(INPUT_DEQUEUE_TIMEOUT_US)
        } catch (e: Exception) {
            Log.w(TAG, "queueFrame: dequeueInputBuffer failed", e)
            _framesDropped.incrementAndGet()
            return false
        }
        if (inIndex < 0) {
            _framesDropped.incrementAndGet()
            return false
        }
        return try {
            val inBuf = c.getInputBuffer(inIndex)
            if (inBuf == null || inBuf.remaining() < frame.nv12.size) {
                Log.w(TAG, "queueFrame: input buffer too small/missing; feeding empty sample")
                c.queueInputBuffer(inIndex, 0, 0, frame.ptsUs, 0)
                _framesDropped.incrementAndGet()
                return false
            }
            inBuf.clear()
            inBuf.put(frame.nv12)
            c.queueInputBuffer(inIndex, 0, frame.nv12.size, frame.ptsUs, 0)
            true
        } catch (e: Exception) {
            Log.w(TAG, "queueFrame: queueInputBuffer failed", e)
            _framesDropped.incrementAndGet()
            false
        }
    }

    private fun drainOutput(c: MediaCodec, timeoutUs: Long): Boolean {
        val info = MediaCodec.BufferInfo()
        val outIndex = try {
            c.dequeueOutputBuffer(info, timeoutUs)
        } catch (e: Exception) {
            Log.w(TAG, "drainOutput: dequeueOutputBuffer failed", e)
            return false
        }
        when {
            outIndex >= 0 -> {
                val eos: Boolean
                try {
                    val outBuf = c.getOutputBuffer(outIndex)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (!isConfig && info.size > 0 && outBuf != null) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        if (muxerStarted && videoTrack >= 0) {
                            try {
                                muxer?.writeSampleData(videoTrack, outBuf, info)
                                _framesEncoded.incrementAndGet()
                            } catch (e: Exception) {
                                Log.e(TAG, "drainOutput: writeSampleData failed", e)
                                _framesDropped.incrementAndGet()
                            }
                        } else {
                            if (pendingSamples.size >= MAX_PENDING_SAMPLES) {
                                pendingSamples.removeAt(0)
                                _framesDropped.incrementAndGet()
                            }
                            val copy = ByteArray(info.size)
                            outBuf.get(copy)
                            pendingSamples.add(
                                PendingSample(copy, info.presentationTimeUs, info.flags),
                            )
                            _framesEncoded.incrementAndGet()
                        }
                    }
                } finally {
                    try {
                        c.releaseOutputBuffer(outIndex, false)
                    } catch (_: Exception) {
                    }
                }
                return eos
            }

            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                if (!muxerStarted) {
                    try {
                        videoTrack = muxer?.addTrack(c.outputFormat) ?: -1
                        muxer?.start()
                        muxerStarted = true
                        flushPendingSamples()
                    } catch (e: Exception) {
                        Log.e(TAG, "drainOutput: muxer start failed", e)
                    }
                }
                return false
            }

            else -> return false
        }
    }

    private fun flushPendingSamples() {
        val info = MediaCodec.BufferInfo()
        val iterator = pendingSamples.iterator()
        while (iterator.hasNext()) {
            val sample = iterator.next()
            val flags = sample.flags and
                MediaCodec.BUFFER_FLAG_CODEC_CONFIG.inv() and
                MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
            info.set(0, sample.data.size, sample.ptsUs, flags)
            try {
                muxer?.writeSampleData(videoTrack, ByteBuffer.wrap(sample.data), info)
            } catch (e: Exception) {
                Log.e(TAG, "flushPendingSamples: writeSampleData failed", e)
                _framesDropped.incrementAndGet()
            }
            iterator.remove()
        }
    }

    private fun signalEos(c: MediaCodec) {
        var leftover = pending.poll()
        while (leftover != null) {
            if (leftover.ptsUs > lastQueuedPtsUs && queueFrame(c, leftover)) {
                lastQueuedPtsUs = leftover.ptsUs
            }
            leftover = pending.poll()
        }
        val t0 = startNs
        val eosPtsUs = if (t0 == 0L) {
            lastQueuedPtsUs + 1
        } else {
            maxOf((System.nanoTime() - t0) / 1_000L, lastQueuedPtsUs + 1)
        }
        val deadlineNs = System.nanoTime() + SIGNAL_EOS_TIMEOUT_MS * 1_000_000L
        while (System.nanoTime() < deadlineNs) {
            val inIndex = try {
                c.dequeueInputBuffer(EOS_POLL_TIMEOUT_US)
            } catch (e: Exception) {
                Log.w(TAG, "signalEos: dequeueInputBuffer failed", e)
                return
            }
            if (inIndex >= 0) {
                try {
                    c.queueInputBuffer(inIndex, 0, 0, eosPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } catch (e: Exception) {
                    Log.w(TAG, "signalEos: queueInputBuffer(EOS) failed", e)
                }
                return
            }
            drainOutput(c, 0L)
        }
        Log.w(TAG, "signalEos: no input buffer available before timeout")
    }

    private suspend fun isLoopActive(): Boolean =
        currentCoroutineContext()[Job]?.isActive ?: true

    private fun selectColorFormat(): Int {
        var sawSemiPlanar = false
        val infos = try {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        } catch (e: Exception) {
            Log.w(TAG, "selectColorFormat: MediaCodecList failed, using SemiPlanar", e)
            return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        }
        for (info in infos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.contains(MIME_AVC)) continue
            val formats = try {
                info.getCapabilitiesForType(MIME_AVC).colorFormats?.toList()
            } catch (_: Exception) {
                continue
            } ?: continue
            if (formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)) {
                return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            }
            if (formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)) {
                sawSemiPlanar = true
            }
        }
        if (sawSemiPlanar) return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        Log.w(TAG, "selectColorFormat: no YUV420 format advertised; trying SemiPlanar anyway")
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    }

    private fun resetForStart() {
        pending.clear()
        pendingSamples.clear()
        codec = null
        muxer = null
        pfd = null
        videoTrack = -1
        muxerStarted = false
        eosRequested = false
        startNs = 0L
        lastOfferedPtsUs.set(-1L)
        lastQueuedPtsUs = -1L
        _framesOffered.set(0)
        _framesEncoded.set(0)
        _framesDropped.set(0)
        epoch.incrementAndGet()
        drainJob = null
    }

    private data class PendingFrame(val nv12: ByteArray, val ptsUs: Long)

    private data class PendingSample(val data: ByteArray, val ptsUs: Long, val flags: Int)

    companion object {
        private const val TAG = "Mp4Recorder"
        private const val MIME_AVC = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val BIT_RATE = 4_000_000
        private const val FRAME_RATE = 30
        private const val I_FRAME_INTERVAL_SEC = 1

        private const val PENDING_QUEUE_CAPACITY = 8

        private const val MAX_PENDING_SAMPLES = 64

        private const val INPUT_POLL_TIMEOUT_MS = 10L
        private const val INPUT_DEQUEUE_TIMEOUT_US = 10_000L
        private const val OUTPUT_DRAIN_TIMEOUT_US = 10_000L
        private const val EOS_POLL_TIMEOUT_US = 50_000L
        private const val SIGNAL_EOS_TIMEOUT_MS = 2_000L
        private const val EOS_DRAIN_TIMEOUT_MS = 5_000L
        private const val STOP_JOIN_TIMEOUT_MS = 8_000L
    }
}

private fun fitFrame(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
    if (src.width == targetW && src.height == targetH) return src
    require(src.width > 0 && src.height > 0) { "empty bitmap" }
    val srcAspect = src.width.toFloat() / src.height
    val dstAspect = targetW.toFloat() / targetH
    val cropW: Int
    val cropH: Int
    if (srcAspect > dstAspect) {
        cropH = src.height
        cropW = (src.height * dstAspect).toInt().coerceIn(1, src.width)
    } else {
        cropW = src.width
        cropH = (src.width / dstAspect).toInt().coerceIn(1, src.height)
    }
    val cropX = ((src.width - cropW) / 2).coerceAtLeast(0)
    val cropY = ((src.height - cropH) / 2).coerceAtLeast(0)
    val cropped = try {
        Bitmap.createBitmap(
            src,
            cropX,
            cropY,
            cropW.coerceAtMost(src.width - cropX),
            cropH.coerceAtMost(src.height - cropY),
        )
    } catch (e: Exception) {
        throw IOException("fitFrame: crop failed", e)
    }
    if (cropped.width == targetW && cropped.height == targetH) return cropped
    val scaled = try {
        cropped.scale(targetW, targetH)
    } catch (e: Exception) {
        if (cropped !== src) runCatching { cropped.recycle() }
        throw IOException("fitFrame: scale failed", e)
    }
    if (cropped !== src && cropped !== scaled) runCatching { cropped.recycle() }
    return scaled
}

private fun argbToNv12(pixels: IntArray, out: ByteArray, width: Int, height: Int) {
    val frameSize = width * height
    var y = 0
    for (px in pixels) {
        val r = px shr 16 and 0xFF
        val g = px shr 8 and 0xFF
        val b = px and 0xFF
        out[y++] = clamp8((66 * r + 129 * g + 25 * b + 128 shr 8) + 16).toByte()
    }
    var uv = frameSize
    var row = 0
    while (row < height) {
        val rowOff0 = row * width
        val rowOff1 = (row + 1).coerceAtMost(height - 1) * width
        var col = 0
        while (col < width) {
            val c1 = minOf(col + 1, width - 1)
            val p00 = pixels[rowOff0 + col]
            val p01 = pixels[rowOff0 + c1]
            val p10 = pixels[rowOff1 + col]
            val p11 = pixels[rowOff1 + c1]
            val r = ((p00 shr 16 and 0xFF) + (p01 shr 16 and 0xFF) +
                (p10 shr 16 and 0xFF) + (p11 shr 16 and 0xFF)) shr 2
            val g = ((p00 shr 8 and 0xFF) + (p01 shr 8 and 0xFF) +
                (p10 shr 8 and 0xFF) + (p11 shr 8 and 0xFF)) shr 2
            val b = ((p00 and 0xFF) + (p01 and 0xFF) + (p10 and 0xFF) + (p11 and 0xFF)) shr 2
            out[uv++] = clamp8((-38 * r - 74 * g + 112 * b + 128 shr 8) + 128).toByte()
            out[uv++] = clamp8((112 * r - 94 * g - 18 * b + 128 shr 8) + 128).toByte()
            col += 2
        }
        row += 2
    }
}

private fun clamp8(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

