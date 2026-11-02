package com.qoder.tvremote

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject

/**
 * 电视端的核心：一个常驻的无障碍服务。
 *
 * 它同时负责三件事：
 *   1. 被系统当作无障碍服务加载（从而获得 dispatchGesture / performGlobalAction 能力）
 *   2. 启动 TCP 服务 + UDP 发现应答，接收手机指令
 *   3. 维护屏幕上的虚拟鼠标指针
 */
class RemoteAccessibilityService : AccessibilityService() {

    private var overlay: CursorOverlay? = null
    private var injector: Injector? = null
    private var controller: CommandController? = null
    private var server: TcpServer? = null
    private var wsServer: WebSocketServer? = null
    private var discovery: DiscoveryResponder? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "onServiceConnected")

        val ov = CursorOverlay(this)
        val inj = Injector(this)
        overlay = ov
        injector = inj

        // 只测量尺寸，不立即显示光标：等手机真正开始操作时再懒加载
        val (w, h) = ov.measureScreen()
        CursorOverlayHolder.update(w, h)

        val tcp = TcpServer(Proto.TCP_PORT, object : TcpServer.Listener {
            override fun onCommand(msg: JSONObject, client: TcpServer.Client) {
                controller?.handle(msg, client)
                if (msg.optString("t") != "move") {
                    RemoteState.setLastCommand(msg.toString())
                }
            }

            override fun onClientCountChanged(count: Int) {
                updateClientCount()
            }

            override fun onServerState(state: TcpServer.State, message: String?) {
                when (state) {
                    TcpServer.State.LISTENING -> RemoteState.serverListening(true)
                    TcpServer.State.ERROR -> RemoteState.serverListening(false, message)
                    TcpServer.State.STOPPED -> RemoteState.serverListening(false)
                    TcpServer.State.STARTING -> Unit
                }
            }
        })

        controller = CommandController(ov, inj) { deviceInfo() }
        server = tcp
        tcp.start()

        // WebSocket 通道：uniapp 手机端走这里
        val ws = WebSocketServer(Proto.WS_PORT, object : WebSocketServer.Listener {
            override fun onOpen(client: WebSocketServer.Client) {
                Log.i(TAG, "ws open ${client.label}")
            }

            override fun onText(client: WebSocketServer.Client, text: String) {
                val msg = try {
                    JSONObject(text)
                } catch (t: Throwable) {
                    client.send(JSONObject().put("t", "error").put("msg", "bad json"))
                    return
                }
                controller?.handle(msg, client)
                if (msg.optString("t") != "move") {
                    RemoteState.setLastCommand(msg.toString())
                }
            }

            override fun onClosed(client: WebSocketServer.Client) {
                Log.i(TAG, "ws closed ${client.label}")
            }

            override fun onCountChanged(count: Int) {
                updateClientCount()
            }

            override fun onState(state: TcpServer.State, message: String?) {
                if (state == TcpServer.State.ERROR) {
                    Log.e(TAG, "ws server error: $message")
                    RemoteState.serverListening(false, message)
                }
            }
        })
        wsServer = ws
        ws.start()

        discovery = DiscoveryResponder(Proto.UDP_PORT) {
            JSONObject()
                .put("v", Proto.VERSION)
                .put("tcp", Proto.TCP_PORT)
                .put("ws", Proto.WS_PORT)
                .put("name", deviceName())
                .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
        }.also { it.start() }

        RemoteState.accessibilityChanged(true)
        RemoteState.setKeyChannel(inj.keyChannel())
    }

    private fun deviceName(): String {
        val custom = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
        if (!custom.isNullOrBlank()) return custom
        return "${Build.BRAND} ${Build.MODEL}".trim()
    }

    private fun deviceInfo(): JSONObject = JSONObject()
        .put("name", deviceName())
        .put("model", Build.MODEL)
        .put("brand", Build.BRAND)
        .put("sdk", Build.VERSION.SDK_INT)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 本服务不消费事件，只需要手势/节点能力，这里保持空实现以降低开销
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "onUnbind")
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun updateClientCount() {
        val total = (server?.clientCount ?: 0) + (wsServer?.clientCount ?: 0)
        RemoteState.clientsChanged(total)
    }

    private fun shutdown() {
        try {
            server?.stop()
        } catch (_: Throwable) {
        }
        try {
            wsServer?.stop()
        } catch (_: Throwable) {
        }
        try {
            discovery?.stop()
        } catch (_: Throwable) {
        }
        try {
            overlay?.hide()
        } catch (_: Throwable) {
        }
        server = null
        wsServer = null
        discovery = null
        controller = null
        injector = null
        overlay = null
        RemoteState.accessibilityChanged(false)
    }

    companion object {
        private const val TAG = "TvRemoteService"
    }
}
