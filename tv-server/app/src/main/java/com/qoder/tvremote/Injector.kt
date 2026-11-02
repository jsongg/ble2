package com.qoder.tvremote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 真正把手机指令变成电视上的动作。
 *
 * 两条注入通道，按能力从高到低自动选择：
 *   1. Instrumentation#sendKeyDownUpSync —— 能发真实 KeyEvent（方向键/媒体键）。
 *      需要 INJECT_EVENTS 权限，普通安装的 APK 一定失败，只有系统签名/root 环境才有。
 *   2. AccessibilityService —— 免 root，能 gesture（点击/滑动/长按）和全局动作（返回/主页）。
 *
 * 所以鼠标相关一律走无障碍；按键先试通道 1，失败再降级为「焦点导航」（通道 3：
 * 用无障碍节点树找空间上相邻的可聚焦控件并 focus 它）。
 */
class Injector(private val svc: AccessibilityService) {

    private val main = Handler(Looper.getMainLooper())
    private val audio = svc.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 反射拿到的 Instrumentation；null 表示当前环境不可用 */
    private var instrumentation: Instrumentation? = null
    private var instrumentationResolved = false
    private var instrumentationUsable = true

    /** 每次手势的最短间隔，避免 dispatchGesture 互相取消 */
    private var lastGestureAt = 0L

    val focusNavigator = FocusNavigator(svc)

    // ------------------------------------------------------------------ 光标 / 鼠标

    /** 单击：在 (x, y) 落一个很短的 tap，等价于鼠标左键单击 */
    fun tap(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 0.5f, y + 0.5f) // 单点手势需要有长度，加 0.5px 防止某些设备判定为无效
        }
        dispatch(path, TAP_DURATION)
    }

    /** 双击：两次 tap，间隔小于系统双击超时（约 300ms） */
    fun doubleTap(x: Float, y: Float) {
        tap(x, y)
        main.postDelayed({ tap(x, y) }, 90)
    }

    /** 长按：按住 700ms 后抬起，多数电视 App 会弹出右键/上下文菜单 */
    fun longPress(x: Float, y: Float, durationMs: Long = 700) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 0.5f, y + 0.5f)
        }
        dispatch(path, durationMs.coerceIn(400, 3000))
    }

    /** 拖动 / 滑动：从起点画到终点 */
    fun swipe(x0: Float, y0: Float, x1: Float, y1: Float, durationMs: Long) {
        val path = Path().apply {
            moveTo(x0, y0)
            lineTo(x1, y1)
        }
        dispatch(path, durationMs.coerceIn(30, 5000))
    }

    /**
     * 滚轮：在光标处做一次垂直滑动。
     * notches > 0 表示"向下滚"，手指需要自下而上滑。
     */
    fun scrollAt(x: Float, y: Float, notches: Float) {
        val (w, h) = currentScreen()
        // 4K 屏上 60px 太小，按屏幕高度等比放大
        val step = SCROLL_STEP_PX * (h / 1080f).coerceIn(0.6f, 4f)
        val distance = notches * step
        // 把滑动起点放在光标下方一点，保证整段手势都落在屏幕内
        val half = distance / 2f
        var fromY = (y - half).coerceIn(2f, h - 2f)
        var toY = (y + half).coerceIn(2f, h - 2f)
        if (fromY == toY) {
            fromY = (h * 0.7f)
            toY = (h * 0.3f)
        }
        val cx = x.coerceIn(2f, w - 2f)
        val path = Path().apply {
            moveTo(cx, fromY)
            lineTo(cx, toY)
        }
        dispatch(path, (120 + Math.abs(notches) * 40).toLong().coerceAtMost(600))
    }

    private fun currentScreen(): Pair<Float, Float> {
        val (w, h) = CursorOverlayHolder.screenSizeOr(1920, 1080)
        return w.toFloat() to h.toFloat()
    }

    private fun dispatch(path: Path, durationMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
        val desc = GestureDescription.Builder().addStroke(stroke).build()

        val now = SystemClock.uptimeMillis()
        val delay = (lastGestureAt + GESTURE_GAP - now).coerceAtLeast(0)
        lastGestureAt = now + delay

        main.postDelayed({
            try {
                svc.dispatchGesture(desc, null, null)
            } catch (t: Throwable) {
                Log.w(TAG, "dispatchGesture failed: ${t.message}")
            }
        }, delay)
    }

    // ------------------------------------------------------------------ 按键

    /**
     * 发送按键，返回 true 表示已经被真实注入。
     * 调用方在返回 false 时可以认为"没有生效"。
     */
    fun key(name: String): Boolean {
        return when (name.lowercase()) {
            "back" -> global(AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> global(AccessibilityService.GLOBAL_ACTION_HOME)
            "recents" -> global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            "notifications" -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "volup" -> adjustVolume(AudioManager.ADJUST_RAISE)
            "voldown" -> adjustVolume(AudioManager.ADJUST_LOWER)
            "mute" -> toggleMute()
            "lock", "power" -> {
                performLockScreen()
                true
            }
            else -> {
                val code = KEYMAP[name.lowercase()] ?: return false
                if (injectKeyEvent(code)) return true
                // 通道 2 降级：空间焦点导航
                focusNavigator.navigate(code)
            }
        }
    }

    private fun global(action: Int): Boolean {
        return try {
            svc.performGlobalAction(action)
        } catch (t: Throwable) {
            Log.w(TAG, "global action $action failed: ${t.message}")
            false
        }
    }

    fun performLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        }
    }

    private fun adjustVolume(direction: Int): Boolean {
        return try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "adjustVolume failed: ${t.message}")
            false
        }
    }

    private fun toggleMute(): Boolean {
        return try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "toggleMute failed: ${t.message}")
            false
        }
    }

    /** 通道 1：反射 Instrumentation 注入真实 KeyEvent */
    fun injectKeyEvent(keyCode: Int): Boolean {
        if (!instrumentationUsable) return false
        val inst = obtainInstrumentation() ?: run {
            instrumentationUsable = false
            return false
        }
        return try {
            inst.sendKeyDownUpSync(keyCode)
            true
        } catch (t: Throwable) {
            // SecurityException: 需要 INJECT_EVENTS；这类设备以后也不用再试了
            Log.w(TAG, "instrumentation key inject rejected: ${t.javaClass.simpleName}")
            instrumentationUsable = false
            false
        }
    }

    private fun obtainInstrumentation(): Instrumentation? {
        if (instrumentationResolved) return instrumentation
        instrumentationResolved = true
        instrumentation = try {
            val threadClass = Class.forName("android.app.ActivityThread")
            val current = threadClass.getMethod("currentActivityThread").invoke(null)
                ?: return null
            threadClass.getMethod("getInstrumentation").invoke(current) as? Instrumentation
        } catch (t: Throwable) {
            Log.w(TAG, "obtainInstrumentation failed: ${t.javaClass.simpleName}")
            null
        }
        return instrumentation
    }

    /** 当前按键通道是高精度（真实 KeyEvent）还是降级（焦点导航） */
    fun keyChannel(): String = if (instrumentationUsable && obtainInstrumentation() != null) "keyevent" else "focus"

    // ------------------------------------------------------------------ 文字输入

    /**
     * 往当前焦点输入框写文字。
     * 优先 ACTION_SET_TEXT（最准确），失败再走剪贴板粘贴。
     */
    fun setText(text: String): Boolean {
        val root = svc.rootInActiveWindow ?: return pasteToClipboard(text)
        val target = findEditableFocused(root)
        if (target != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                return true
            }
        }
        return pasteToClipboard(text)
    }

    private fun pasteToClipboard(text: String): Boolean {
        return try {
            val cm = svc.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("tvremote", text))
            val root = svc.rootInActiveWindow
            val target = findEditableFocused(root ?: return false) ?: return false
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (t: Throwable) {
            Log.w(TAG, "paste fallback failed: ${t.message}")
            false
        }
    }

    private fun findEditableFocused(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(node)
        var visited = 0
        var firstEditable: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty() && visited < MAX_NODE_SCAN) {
            val cur = queue.removeFirst()
            visited++
            if (cur.isEditable) {
                if (cur.isFocused) return cur
                if (firstEditable == null) firstEditable = cur
            }
            for (i in 0 until cur.childCount) {
                cur.getChild(i)?.let { queue.add(it) }
            }
        }
        return firstEditable
    }

    // ------------------------------------------------------------------ 键盘显隐

    fun showIme() {
        val root = svc.rootInActiveWindow ?: return
        val target = findEditableFocused(root) ?: return
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    companion object {
        private const val TAG = "Injector"
        private const val TAP_DURATION = 40L
        private const val GESTURE_GAP = 45L
        private const val SCROLL_STEP_PX = 60f
        private const val MAX_NODE_SCAN = 500

        val KEYMAP: Map<String, Int> = mapOf(
            "up" to KeyEvent.KEYCODE_DPAD_UP,
            "down" to KeyEvent.KEYCODE_DPAD_DOWN,
            "left" to KeyEvent.KEYCODE_DPAD_LEFT,
            "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "ok" to KeyEvent.KEYCODE_DPAD_CENTER,
            "center" to KeyEvent.KEYCODE_DPAD_CENTER,
            "enter" to KeyEvent.KEYCODE_ENTER,
            "menu" to KeyEvent.KEYCODE_MENU,
            "playpause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            "next" to KeyEvent.KEYCODE_MEDIA_NEXT,
            "prev" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            "backspace" to KeyEvent.KEYCODE_DEL,
            "tab" to KeyEvent.KEYCODE_TAB,
            "space" to KeyEvent.KEYCODE_SPACE
        )
    }
}

/**
 * 用一个全局持有者让 Injector 在不知道服务实例的地方也能读到屏幕尺寸。
 * 只保存尺寸，不保存 Context，避免静态泄漏。
 */
object CursorOverlayHolder {
    @Volatile
    private var w: Int = 1920

    @Volatile
    private var h: Int = 1080

    fun update(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            w = width
            h = height
        }
    }

    fun screenSizeOr(dw: Int, dh: Int): Pair<Int, Int> =
        if (w > 0 && h > 0) w to h else dw to dh
}
