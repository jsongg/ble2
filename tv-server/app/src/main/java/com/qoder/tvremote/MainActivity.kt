package com.qoder.tvremote

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

/**
 * 电视端首页：只是把"该在哪儿看 IP、该去开什么权限"讲清楚，
 * 真正的服务跑在 [RemoteAccessibilityService] 里。
 */
class MainActivity : Activity(), RemoteState.Listener {

    private lateinit var tvIp: TextView
    private lateinit var tvPort: TextView
    private lateinit var tvAccessibility: TextView
    private lateinit var tvServer: TextView
    private lateinit var tvClients: TextView
    private lateinit var tvKeyChannel: TextView
    private lateinit var tvLastCmd: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 电视端常驻展示，避免看着看着熄屏
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        tvIp = findViewById(R.id.tvIp)
        tvPort = findViewById(R.id.tvPort)
        tvAccessibility = findViewById(R.id.tvAccessibility)
        tvServer = findViewById(R.id.tvServer)
        tvClients = findViewById(R.id.tvClients)
        tvKeyChannel = findViewById(R.id.tvKeyChannel)
        tvLastCmd = findViewById(R.id.tvLastCmd)

        findViewById<View>(R.id.btnAccessibility).setOnClickListener {
            openAccessibilitySettings()
        }
        findViewById<View>(R.id.btnOpenSettings).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (t: Throwable) {
                toast("无法打开系统设置")
            }
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        RemoteState.addListener(this)
        refresh()
    }

    override fun onPause() {
        RemoteState.removeListener(this)
        super.onPause()
    }

    override fun onStateChanged() {
        refresh()
    }

    private fun refresh() {
        val ip = NetUtils.localIpv4()
        tvIp.text = ip
        tvPort.text = "端口 ${Proto.TCP_PORT}  ·  发现端口 ${Proto.UDP_PORT}"
        if (ip == "0.0.0.0") {
            tvIp.setTextColor(0xFFFF6B6B.toInt())
        } else {
            tvIp.setTextColor(0xFF4CD964.toInt())
        }

        val enabled = isAccessibilityEnabled()
        val connected = RemoteState.accessibilityConnected
        tvAccessibility.text = when {
            connected -> "无障碍服务：已开启（正在接收指令）"
            enabled -> "无障碍服务：系统已允许，但未连接（请重新开启一次）"
            else -> "无障碍服务：未开启 —— 不开就无法控制本机"
        }
        tvAccessibility.setTextColor(
            if (connected) 0xFF4CD964.toInt() else 0xFFFF6B6B.toInt()
        )

        tvServer.text = when {
            RemoteState.listening -> "TCP 服务：监听中 ${Proto.TCP_PORT}"
            RemoteState.lastError != null -> "TCP 服务：端口占用（${RemoteState.lastError}）"
            else -> "TCP 服务：未监听"
        }

        tvClients.text = "已连接手机：${RemoteState.clientCount}"
        tvKeyChannel.text = "按键通道：" + when (RemoteState.keyChannel) {
            "keyevent" -> "真实 KeyEvent（高精度，方向键可用）"
            "focus" -> "焦点导航降级（免 root，方向键部分界面可用）"
            else -> "-"
        }
        tvLastCmd.text = "最近指令：${RemoteState.lastCommand.ifEmpty { "-" }}"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = "$packageName/${RemoteAccessibilityService::class.java.name}"
        val enabledServices = try {
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (t: Throwable) {
            null
        } ?: return false
        return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("请在列表中找到「电视遥控服务」并开启")
        } catch (t: Throwable) {
            // 部分定制电视系统没有直达页面，退回到设置首页
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
                toast("请在 设置 → 无障碍 中开启「电视遥控服务」")
            } catch (t2: Throwable) {
                toast("请手动进入 设置 → 无障碍 → 电视遥控服务")
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
