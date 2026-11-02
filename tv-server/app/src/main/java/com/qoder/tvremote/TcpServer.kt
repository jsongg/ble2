package com.qoder.tvremote

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 轻量 TCP 服务端：一个读线程处理一个客户端，命令回调给上层。
 *
 * 线程模型：
 *   accept 线程 -> 每连接一个阻塞读线程 -> 回调 Controller（Controller 内部切主线程做注入）
 * 回包走 PrintWriter，直接在当前线程写（短小消息，不会阻塞）。
 */
class TcpServer(
    private val port: Int,
    private val listener: Listener
) {
    interface Listener {
        /** 收到一条业务命令（已解析为 JSON） */
        fun onCommand(msg: JSONObject, client: Client)

        /** 客户端数量变化（在子线程回调） */
        fun onClientCountChanged(count: Int)

        /** 服务器状态变化，例如启动失败 */
        fun onServerState(state: State, message: String?)
    }

    enum class State { STARTING, LISTENING, STOPPED, ERROR }

    inner class Client internal constructor(
        val socket: Socket,
        val id: Int
    ) : RemoteClient {
        val remote: String = "${socket.inetAddress?.hostAddress ?: "?"}:${socket.port}"

        override val label: String = "tcp#$id"

        @Volatile
        override var deviceName: String = "手机"

        /** 最近一次心跳时间，用于判断假连接 */
        @Volatile
        var lastSeen: Long = System.currentTimeMillis()

        private val writer: PrintWriter = PrintWriter(
            OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), false
        )

        /** 发送一条 JSON（自动追加换行），失败则关闭连接 */
        @Synchronized
        override fun send(obj: JSONObject) {
            try {
                writer.println(obj.toString())
                writer.flush()
            } catch (e: Throwable) {
                closeQuietly()
            }
        }

        internal fun isAlive(): Boolean = !socket.isClosed && socket.isConnected

        internal fun closeQuietly() {
            try {
                writer.close()
            } catch (_: Throwable) {
            }
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
    private var nextClientId = 0

    val clientList: List<Client> get() = clients
    val clientCount: Int get() = clients.size

    fun start() {
        if (running) return
        running = true
        listener.onServerState(State.STARTING, null)

        acceptThread = Thread({ acceptLoop() }, "tvremote-accept").apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop() {
        var ss: ServerSocket? = null
        try {
            ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port), 8)
            serverSocket = ss
            listener.onServerState(State.LISTENING, null)
            Log.i(TAG, "TCP listening on $port")

            while (running) {
                val socket = try {
                    ss.accept()
                } catch (e: IOException) {
                    if (running) Log.w(TAG, "accept failed: ${e.message}")
                    break
                }
                handleNewClient(socket)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "bind failed", e)
            listener.onServerState(State.ERROR, e.message ?: e.javaClass.simpleName)
        } finally {
            try {
                ss?.close()
            } catch (_: Throwable) {
            }
            serverSocket = null
            if (running) {
                running = false
                listener.onServerState(State.STOPPED, null)
            }
        }
    }

    private fun handleNewClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 0 // 阻塞读，不设超时；断线由 readLine 返回 null 感知
        } catch (_: Throwable) {
        }

        val id = synchronized(this) { nextClientId++ }
        val client = Client(socket, id)
        clients.add(client)
        listener.onClientCountChanged(clients.size)
        Log.i(TAG, "client #$id connected $client")

        pool.execute { clientLoop(client) }
    }

    private fun clientLoop(client: Client) {
        try {
            val reader = BufferedReader(InputStreamReader(client.socket.getInputStream(), Charsets.UTF_8))
            while (running && client.isAlive()) {
                val line = try {
                    reader.readLine()
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: IOException) {
                    null
                }
                if (line == null) break
                if (line.isBlank()) continue

                client.lastSeen = System.currentTimeMillis()

                val msg = try {
                    JSONObject(line)
                } catch (e: Throwable) {
                    client.send(JSONObject().put("t", "error").put("msg", "bad json"))
                    continue
                }

                try {
                    listener.onCommand(msg, client)
                } catch (e: Throwable) {
                    Log.e(TAG, "handle command failed", e)
                }
            }
        } catch (_: Throwable) {
        } finally {
            clients.remove(client)
            client.closeQuietly()
            listener.onClientCountChanged(clients.size)
            Log.i(TAG, "client #${client.id} disconnected, remain=${clients.size}")
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
        listener.onClientCountChanged(0)
        listener.onServerState(State.STOPPED, null)
        Log.i(TAG, "TCP server stopped")
    }

    private companion object {
        const val TAG = "TcpServer"
    }
}
