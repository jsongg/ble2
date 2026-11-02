package com.qoder.tvremote

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 一个极简的全局状态板，让 MainActivity 能显示无障碍是否已开启、
 * TCP 是否在监听、当前连了几台手机。故意不引 LiveData/Flow，
 * 保持 TV 端依赖为零。
 */
object RemoteState {

    interface Listener {
        fun onStateChanged()
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    var accessibilityConnected: Boolean = false
        private set

    @Volatile
    var listening: Boolean = false
        private set

    @Volatile
    var clientCount: Int = 0
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var keyChannel: String = "-"
        private set

    @Volatile
    var lastCommand: String = ""
        private set

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun accessibilityChanged(connected: Boolean) {
        accessibilityConnected = connected
        notifyChanged()
    }

    fun serverListening(ok: Boolean, error: String? = null) {
        listening = ok
        lastError = error
        notifyChanged()
    }

    fun clientsChanged(count: Int) {
        clientCount = count
        notifyChanged()
    }

    fun setKeyChannel(channel: String) {
        keyChannel = channel
        notifyChanged()
    }

    fun setLastCommand(text: String) {
        lastCommand = text
        notifyChanged()
    }

    private fun notifyChanged() {
        if (listeners.isEmpty()) return
        main.post {
            for (l in listeners) {
                try {
                    l.onStateChanged()
                } catch (_: Throwable) {
                }
            }
        }
    }
}
