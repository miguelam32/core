package com.maxrave.media3.extension

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

// MZKXLF-HOOK: no borrar en merge - exporta letra y posicion en tiempo real por UDP a Termux-X11
internal object LyricsUdpExporter {
    private const val PORT = 4212
    private val socket by lazy { DatagramSocket() }

    fun sendMeta(videoId: String, index: Int, startMs: Long, text: String) {
        send("META|$videoId|$index|$startMs|$text")
    }

    fun sendPosition(positionMs: Long) {
        send("POS|$positionMs")
    }

    fun sendDebug(msg: String) {
        send("DBG|$msg")
    }

    private fun send(payloadText: String) {
        Thread {
            try {
                val payload = payloadText.toByteArray(Charsets.UTF_8)
                val packet = DatagramPacket(
                    payload, payload.size,
                    InetAddress.getByName("127.0.0.1"), PORT,
                )
                socket.send(packet)
            } catch (_: Exception) { }
        }.start()
    }
}
