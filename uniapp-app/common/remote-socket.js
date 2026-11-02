/**
 * 手机端与电视之间的长连接封装。
 *
 * 传输选型说明：
 *   WebSocket 本质上就是一条 TCP 长连接，电视端在 8881 端口上自己实现了
 *   RFC6455 子集（握手 + 文本帧），手机端用 uni 官方的 uni.connectSocket 连接。
 *   之所以不用 plus.android 直接 new java.net.Socket：
 *     它的 readLine() 是同步阻塞调用，会把 uniapp 的 JS 线程整个卡住，
 *     触摸板的连续 move 事件会直接卡死界面；而 connectSocket 是原生异步实现，
 *     回调走独立线程，不占 UI。
 *
 *   电视端同时也开着裸 TCP 8888（NDJSON 协议），给 curl / nc / 脚本用。
 */

const STORAGE_KEY = 'tv_remote_target'
const PING_INTERVAL = 4000
const DEAD_TIMEOUT = 15000

class RemoteSocket {
	constructor() {
		this.task = null
		this.state = 'idle' // idle | connecting | open | closed
		this.host = ''
		this.port = 8881
		this.info = null
		this.rtt = 0
		this.lastError = ''

		this._handlers = { state: [], message: [] }
		this._pingTimer = null
		this._pingSentAt = 0
		this._lastRecvAt = 0
		this._welcomeResolver = null
	}

	// ---------------------------------------------------------------- 本地记忆

	loadLastTarget() {
		try {
			const saved = uni.getStorageSync(STORAGE_KEY)
			if (saved && saved.host) {
				this.host = saved.host
				this.port = saved.port || 8881
			}
		} catch (e) {
			// 忽略存储异常
		}
		return { host: this.host, port: this.port }
	}

	saveTarget(host, port) {
		this.host = host
		this.port = port
		try {
			uni.setStorageSync(STORAGE_KEY, { host, port })
		} catch (e) {
			// 忽略
		}
	}

	// ---------------------------------------------------------------- 事件

	on(event, handler) {
		if (this._handlers[event]) this._handlers[event].push(handler)
		return this
	}

	off(event, handler) {
		const list = this._handlers[event]
		if (!list) return
		const idx = list.indexOf(handler)
		if (idx >= 0) list.splice(idx, 1)
	}

	_emit(event, payload) {
		const list = this._handlers[event]
		if (!list) return
		for (let i = 0; i < list.length; i++) {
			try {
				list[i](payload)
			} catch (e) {
				console.error('[socket] handler error', e)
			}
		}
	}

	_setState(state, extra) {
		if (this.state === state && !extra) return
		this.state = state
		this._emit('state', { state, ...(extra || {}) })
	}

	// ---------------------------------------------------------------- 连接

	/**
	 * @returns {Promise<void>} 握手成功（收到 welcome）后 resolve
	 */
	connect(host, port) {
		host = (host || '').trim()
		port = Number(port) || 8881
		if (!host) return Promise.reject(new Error('请先填写电视 IP'))

		this.close(true)
		this.host = host
		this.port = port
		this.info = null
		this.lastError = ''
		this._setState('connecting')

		const url = `ws://${host}:${port}`

		return new Promise((resolve, reject) => {
			let settled = false
			const finishOk = () => {
				if (settled) return
				settled = true
				resolve()
			}
			const finishErr = (msg) => {
				if (settled) return
				settled = true
				this.lastError = msg
				reject(new Error(msg))
			}

			// 连接超时兜底（部分安卓机型 onError 不回调）
			const timer = setTimeout(() => {
				finishErr('连接超时：请确认手机与电视在同一 WiFi，且电视端服务已开启')
				this.close()
			}, 7000)

			let task
			try {
				task = uni.connectSocket({
					url,
					complete: () => {}
				})
			} catch (e) {
				clearTimeout(timer)
				finishErr('无法发起连接：' + (e && e.message ? e.message : e))
				return
			}

			if (!task) {
				clearTimeout(timer)
				finishErr('当前环境不支持 WebSocket')
				return
			}

			this.task = task

			task.onOpen(() => {
				// 这个 task 已经被新连接取代，忽略它的回调
				if (this.task !== task) return
				clearTimeout(timer)
				this._lastRecvAt = Date.now()
				this._setState('open')
				this._welcomeResolver = finishOk
				// 握手后立刻自报家门，顺便拿回电视的屏幕尺寸和按键通道
				this._rawSend({ t: 'hello', v: 1, name: this._deviceName() })
				this._startHeartbeat()
				// 即使 welcome 没回来也算连上，避免个别固件不回包
				setTimeout(finishOk, 1200)
			})

			task.onMessage((res) => {
				if (this.task !== task) return
				this._lastRecvAt = Date.now()
				this._handleRaw(res.data)
			})

			task.onError((err) => {
				if (this.task !== task) return
				clearTimeout(timer)
				this._stopHeartbeat()
				this._setState('closed', { error: true })
				finishErr('连接失败：' + (err && err.errMsg ? err.errMsg : '请检查 IP'))
			})

			task.onClose(() => {
				if (this.task !== task) return
				clearTimeout(timer)
				this._stopHeartbeat()
				this._setState('closed')
				finishErr('连接已断开')
			})
		})
	}

	close(silent) {
		this._stopHeartbeat()
		this._welcomeResolver = null
		const t = this.task
		this.task = null
		if (t) {
			try {
				t.close({})
			} catch (e) {
				// 已经关了
			}
		}
		if (!silent && this.state !== 'idle') {
			this._setState('closed')
		} else if (silent) {
			this.state = 'idle'
		}
	}

	get connected() {
		return this.state === 'open' && !!this.task
	}

	// ---------------------------------------------------------------- 收发

	_rawSend(obj) {
		if (!this.task) return false
		try {
			this.task.send({
				data: JSON.stringify(obj),
				fail: () => {}
			})
			return true
		} catch (e) {
			return false
		}
	}

	/** 对外发送指令，未连接时静默丢弃（触摸板高频调用，不抛异常） */
	send(obj) {
		if (this.state !== 'open') return false
		return this._rawSend(obj)
	}

	_handleRaw(raw) {
		if (!raw) return
		let msg
		try {
			msg = typeof raw === 'string' ? JSON.parse(raw) : raw
		} catch (e) {
			return
		}
		if (!msg || !msg.t) return

		if (msg.t === 'welcome') {
			this.info = msg
			if (this._welcomeResolver) {
				this._welcomeResolver()
				this._welcomeResolver = null
			}
		} else if (msg.t === 'pong') {
			const ts = Number(msg.ts) || 0
			if (ts > 0) this.rtt = Math.max(0, Date.now() - ts)
		}

		this._emit('message', msg)
	}

	// ---------------------------------------------------------------- 心跳

	_startHeartbeat() {
		this._stopHeartbeat()
		this._pingTimer = setInterval(() => {
			if (this.state !== 'open') return
			if (Date.now() - this._lastRecvAt > DEAD_TIMEOUT) {
				// 长时间没有任何回包，认定连接已经死了
				this.lastError = '连接已失效'
				this.close()
				return
			}
			this._rawSend({ t: 'ping' })
		}, PING_INTERVAL)
	}

	_stopHeartbeat() {
		if (this._pingTimer) {
			clearInterval(this._pingTimer)
			this._pingTimer = null
		}
	}

	_deviceName() {
		try {
			const info = uni.getSystemInfoSync()
			return (info.brand || info.deviceBrand || 'phone') + ' ' + (info.model || '')
		} catch (e) {
			return 'phone'
		}
	}
}

export default new RemoteSocket()
