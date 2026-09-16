package com.aarrondo.droneview.connect

class IcommAssembler(
    private val expectedWidth: Int = DEFAULT_WIDTH,
    private val expectedHeight: Int = DEFAULT_HEIGHT,
    private val frameTimeoutMs: Long = IcommConsts.FRAME_TIMEOUT_MS,
) {
    var framesEmitted: Long = 0L
        private set
    var framesDropped: Long = 0L
        private set
    var framesRepaired: Long = 0L
        private set
    var coalescedDatagrams: Long = 0L
        private set
    var coalescedSegments: Long = 0L
        private set
    var segmentsParsed: Long = 0L
        private set
    var wireLenMismatches: Long = 0L
        private set
    var payloadLenMismatches: Long = 0L
        private set
    var resyncs: Long = 0L
        private set

    private var pendingFrameId: Int? = null
    private var pendingTotal: Int = 0
    private val pendingFrags = mutableMapOf<Int, ByteArray>()
    private var pendingFirstSeenMs: Long = 0L
    private var lastEmittedId: Int? = null

    private var carry = ByteArray(0)

    private data class Segment(
        val frameId: Int,
        val fragIndex: Int,
        val total: Int,
        val payload: ByteArray,
    )

    fun feed(data: ByteArray, length: Int = data.size, nowMs: Long = System.currentTimeMillis()): ByteArray? {
        val len = length.coerceIn(0, data.size)
        if (len <= 0) return null

        val buf: ByteArray = if (carry.isNotEmpty()) {
            val combined = ByteArray(carry.size + len)
            System.arraycopy(carry, 0, combined, 0, carry.size)
            System.arraycopy(data, 0, combined, carry.size, len)
            carry = ByteArray(0)
            combined
        } else {
            // Copy: caller reuses the buffer (UdpVideoSource 64 KiB buf).
            val copy = ByteArray(len)
            System.arraycopy(data, 0, copy, 0, len)
            copy
        }
        if (buf.isEmpty()) return null

        val segments = parseDatagram(buf)
        if (segments.size > 1) {
            coalescedDatagrams += 1
            coalescedSegments += (segments.size - 1).toLong()
        }

        var emitted: ByteArray? = null
        for (seg in segments) {
            val jpeg = offerSegment(seg, nowMs)
            if (jpeg != null) emitted = jpeg
        }
        return emitted
    }

    private fun parseDatagram(buf: ByteArray): List<Segment> {
        val out = mutableListOf<Segment>()
        var pos = 0
        while (pos < buf.size) {
            val remaining = buf.size - pos
            if (remaining < IcommConsts.HEADER_OFFSET) {
                if (remaining >= 2 &&
                    buf[pos] == MAGIC_0 && buf[pos + 1] == MAGIC_1 &&
                    remaining >= 3 &&
                    (buf[pos + 2].toInt() and 0xFF) == IcommConsts.CMD_VIDEO
                ) {
                    carry = buf.copyOfRange(pos, buf.size)
                }
                break
            }
            if (buf[pos] != MAGIC_0 || buf[pos + 1] != MAGIC_1) {
                val next = indexOfMagic(buf, pos + 1)
                if (next == -1) break
                if (out.isNotEmpty()) wireLenMismatches += 1
                resyncs += 1
                pos = next
                continue
            }
            val cmd = buf[pos + 2].toInt() and 0xFF
            if (cmd != IcommConsts.CMD_VIDEO) {
                val wl = readLe16(buf, pos + IcommConsts.WIRE_LEN_OFFSET)
                if (wl >= IcommConsts.HEADER_OFFSET && wl <= remaining) {
                    pos += wl
                    continue
                }
                val next = indexOfMagic(buf, pos + 2)
                if (next == -1) break
                resyncs += 1
                pos = next
                continue
            }
            val wireLen = readLe16(buf, pos + IcommConsts.WIRE_LEN_OFFSET)
            if (wireLen < IcommConsts.HEADER_OFFSET) {
                wireLenMismatches += 1
                val next = indexOfMagic(buf, pos + 2)
                if (next == -1) break
                resyncs += 1
                pos = next
                continue
            }
            if (pos + wireLen > buf.size) {
                carry = buf.copyOfRange(pos, buf.size)
                break
            }
            val frameId = readLe32(buf, pos + IcommConsts.FRAME_ID_OFFSET)
            val fragIndex = readLe16(buf, pos + FRAG_INDEX_OFFSET)
            val total = readLe16(buf, pos + TOTAL_OFFSET)
            val declaredLen = readLe16(buf, pos + DECLARED_LEN_OFFSET)
            val avail = wireLen - IcommConsts.HEADER_OFFSET
            val effectiveLen = minOf(declaredLen, avail).coerceIn(0, avail)
            if (declaredLen != avail) payloadLenMismatches += 1

            val payload = ByteArray(effectiveLen)
            if (effectiveLen > 0) {
                System.arraycopy(buf, pos + IcommConsts.HEADER_OFFSET, payload, 0, effectiveLen)
            }
            segmentsParsed += 1
            out.add(Segment(frameId, fragIndex, total, payload))
            pos += wireLen
        }
        return out
    }
    private fun offerSegment(seg: Segment, nowMs: Long): ByteArray? {
        val pendingId = pendingFrameId
        if (pendingId != null && nowMs - pendingFirstSeenMs > frameTimeoutMs) {
            framesDropped += 1
            clearPending()
        }
        if (seg.frameId == lastEmittedId) return null

        if (seg.total <= 0 || seg.fragIndex <= 0 || seg.fragIndex > seg.total) return null

        val currentPending = pendingFrameId
        if (currentPending == null) {
            startPending(seg, nowMs)
            return tryEmit(seg.frameId)
        }
        if (currentPending != seg.frameId) {
            framesDropped += 1
            clearPending()
            startPending(seg, nowMs)
            return tryEmit(seg.frameId)
        }

        if (seg.total != pendingTotal) return null // inconsistent total: keep first.
        if (pendingFrags.containsKey(seg.fragIndex)) return null // duplicate.
        pendingFrags[seg.fragIndex] = seg.payload
        return tryEmit(seg.frameId)
    }

    private fun startPending(seg: Segment, nowMs: Long) {
        pendingFrameId = seg.frameId
        pendingTotal = seg.total
        pendingFrags.clear()
        pendingFrags[seg.fragIndex] = seg.payload
        pendingFirstSeenMs = nowMs
    }

    private fun clearPending() {
        pendingFrameId = null
        pendingTotal = 0
        pendingFrags.clear()
    }

    private fun tryEmit(frameId: Int): ByteArray? {
        if (pendingFrameId != frameId) return null
        if (pendingFrags.size != pendingTotal) return null
        for (i in 1..pendingTotal) {
            if (!pendingFrags.containsKey(i)) return null
        }
        var totalBytes = 0
        for (i in 1..pendingTotal) totalBytes += pendingFrags[i]!!.size
        val assembled = ByteArray(totalBytes)
        var off = 0
        for (i in 1..pendingTotal) {
            val part = pendingFrags[i]!!
            System.arraycopy(part, 0, assembled, off, part.size)
            off += part.size
        }
        val result = validateAndRepairJpeg(assembled)
        if (result == null) {
            framesDropped += 1
            clearPending()
            return null
        }
        if (result.repaired) framesRepaired += 1
        framesEmitted += 1
        clearPending()
        lastEmittedId = frameId
        return result.data
    }

    private data class JpegResult(val data: ByteArray, val repaired: Boolean)

    private fun validateAndRepairJpeg(input: ByteArray): JpegResult? {
        if (input.size < MIN_JPEG_SIZE) return null
        if (input[0] != 0xFF.toByte() || input[1] != 0xD8.toByte()) return null
        if (input[input.size - 2] != 0xFF.toByte() || input[input.size - 1] != 0xD9.toByte()) return null

        var pos = 2
        var sofFound = false
        var entropyStart = -1
        while (pos + 1 < input.size) {
            if (input[pos] != 0xFF.toByte()) return null
            var mPos = pos
            while (mPos + 1 < input.size &&
                input[mPos] == 0xFF.toByte() &&
                input[mPos + 1] == 0xFF.toByte()
            ) {
                mPos += 1
            }
            if (mPos + 1 >= input.size) return null
            val marker = input[mPos + 1].toInt() and 0xFF
            if (marker == 0xD8) {
                pos = mPos + 2
                continue
            }
            if (marker == 0xD9) return null
            if (marker == 0x01 || marker in 0xD0..0xD7) {
                pos = mPos + 2
                continue
            }
            if (mPos + 3 >= input.size) return null
            val length = ((input[mPos + 2].toInt() and 0xFF) shl 8) or (input[mPos + 3].toInt() and 0xFF)
            if (length < 2 || mPos + 2 + length > input.size) return null
            if (marker in SOF_MARKERS) {
                if (length < 8 || mPos + 8 >= input.size) return null
                val h = ((input[mPos + 5].toInt() and 0xFF) shl 8) or (input[mPos + 6].toInt() and 0xFF)
                val w = ((input[mPos + 7].toInt() and 0xFF) shl 8) or (input[mPos + 8].toInt() and 0xFF)
                if (w != expectedWidth || h != expectedHeight) return null
                sofFound = true
            } else if (marker == SOS_MARKER) {
                entropyStart = mPos + 2 + length
                break
            }
            pos = mPos + 2 + length
        }
        if (!sofFound || entropyStart == -1 || entropyStart > input.size - 2) return null

        val end = input.size - 2
        val barePositions = mutableListOf<Int>()
        var i = entropyStart
        while (i < end) {
            if (input[i] != 0xFF.toByte()) {
                i += 1
                continue
            }
            if (i + 1 >= input.size) return null
            val nxt = input[i + 1].toInt() and 0xFF
            when (nxt) {
                0x00 -> i += 2
                in 0xD0..0xD7 -> i += 2
                0xD9 -> return null
                0xFF -> i += 1
                0x01 -> i += 2
                in 0xC0..0xFE -> return null
                in 0x02..0xBF -> {
                    barePositions.add(i)
                    i += 2
                }

                else -> return null
            }
        }
        if (barePositions.isEmpty()) return JpegResult(input, false)

        val repaired = ByteArray(input.size - barePositions.size)
        var src = 0
        var dst = 0
        var bareIdx = 0
        while (src < input.size) {
            if (bareIdx < barePositions.size && src == barePositions[bareIdx]) {
                src += 1
                bareIdx += 1
                continue
            }
            repaired[dst++] = input[src++]
        }
        if (dst != repaired.size) return null
        if (repaired[0] != 0xFF.toByte() || repaired[1] != 0xD8.toByte()) return null
        if (repaired[repaired.size - 2] != 0xFF.toByte() || repaired[repaired.size - 1] != 0xD9.toByte()) return null
        return JpegResult(repaired, true)
    }

    companion object {
        const val DEFAULT_WIDTH = 640
        const val DEFAULT_HEIGHT = 480
        private const val MIN_JPEG_SIZE = 4
        private const val FRAG_INDEX_OFFSET = 48
        private const val TOTAL_OFFSET = 50
        private const val DECLARED_LEN_OFFSET = 52
        private const val SOS_MARKER = 0xDA
        private const val MAGIC_0 = 0x63.toByte()
        private const val MAGIC_1 = 0x63.toByte()
        private val SOF_MARKERS = setOf(
            0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7,
            0xC9, 0xCB, 0xCD, 0xCE, 0xCF
        )

        private fun readLe16(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

        private fun readLe32(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or
                ((b[off + 3].toInt() and 0xFF) shl 24)

        private fun indexOfMagic(buf: ByteArray, from: Int): Int {
            var k = from.coerceAtLeast(0)
            while (k + 1 < buf.size) {
                if (buf[k] == MAGIC_0 && buf[k + 1] == MAGIC_1) return k
                k += 1
            }
            return -1
        }
    }
}



