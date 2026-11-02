package com.qoder.tvremote

import org.json.JSONObject

/**
 * 一条"下发给手机的通道"。
 *
 * 电视端同时开两种传输：
 *   - 纯 TCP（[Proto.TCP_PORT]）：给脚本、telnet、其他语言的客户端用
 *   - WebSocket（[Proto.WS_PORT]）：给 uniapp 手机端用，因为 uni 的
 *     uni.connectSocket 是原生异步实现，不会像 JS 直调 Socket 那样阻塞 UI 线程
 *
 * 两者共用同一套命令处理逻辑，所以在此抽象成接口。
 */
interface RemoteClient {

    /** 通道标识，用于日志/状态展示 */
    val label: String

    /** 手机自报的名字，在 hello 里填 */
    var deviceName: String

    /** 下发一条 JSON，失败要吞掉异常并让连接自行关闭 */
    fun send(obj: JSONObject)
}
