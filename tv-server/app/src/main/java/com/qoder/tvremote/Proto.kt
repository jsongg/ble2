package com.qoder.tvremote

/**
 * 手机端 <-> 电视端 的通信协议常量。
 *
 * 传输格式：TCP 长连接 + 每行一个 JSON（NDJSON，UTF-8，'\n' 结尾）。
 * 之所以用文本行协议而不是二进制，是为了可以用 telnet / nc 直接手动调试。
 */
object Proto {

    /** 协议版本，双方不一致时手机端会提示升级 */
    const val VERSION = 1

    /** TCP 控制端口 */
    const val TCP_PORT = 8888

    /** WebSocket 控制端口（与 TCP 同一套 JSON 指令，供 uniapp 手机端使用） */
    const val WS_PORT = 8881

    /** UDP 局域网发现端口 */
    const val UDP_PORT = 8889

    /** 手机端广播的发现请求（明文，方便用脚本测试） */
    const val DISCOVER_REQUEST = "TVREMOTE_DISCOVER_V1"

    /** 电视端对发现请求的应答前缀，后面紧跟一行 JSON */
    const val DISCOVER_REPLY_PREFIX = "TVREMOTE_HERE_V1 "
}
