package com.qoder.tvremote

import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * 把手机端发来的一行 JSON 翻译成电视上的动作。
 *
 * 线程约定：TCP / WebSocket 读线程直接调用 [handle]，所有触碰 UI 与注入的操作
 * 都 post 到主线程。高频消息（move）不回执，避免回包拖慢读线程。
 */
class CommandController(
    private val overlay: CursorOverlay,
    private val injector: Injector,
    private val deviceInfo: () -> JSONObject
) {

    private val main = Handler(Looper.getMainLooper())

    fun handle(msg: JSONObject, client: RemoteClient) {
        val type = msg.optString("t", "")
        if (type.isEmpty()) return

        when (type) {
            // ---------------- 会话 ----------------
            "hello" -> {
                client.deviceName = msg.optString("name", "手机")
                client.send(welcome())
            }

            "ping" -> client.send(JSONObject().put("t", "pong").put("ts", System.currentTimeMillis()))

            // ---------------- 光标 ----------------
            "move" -> {
                val abs = msg.optBoolean("abs", false)
                if (abs) {
                    val nx = (msg.optDouble("x", 0.5) * screenW()).toFloat()
                    val ny = (msg.optDouble("y", 0.5) * screenH()).toFloat()
                    main.post { overlay.setPosition(nx, ny) }
                } else {
                    val dx = msg.optDouble("dx", 0.0).toFloat()
                    val dy = msg.optDouble("dy", 0.0).toFloat()
                    if (dx != 0f || dy != 0f) {
                        main.post { overlay.moveBy(dx, dy) }
                    }
                }
            }

            "cursor" -> {
                val show = msg.optBoolean("show", true)
                main.post {
                    if (show) ensureCursor() else overlay.hide()
                }
            }

            // ---------------- 鼠标动作 ----------------
            "tap", "click" -> onCursor { x, y ->
                ensureCursor()
                injector.tap(x, y)
                ack(client, "tap")
            }

            "dblclick", "doubletap" -> onCursor { x, y ->
                ensureCursor()
                injector.doubleTap(x, y)
                ack(client, "dblclick")
            }

            "longpress", "rightclick" -> onCursor { x, y ->
                ensureCursor()
                val ms = msg.optLong("ms", 700)
                injector.longPress(x, y, ms)
                ack(client, "longpress")
            }

            "swipe" -> {
                val x0 = msg.optDouble("x0", Double.NaN).toFloat()
                val y0 = msg.optDouble("y0", Double.NaN).toFloat()
                val x1 = msg.optDouble("x1", Double.NaN).toFloat()
                val y1 = msg.optDouble("y1", Double.NaN).toFloat()
                val ms = msg.optLong("ms", 260)
                if (!x0.isNaN() && !y0.isNaN() && !x1.isNaN() && !y1.isNaN()) {
                    main.post { injector.swipe(x0, y0, x1, y1, ms) }
                } else {
                    // 相对滑动：以光标为起点
                    val dx = msg.optDouble("dx", 0.0).toFloat()
                    val dy = msg.optDouble("dy", 0.0).toFloat()
                    main.post {
                        ensureCursor()
                        injector.swipe(overlay.x, overlay.y, overlay.x + dx, overlay.y + dy, ms)
                    }
                }
            }

            "scroll" -> {
                val v = msg.optDouble("v", 0.0).toFloat()
                if (v != 0f) {
                    main.post {
                        ensureCursor()
                        injector.scrollAt(overlay.x, overlay.y, v)
                    }
                }
            }

            // ---------------- 键盘 ----------------
            "key" -> {
                val codeName = msg.optString("code", "")
                if (codeName.isNotEmpty()) {
                    main.post {
                        val ok = injector.key(codeName)
                        if (!ok) client.send(JSONObject().put("t", "warn").put("msg", "key $codeName 未生效"))
                    }
                }
            }

            "text" -> {
                val text = msg.optString("s", "")
                if (text.isNotEmpty()) {
                    main.post {
                        val ok = injector.setText(text)
                        ack(client, if (ok) "text" else "text-failed")
                    }
                }
            }

            "ime" -> main.post { injector.showIme() }

            "lock" -> main.post { injector.performLockScreen() }

            "state" -> client.send(welcome())

            else -> client.send(JSONObject().put("t", "warn").put("msg", "unknown cmd: $type"))
        }
    }

    private fun ack(client: RemoteClient, what: String) {
        client.send(JSONObject().put("t", "ack").put("what", what))
    }

    private fun ensureCursor() {
        if (!overlay.visible) overlay.show()
    }

    private inline fun onCursor(crossinline block: (Float, Float) -> Unit) {
        main.post {
            ensureCursor()
            block(overlay.x, overlay.y)
        }
    }

    private fun screenW() = CursorOverlayHolder.screenSizeOr(1920, 1080).first

    private fun screenH() = CursorOverlayHolder.screenSizeOr(1920, 1080).second

    fun welcome(): JSONObject {
        val (w, h) = CursorOverlayHolder.screenSizeOr(1920, 1080)
        return JSONObject()
            .put("t", "welcome")
            .put("v", Proto.VERSION)
            .put("w", w)
            .put("h", h)
            .put("port", Proto.TCP_PORT)
            .put("key", injector.keyChannel())
            .put("dev", deviceInfo())
            .put("cursor", overlay.visible)
    }

    companion object {
        private const val TAG = "Controller"
    }
}
