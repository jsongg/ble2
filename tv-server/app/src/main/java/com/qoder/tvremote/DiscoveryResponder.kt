package com.qoder.tvremote

import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * UDP 广播应答器：手机端在局域网广播 [Proto.DISCOVER_REQUEST]，
 * 本类收到后原样回复 "TVREMOTE_HERE_V1 {json}"，让手机端自动填好 IP。
 *
 * 注意：电视端只要绑定 8889 端口即可收到广播；不需要 joinMulticastGroup，
 * 因为手机端发送的是 255.255.255.255 定向广播。
 */
class DiscoveryResponder(
    private val port: Int,
    private val payloadProvider: () -> JSONObject
) {
    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var socket: DatagramSocket? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "tvremote-discovery").apply {
            isDaemon = true
            start()
        }
    }

    private fun loop() {
        var ds: DatagramSocket? = null
        try {
            ds = DatagramSocket(null)
            ds.reuseAddress = true
            ds.broadcast = true
            ds.bind(java.net.InetSocketAddress(port))
            ds.soTimeout = 1000
            socket = ds
            Log.i(TAG, "discovery listening on $port")

            val buf = ByteArray(256)
            while (running) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    ds.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Throwable) {
                    if (running) Log.w(TAG, "receive failed: ${e.message}")
                    break
                }

                val text = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                if (!text.startsWith(Proto.DISCOVER_REQUEST)) continue

                val replyText = Proto.DISCOVER_REPLY_PREFIX + payloadProvider().toString()
                val reply = replyText.toByteArray(Charsets.UTF_8)
                try {
                    ds.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                    Log.i(TAG, "replied discovery to ${packet.address.hostAddress}")
                } catch (e: Throwable) {
                    Log.w(TAG, "reply failed: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "discovery failed", e)
        } finally {
            try {
                ds?.close()
            } catch (_: Throwable) {
            }
            socket = null
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        thread?.interrupt()
        thread = null
    }

    private companion object {
        const val TAG = "Discovery"
    }
}
