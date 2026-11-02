package com.qoder.tvremote

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {

    /** 取本机第一个可用的 IPv4 局域网地址，取不到返回 0.0.0.0 */
    fun localIpv4(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "0.0.0.0"
            var fallback: String? = null
            for (nif in interfaces) {
                try {
                    if (!nif.isUp || nif.isLoopback) continue
                    val name = nif.name ?: ""
                    // 跳过常见虚拟网卡，避免把 172.x 的 docker/vpn 地址展示给用户
                    val virtual = name.startsWith("docker") || name.startsWith("veth") ||
                            name.startsWith("tun") || name.startsWith("rmnet") ||
                            name.startsWith("p2p") || name.startsWith("dummy")
                    for (addr in nif.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (ip.startsWith("169.254.")) continue
                            if (virtual) {
                                if (fallback == null) fallback = ip
                            } else {
                                val isWlan = name.startsWith("wlan") || name.startsWith("eth") ||
                                        name.startsWith("ap") || name.startsWith("br")
                                if (isWlan) return ip
                                if (fallback == null) fallback = ip
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // 某些接口在枚举时会抛异常，忽略继续
                }
            }
            return fallback ?: "0.0.0.0"
        } catch (_: Throwable) {
            return "0.0.0.0"
        }
    }
}
