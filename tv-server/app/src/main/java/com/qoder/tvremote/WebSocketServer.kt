package com.qoder.tvremote

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 一个够用的 WebSocket 服务端（RFC 6455 子集），只实现 uniapp 手机端需要的能力：
 * 握手、文本帧双向收发、ping/pong、close。
 *
 * 为什么不用 OkHttp/Netty？
 *   电视盒子 ROM 五花八门，APK 越轻越好；这里用到的帧类型很单一（短 JSON 文本），
 *   自己实现 200 行比引入 2MB 依赖更合适。
 *
 * 为什么手机端不直接用裸 TCP？
 *   uniapp 在 Android 上用 plus.android 调 java.net.Socket 时，readLine() 是阻塞调用，
 *   会把 JS 线程卡死；而 uni.connectSocket 是原生异步实现，事件回调不占 UI 线程。
 */
class WebSocketServer(
    private val port: Int,
    private val listener: Listener
) {

    interface Listener {
        fun onOpen(client: Client)
        fun onText(client: Client, text: String)
        fun onClosed(client: Client)
        fun onCountChanged(count: Int)
        fun onState(state: TcpServer.State, message: String?)
    }

    inner class Client internal constructor(
        val socket: Socket,
        val id: Int
    ) : RemoteClient {

        override val label: String = "ws#$id"

        @Volatile
        override var deviceName: String = "手机"

        private val out: OutputStream = socket.getOutputStream()
        private val writeLock = Any()

        @Volatile
        internal var closed = false

        override fun send(obj: JSONObject) {
            sendText(obj.toString())
        }

        fun sendText(text: String) {
            sendFrame(OPCODE_TEXT, text.toByteArray(Charsets.UTF_8))
        }

        /** 统一入口：所有写帧都在同一把锁内完成，避免心跳帧与业务帧交错 */
        internal fun sendFrame(opcode: Int, payload: ByteArray) {
            if (closed) return
            synchronized(writeLock) {
                try {
                    writeFrame(out, opcode, payload)
                } catch (e: Throwable) {
                    Log.w(TAG, "ws write failed: ${e.message}")
                    closeQuietly()
                }
            }
        }

        internal fun closeQuietly() {
            closed = true
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newCachedThreadPool()
    private val clients = CopyOnWriteArrayList<Client>()

    @Volatile
    private var running = false
    private var nextId = 0

    val clientCount: Int get() = clients.size

    fun start() {
        if (running) return
        running = true
        listener.onState(TcpServer.State.STARTING, null)
        acceptThread = Thread({ acceptLoop() }, "tvremote-ws").apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop() {
        var ss: ServerSocket? = null
        try {
            ss = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port), 8)
            }
            serverSocket = ss
            listener.onState(TcpServer.State.LISTENING, null)
            Log.i(TAG, "WebSocket listening on $port")

            while (running) {
                val socket = try {
                    ss.accept()
                } catch (e: IOException) {
                    if (running) Log.w(TAG, "ws accept failed: ${e.message}")
                    break
                }
                socket.tcpNoDelay = true
                val id = synchronized(this) { nextId++ }
                pool.execute { serve(socket, id) }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "ws bind failed", e)
            listener.onState(TcpServer.State.ERROR, e.message ?: e.javaClass.simpleName)
        } finally {
            try {
                ss?.close()
            } catch (_: Throwable) {
            }
            if (running) {
                running = false
                listener.onState(TcpServer.State.STOPPED, null)
            }
        }
    }

    private fun serve(socket: Socket, id: Int) {
        var client: Client? = null
        try {
            val raw = socket.getInputStream()
            val header = readHttpHeader(raw) ?: run {
                socket.close()
                return
            }
            val key = headerValue(header, "sec-websocket-key")
            if (key == null) {
                writeRaw(socket.getOutputStream(), "HTTP/1.1 400 Bad Request\r\n\r\n")
                socket.close()
                return
            }

            val accept = Base64.encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + WS_MAGIC).toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP
            )
            val response = buildString {
                append("HTTP/1.1 101 Switching Protocols\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Accept: ").append(accept).append("\r\n")
                append("\r\n")
            }
            writeRaw(socket.getOutputStream(), response)

            val c = Client(socket, id)
            client = c
            clients.add(c)
            listener.onOpen(c)
            listener.onCountChanged(clients.size)
            Log.i(TAG, "ws client #$id connected")

            frameLoop(c, DataInputStream(BufferedInputStream(raw)))
        } catch (e: Throwable) {
            Log.w(TAG, "ws serve error: ${e.message}")
        } finally {
            client?.let {
                clients.remove(it)
                it.closeQuietly()
                listener.onClosed(it)
                listener.onCountChanged(clients.size)
            }
            if (client == null) {
                try {
                    socket.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun frameLoop(client: Client, input: DataInputStream) {
        val buffer = ByteArrayOutputStream()
        while (running && !client.closed) {
            val frame = readFrame(input) ?: break

            when (frame.opcode) {
                OPCODE_TEXT, OPCODE_CONTINUATION -> {
                    buffer.write(frame.payload)
                    if (frame.fin) {
                        val text = buffer.toString("UTF-8")
                        buffer.reset()
                        if (text.isNotBlank()) {
                            // ping 帧内容也走这里，语义上直接交给上层
                            listener.onText(client, text)
                        }
                    }
                }

                OPCODE_PING -> client.sendFrame(OPCODE_PONG, frame.payload)

                OPCODE_PONG -> Unit

                OPCODE_CLOSE -> {
                    client.sendFrame(OPCODE_CLOSE, ByteArray(0))
                    break
                }

                else -> break
            }
        }
    }

    /**
     * 读 HTTP 握手头。必须逐字节读，不能用 BufferedReader：
     * BufferedReader 会预读进缓冲区，把紧随其后的 WebSocket 帧数据吃掉。
     */
    private fun readHttpHeader(input: InputStream): String? {
        val sb = StringBuilder()
        var consecutive = 0
        while (sb.length < MAX_HEADER) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            sb.append(b.toChar())
            // 连续出现 4 个 CR/LF 就是 "\r\n\r\n"，握手头结束
            if (b == '\r'.code || b == '\n'.code) {
                consecutive++
                if (consecutive >= 4) break
            } else {
                consecutive = 0
            }
        }
        return sb.toString()
    }

    private fun headerValue(header: String, name: String): String? {
        for (line in header.split("\r\n")) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            if (line.substring(0, idx).trim().equals(name, ignoreCase = true)) {
                return line.substring(idx + 1).trim()
            }
        }
        return null
    }

    private class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    private fun readFrame(input: DataInputStream): Frame? {
        val b0 = try {
            input.readUnsignedByte()
        } catch (e: Throwable) {
            return null
        }
        val b1 = input.readUnsignedByte()

        val fin = (b0 and 0x80) != 0
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var length = (b1 and 0x7F).toLong()

        if (length == 126L) {
            length = input.readUnsignedShort().toLong()
        } else if (length == 127L) {
            length = input.readLong()
        }
        if (length < 0 || length > MAX_FRAME) {
            throw IOException("bad frame length $length")
        }

        val maskKey = if (masked) ByteArray(4).also { input.readFully(it) } else null
        val payload = ByteArray(length.toInt())
        input.readFully(payload)

        if (maskKey != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }
        return Frame(fin, opcode, payload)
    }

    private fun writeFrame(out: OutputStream, opcode: Int, payload: ByteArray) {
        val header = ByteArrayOutputStream()
        header.write(0x80 or opcode) // FIN=1
        val len = payload.size
        when {
            len < 126 -> header.write(len)
            len < 65536 -> {
                header.write(126)
                header.write((len ushr 8) and 0xFF)
                header.write(len and 0xFF)
            }

            else -> {
                header.write(127)
                for (i in 7 downTo 0) header.write(((len.toLong() ushr (8 * i)) and 0xFF).toInt())
            }
        }
        header.write(payload)
        out.write(header.toByteArray())
        out.flush()
    }

    private fun writeRaw(out: OutputStream, text: String) {
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (_: Throwable) {
        }
    }

    fun broadcast(obj: JSONObject) {
        for (c in clients) c.send(obj)
    }

    fun stop() {
        if (!running) return
        running = false
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        for (c in clients) c.closeQuietly()
        clients.clear()
        listener.onCountChanged(0)
        listener.onState(TcpServer.State.STOPPED, null)
    }

    private companion object {
        const val TAG = "WebSocketServer"
        const val WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val MAX_FRAME = 64 * 1024
        const val MAX_HEADER = 8 * 1024

        const val OPCODE_CONTINUATION = 0x0
        const val OPCODE_TEXT = 0x1
        const val OPCODE_BINARY = 0x2
        const val OPCODE_CLOSE = 0x8
        const val OPCODE_PING = 0x9
        const val OPCODE_PONG = 0xA
    }
}
