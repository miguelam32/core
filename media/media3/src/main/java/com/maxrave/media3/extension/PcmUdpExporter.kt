package com.maxrave.media3.extension

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingDeque

internal object PcmUdpExporter {
    private const val PORT = 4213
    private const val QUEUE_CAPACITY = 64
    private const val TAG_FORMAT: Byte = 0x01
    private const val TAG_AUDIO: Byte = 0x02
    private const val TAG_TRANSITION: Byte = 0x03

    private val socket by lazy { DatagramSocket() }
    private val address by lazy { InetAddress.getByName("127.0.0.1") }
    private val queue = LinkedBlockingDeque<ByteArray>(QUEUE_CAPACITY)

    @Volatile
    private var workerStarted = false

    private fun ensureWorkerStarted() {
        if (workerStarted) return
        synchronized(this) {
            if (workerStarted) return
            workerStarted = true
            Thread({
                while (true) {
                    try {
                        val payload = queue.take()
                        val packet = DatagramPacket(payload, payload.size, address, PORT)
                        socket.send(packet)
                    } catch (_: InterruptedException) {
                        return@Thread
                    } catch (_: Exception) {
                    }
                }
            }, "PcmUdpExporter-worker").apply { isDaemon = true }.start()
        }
    }

    fun sendFormat(
        sampleRate: Int,
        channelCount: Int,
    ) {
        ensureWorkerStarted()
        val text = "$sampleRate|$channelCount".toByteArray(Charsets.UTF_8)
        val payload = ByteArray(1 + text.size)
        payload[0] = TAG_FORMAT
        System.arraycopy(text, 0, payload, 1, text.size)
        offer(payload)
    }

    fun sendAudio(pcm: ByteArray) {
        ensureWorkerStarted()
        val payload = ByteArray(1 + pcm.size)
        payload[0] = TAG_AUDIO
        System.arraycopy(pcm, 0, payload, 1, pcm.size)
        offer(payload)
    }

    /** Aviso de un solo byte: arranco el efecto de crossfade/DJ hacia la siguiente cancion. */
    fun sendTransition() {
        ensureWorkerStarted()
        offer(byteArrayOf(TAG_TRANSITION))
    }

    private fun offer(payload: ByteArray) {
        if (!queue.offer(payload)) {
            queue.pollFirst()
            queue.offer(payload)
        }
    }
}
