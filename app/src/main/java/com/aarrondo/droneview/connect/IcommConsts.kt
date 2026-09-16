package com.aarrondo.droneview.connect

object IcommConsts {
    const val DRONE_HOST = "192.168.0.1"
    const val DRONE_SSID = "udirc-WiFi-9787CB"
    const val GROUND_PORT = 6000
    const val DRONE_PORT = 40000
    const val FRAME_TIMEOUT_MS = 3000L
    const val KEEPALIVE_INTERVAL_MS = 1000L
    val KEEPALIVE: ByteArray = byteArrayOf(0x63,0x63,0x01,0x00,0x00,0x00,0x00)
    const val HEADER_OFFSET = 54
    const val FRAME_ID_OFFSET = 8
    const val WIRE_LEN_OFFSET = 5
    const val CMD_VIDEO = 0x03
}
