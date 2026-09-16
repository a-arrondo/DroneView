package com.aarrondo.droneview.connect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Network
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import kotlin.time.Duration.Companion.milliseconds

class UdpVideoSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _frame = MutableStateFlow<Bitmap?>(null)
    val frame: StateFlow<Bitmap?> = _frame

    private val _framesReceived = MutableStateFlow(0)
    val framesReceived: StateFlow<Int> = _framesReceived

    private val _framesDropped = MutableStateFlow(0)

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming

    private var socket: DatagramSocket? = null
    private var recvJob: Job? = null
    private var keepaliveJob: Job? = null
    @Volatile private var running = false

    fun restart(network: Network?) {
        synchronized(lock) {
            stopLocked()
            running = true
            val sock = try {
                createBoundSocket(network)
            } catch (e: Exception) {
                Log.e(TAG, "bind 0.0.0.0:${IcommConsts.GROUND_PORT} failed", e)
                running = false
                return
            }
            socket = sock
            _isStreaming.value = false
            keepaliveJob = scope.launch { keepaliveLoop(sock) }
            recvJob = scope.launch { receiveLoop(sock) }
        }
    }

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private fun stopLocked() {
        running = false
        recvJob?.cancel(); recvJob = null
        keepaliveJob?.cancel(); keepaliveJob = null
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    private fun createBoundSocket(network: Network?): DatagramSocket {
        val sock = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(IcommConsts.GROUND_PORT))
        }
        if (network != null) {
            try {
                network.bindSocket(sock)
                Log.i(TAG, "socket bound to drone network $network")
            } catch (e: Exception) {
                Log.w(TAG, "bindSocket(drone network) failed, keeping socket", e)
            }
        } else {
            Log.w(TAG, "no drone network; listening unbound (keepalive may route via mobile)")
        }
        return sock
    }

    private suspend fun keepaliveLoop(sock: DatagramSocket) {
        val droneAddr = try {
            withContext(Dispatchers.IO) {
                InetAddress.getByName(IcommConsts.DRONE_HOST)
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolve ${IcommConsts.DRONE_HOST} failed", e)
            return
        }
        while (scope.isActive && running) {
            try {
                val pkt = DatagramPacket(
                    IcommConsts.KEEPALIVE, IcommConsts.KEEPALIVE.size,
                    droneAddr, IcommConsts.DRONE_PORT
                )
                withContext(Dispatchers.IO) {
                    sock.send(pkt)
                }
            } catch (e: SocketException) {
                if (!running) return
                Log.w(TAG, "keepalive send failed", e)
            } catch (e: IOException) {
                Log.w(TAG, "keepalive send failed", e)
            }
            delay(IcommConsts.KEEPALIVE_INTERVAL_MS.milliseconds)
        }
    }

    private suspend fun receiveLoop(sock: DatagramSocket) {
        val assembler = IcommAssembler()
        val buf = ByteArray(RECV_BUF_SIZE)
        var lastDropped = 0L
        var lastCoalescedDatagrams = 0L
        var lastCoalescedSegments = 0L
        var lastWireLenMismatches = 0L
        var lastPayloadMismatch = 0L
        var lastResyncs = 0L
        while (scope.isActive && running) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                withContext(Dispatchers.IO) {
                    sock.receive(pkt)
                }
                if (pkt.length >= buf.size) {
                    Log.w(TAG, "possible UDP truncation (${pkt.length}B)")
                }
                val datagramLen = pkt.length
                val jpeg = assembler.feed(pkt.data, datagramLen)
                if (assembler.framesDropped != lastDropped) {
                    _framesDropped.value += (assembler.framesDropped - lastDropped).toInt()
                    lastDropped = assembler.framesDropped
                }
                // Hypothesis-1 discrimination logging: one UDP datagram may hold
                // several segments. Log whenever coalescing / wire-len anomalies appear
                // so a port-6000 pcap histogram (len, declaredLen vs avail, 63 63 at off>0)
                // can be correlated with in-app counters.
                if (assembler.coalescedDatagrams != lastCoalescedDatagrams ||
                    assembler.coalescedSegments != lastCoalescedSegments
                ) {
                    Log.i(
                        TAG,
                        "coalesced datagram len=${datagramLen}B " +
                            "coalescedDatagrams=${assembler.coalescedDatagrams} " +
                            "coalescedSegments=${assembler.coalescedSegments} " +
                            "segmentsParsed=${assembler.segmentsParsed}"
                    )
                    lastCoalescedDatagrams = assembler.coalescedDatagrams
                    lastCoalescedSegments = assembler.coalescedSegments
                }
                if (assembler.wireLenMismatches != lastWireLenMismatches ||
                    assembler.payloadLenMismatches != lastPayloadMismatch ||
                    assembler.resyncs != lastResyncs
                ) {
                    Log.i(
                        TAG,
                        "segment anomaly len=${datagramLen}B " +
                            "wireLenMismatches=${assembler.wireLenMismatches} " +
                            "payloadLenMismatches=${assembler.payloadLenMismatches} " +
                            "resyncs=${assembler.resyncs}"
                    )
                    lastWireLenMismatches = assembler.wireLenMismatches
                    lastPayloadMismatch = assembler.payloadLenMismatches
                    lastResyncs = assembler.resyncs
                }
                if (jpeg == null) continue
                val bmp = try {
                    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                } catch (e: Exception) {
                    Log.w(TAG, "JPEG decode failed (${jpeg.size}B)", e)
                    null
                }
                if (bmp != null) {
                    if (bmp.width != EXPECTED_WIDTH || bmp.height != EXPECTED_HEIGHT) {
                        Log.w(TAG, "drop wrong size ${bmp.width}x${bmp.height} (${jpeg.size}B)")
                        try {
                            bmp.recycle()
                        } catch (_: Exception) {
                        }
                        _framesDropped.value += 1
                    } else {
                        _frame.value = bmp
                        _framesReceived.value += 1
                        if (!_isStreaming.value) _isStreaming.value = true
                    }
                } else {
                    _framesDropped.value += 1
                }
            } catch (e: SocketException) {
                if (!running) return
                Log.w(TAG, "receive socket closed/error", e)
                delay(RETRY_DELAY_MS.milliseconds)
            } catch (e: IOException) {
                if (!running) return
                Log.w(TAG, "receive failed", e)
                delay(RETRY_DELAY_MS.milliseconds)
            }
        }
    }

    companion object {
        private const val TAG = "DroneView"
        private const val RECV_BUF_SIZE = 65507
        private const val RETRY_DELAY_MS = 200L
        private const val EXPECTED_WIDTH = 640
        private const val EXPECTED_HEIGHT = 480
    }
}

