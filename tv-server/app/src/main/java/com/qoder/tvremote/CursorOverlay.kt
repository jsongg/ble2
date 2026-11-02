package com.qoder.tvremote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 电视屏幕上的"虚拟鼠标指针"。
 *
 * 为什么自己画指针？
 *   电视端通过 AccessibilityService 注入的是「手势」而不是真正的鼠标事件，
 *   系统不会帮忙画指针。所以指针位置完全由本类维护，点击时再在指针坐标处
 *   用 dispatchGesture 打一个 tap，视觉与操作就能对上。
 *
 * 为什么用 TYPE_ACCESSIBILITY_OVERLAY？
 *   该窗口类型由无障碍服务直接持有即可添加，不需要用户额外授予"显示在其他应用上层"权限。
 */
class CursorOverlay(private val serviceContext: Context) {

    private val wm: WindowManager =
        serviceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var view: CursorView? = null
    private var params: WindowManager.LayoutParams? = null

    /** 指针尖端的屏幕坐标 */
    var x: Float = 0f
        private set
    var y: Float = 0f
        private set

    /** 是否显示指针（鼠标模式下为 true，纯按键模式可以关掉） */
    var visible: Boolean = false
        private set

    private var screenW = 1920
    private var screenH = 1080

    /** 指针视觉大小（dp） */
    private val cursorSizeDp = 28

    fun screenSize(): Pair<Int, Int> = screenW to screenH

    /**
     * 只测量屏幕，不添加窗口。
     * 电视端启动时并不需要立刻看到一个悬空的光标，用户第一次操作时再 show 即可。
     */
    fun measureScreen(): Pair<Int, Int> {
        refreshScreenSize()
        return screenW to screenH
    }

    /** 添加 TYPE_ACCESSIBILITY_OVERLAY 窗口，只有无障碍服务自己的 Context 才有权限 */
    fun show() {
        if (visible) return
        refreshScreenSize()

        val cv = CursorView(serviceContext, cursorSizeDp)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW / 2 - cv.hotspotX()).toInt()
            y = (screenH / 2 - cv.hotspotY()).toInt()
        }

        try {
            wm.addView(cv, lp)
        } catch (t: Throwable) {
            Log.e(TAG, "add cursor overlay failed", t)
            return
        }

        view = cv
        params = lp
        visible = true
        x = screenW / 2f
        y = screenH / 2f
    }

    fun hide() {
        val v = view ?: return
        try {
            wm.removeView(v)
        } catch (_: Throwable) {
        }
        view = null
        params = null
        visible = false
    }

    /** 相对移动，带边界钳制 */
    fun moveBy(dx: Float, dy: Float) {
        setPosition(x + dx, y + dy)
    }

    fun setPosition(nx: Float, ny: Float) {
        x = nx.coerceIn(0f, screenW.toFloat())
        y = ny.coerceIn(0f, screenH.toFloat())
        val lp = params ?: return
        val v = view ?: return
        lp.x = (x - v.hotspotX()).toInt()
        lp.y = (y - v.hotspotY()).toInt()
        try {
            wm.updateViewLayout(v, lp)
        } catch (_: Throwable) {
        }
    }

    private fun refreshScreenSize() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.maximumWindowMetrics.bounds
                screenW = bounds.width()
                screenH = bounds.height()
            } else {
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(dm)
                screenW = dm.widthPixels
                screenH = dm.heightPixels
            }
        } catch (t: Throwable) {
            Log.w(TAG, "refreshScreenSize failed: ${t.message}")
        }
        if (screenW <= 0) screenW = 1920
        if (screenH <= 0) screenH = 1080
    }

    /**
     * 箭头指针。尖端在 (hotspotX, hotspotY)，用于与真实屏幕坐标对齐。
     */
    private class CursorView(context: Context, private val sizeDp: Int) : View(context) {

        private val density = resources.displayMetrics.density
        private val sizePx = (sizeDp * density).toInt().coerceAtLeast(16)

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC000000")
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#55000000")
            style = Paint.Style.FILL
            setShadowLayer(4f * density, 0f, 2f * density, Color.BLACK)
        }

        private val arrow = Path()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(sizePx, sizePx)
        }

        /** 箭头尖端在 View 内的 x 偏移（0） */
        fun hotspotX(): Int = 1

        /** 箭头尖端在 View 内的 y 偏移（0） */
        fun hotspotY(): Int = 1

        override fun onDraw(canvas: Canvas) {
            val s = sizePx.toFloat()
            if (arrow.isEmpty) {
                // 经典箭头造型，尖端在左上角
                arrow.moveTo(1f, 1f)
                arrow.lineTo(1f, s * 0.78f)
                arrow.lineTo(s * 0.28f, s * 0.58f)
                arrow.lineTo(s * 0.42f, s * 0.98f)
                arrow.lineTo(s * 0.58f, s * 0.92f)
                arrow.lineTo(s * 0.44f, s * 0.54f)
                arrow.lineTo(s * 0.72f, s * 0.52f)
                arrow.close()
            }
            canvas.drawPath(arrow, shadow)
            canvas.drawPath(arrow, fill)
            canvas.drawPath(arrow, stroke)
        }
    }

    private companion object {
        const val TAG = "CursorOverlay"
    }
}
