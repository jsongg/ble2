const UUID_HINT = 'tv_remote_history'

/**
 * 局域网自动发现电视。
 *
 * 电视端在 UDP 8889 上监听明文 "TVREMOTE_DISCOVER_V1"，回包是
 * "TVREMOTE_HERE_V1 {json}"。手机端广播一次即可拿到同网段所有已开机的电视。
 *
 * 实现限制：
 *   uni 没有封装 UDP，App 端只能用 plus.android 透传 Java 的 DatagramSocket。
 *   receive() 是阻塞调用，所以这里用 setSoTimeout + 有限次循环，最多占用
 *   JS 线程约 1.5 秒（只在用户主动点"自动搜索"时发生）。
 *   任何一步失败都静默降级，让用户手动填 IP —— 手填永远是 100% 可用的路径。
 */
export function discoverDevices(timeoutMs = 1500) {
	return new Promise((resolve) => {
		const found = []

		// #ifdef APP-PLUS
		try {
			const DatagramSocket = plus.android.importClass('java.net.DatagramSocket')
			const DatagramPacket = plus.android.importClass('java.net.DatagramPacket')
			const InetAddress = plus.android.importClass('java.net.InetAddress')
			const JString = plus.android.importClass('java.lang.String')

			const socket = new DatagramSocket()
			socket.setBroadcast(true)
			socket.setSoTimeout(400)

			// 发广播
			const reqBytes = new JString('TVREMOTE_DISCOVER_V1').getBytes()
			const target = InetAddress.getByName('255.255.255.255')
			const outPacket = new DatagramPacket(reqBytes, reqBytes.length, target, 8889)
			socket.send(outPacket)

			// 收应答：缓冲区用一个长度固定的字符串造出 byte[]
			const buf = new JString(new Array(1025).join(' ')).getBytes()
			const deadline = Date.now() + timeoutMs

			while (Date.now() < deadline) {
				try {
					const inPacket = new DatagramPacket(buf, buf.length)
					socket.receive(inPacket)
					const len = inPacket.getLength()
					if (len <= 0) continue
					const text = new JString(buf, 0, len, 'UTF-8').toString()
					const item = parseReply(text, inPacket.getAddress().getHostAddress())
					if (item && !found.some((d) => d.host === item.host)) {
						found.push(item)
					}
				} catch (e) {
					// SocketTimeoutException：继续等下一轮
				}
			}

			try {
				socket.close()
			} catch (e) {}
		} catch (e) {
			console.warn('[discovery] 不可用，请手动填写 IP', e)
		}
		// #endif

		resolve(found)
	})
}

function parseReply(text, fromIp) {
	if (!text || text.indexOf('TVREMOTE_HERE_V1') !== 0) return null
	const jsonPart = text.substring('TVREMOTE_HERE_V1'.length).trim()
	let info = {}
	try {
		info = JSON.parse(jsonPart)
	} catch (e) {
		info = {}
	}
	return {
		host: fromIp,
		port: Number(info.ws) || 8881,
		tcpPort: Number(info.tcp) || 8888,
		name: info.name || '电视',
		model: info.model || '',
		version: Number(info.v) || 0
	}
}

// ---------------------------------------------------------------- 历史设备

export function loadHistory() {
	try {
		const list = uni.getStorageSync(UUID_HINT)
		return Array.isArray(list) ? list : []
	} catch (e) {
		return []
	}
}

export function pushHistory(device) {
	if (!device || !device.host) return []
	const list = loadHistory().filter((d) => d.host !== device.host)
	list.unshift({
		host: device.host,
		port: Number(device.port) || 8881,
		name: device.name || device.host,
		model: device.model || '',
		ts: Date.now()
	})
	const trimmed = list.slice(0, 8)
	try {
		uni.setStorageSync(UUID_HINT, trimmed)
	} catch (e) {}
	return trimmed
}

export function removeHistory(host) {
	const list = loadHistory().filter((d) => d.host !== host)
	try {
		uni.setStorageSync(UUID_HINT, list)
	} catch (e) {}
	return list
}
