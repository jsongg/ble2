package com.qoder.tvremote

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 方向键的免 root 降级实现。
 *
 * 背景：AccessibilityService 无法发送 KeyEvent，普通 APK 也拿不到 INJECT_EVENTS。
 * 于是退而求其次——直接从无障碍节点树里读出所有可聚焦控件的位置，找出当前焦点
 * 在指定方向上的"最近邻居"，然后 focus 它。对系统 Launcher、原生 TV 设置、
 * 大部分电视 App 都有效；对自绘 UI（Flutter/游戏/视频播放器全屏控件）无效，
 * 那种场景请让用户用鼠标模式点位。
 */
class FocusNavigator(private val svc: AccessibilityService) {

    private data class Box(
        val node: AccessibilityNodeInfo,
        val rect: Rect,
        val focused: Boolean
    )

    /**
     * @param keyCode KeyEvent.KEYCODE_DPAD_* 之一
     * @return 是否成功移动了焦点
     */
    fun navigate(keyCode: Int): Boolean {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> DIR_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> DIR_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> DIR_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> DIR_RIGHT
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> DIR_CENTER
            else -> return false
        }

        val root = try {
            svc.rootInActiveWindow
        } catch (t: Throwable) {
            null
        } ?: return false

        val boxes = ArrayList<Box>(96)
        try {
            collect(root, boxes, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "collect nodes failed: ${t.message}")
        }
        if (boxes.isEmpty()) return false

        val current = boxes.firstOrNull { it.focused }

        // 确认键：直接点击当前焦点
        if (direction == DIR_CENTER) {
            val target = current ?: boxes.firstOrNull { it.node.isClickable } ?: return false
            return clickOrFocus(target.node)
        }

        if (current == null) {
            // 没有任何焦点时，把焦点给第一个可聚焦控件，让用户有个起点
            val first = boxes.firstOrNull { it.node.isFocusable || it.node.isClickable } ?: return false
            return focus(first.node)
        }

        val cx = current.rect.centerX().toFloat()
        val cy = current.rect.centerY().toFloat()

        var best: Box? = null
        var bestCost = Float.MAX_VALUE

        for (box in boxes) {
            if (box === current) continue
            if (!box.node.isFocusable && !box.node.isClickable) continue
            if (box.rect.width() <= 0 || box.rect.height() <= 0) continue
            // 容器节点会包住当前焦点，跳过以免原地跳
            if (box.rect.contains(current.rect)) continue

            val dx = box.rect.centerX() - cx
            val dy = box.rect.centerY() - cy

            val (mainDelta, crossDelta) = when (direction) {
                DIR_UP -> -dy to kotlin.math.abs(dx)
                DIR_DOWN -> dy to kotlin.math.abs(dx)
                DIR_LEFT -> -dx to kotlin.math.abs(dy)
                else -> dx to kotlin.math.abs(dy)
            }
            if (mainDelta <= MIN_MAIN_DELTA) continue

            // 主方向距离为主，垂直方向的错位加权惩罚，避免跳到斜对角的元素
            val cost = mainDelta + crossDelta * CROSS_WEIGHT
            if (cost < bestCost) {
                bestCost = cost
                best = box
            }
        }

        val target = best ?: return false
        return focus(target.node)
    }

    private fun focus(node: AccessibilityNodeInfo): Boolean {
        var ok = false
        if (node.isFocusable) {
            ok = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }
        if (!ok) {
            ok = node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        }
        if (!ok) {
            ok = node.performAction(AccessibilityNodeInfo.ACTION_SELECT)
        }
        return ok
    }

    private fun clickOrFocus(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        // 有些 TV 控件不暴露 click，但点击父级可用
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 3) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            parent = parent.parent
            depth++
        }
        return focus(node)
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<Box>, depth: Int) {
        if (out.size >= MAX_NODES || depth > MAX_DEPTH) return

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val visible = node.isVisibleToUser && rect.width() > 0 && rect.height() > 0
        if (visible) {
            out.add(Box(node, rect, node.isFocused || node.isAccessibilityFocused))
        }

        val count = node.childCount
        for (i in 0 until count) {
            val child = try {
                node.getChild(i)
            } catch (t: Throwable) {
                null
            } ?: continue
            collect(child, out, depth + 1)
            if (out.size >= MAX_NODES) return
        }
    }

    private companion object {
        const val TAG = "FocusNavigator"
        const val MAX_NODES = 600
        const val MAX_DEPTH = 40
        const val MIN_MAIN_DELTA = 4f
        const val CROSS_WEIGHT = 2.5f

        const val DIR_UP = 0
        const val DIR_DOWN = 1
        const val DIR_LEFT = 2
        const val DIR_RIGHT = 3
        const val DIR_CENTER = 4
    }
}
