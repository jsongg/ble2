#!/usr/bin/env node
/**
 * 电视端服务自测脚本（在电脑上跑，不需要手机）。
 *
 * 用法：
 *   node tools/tcp-test.js 192.168.1.100            # 连 TCP 8888，跑一遍演示动作
 *   node tools/tcp-test.js 192.168.1.100 8888 move  # 只发指定命令
 *   node tools/tcp-test.js 192.168.1.100 8888 -i    # 进入交互模式，每行一条 JSON
 *
 * 协议：纯 TCP + 每行一个 JSON（UTF-8，\n 结尾）。
 * WebSocket(8881) 走的是同一套 JSON，只是换了个传输层。
 */

const net = require('net')
const readline = require('readline')

const host = process.argv[2] || '127.0.0.1'
const port = Number(process.argv[3] || 8888)
const mode = process.argv[4] || 'demo'

const socket = net.createConnection({ host, port }, () => {
	console.log(`已连接 ${host}:${port}`)
	send({ t: 'hello', v: 1, name: 'pc-test' })
	run()
})

socket.setNoDelay(true)
socket.setEncoding('utf8')

let buffer = ''
socket.on('data', (chunk) => {
	buffer += chunk
	let idx
	while ((idx = buffer.indexOf('\n')) >= 0) {
		const line = buffer.slice(0, idx).trim()
		buffer = buffer.slice(idx + 1)
		if (line) console.log('<-', line)
	}
})

socket.on('error', (err) => {
	console.error('连接出错：', err.message)
	process.exit(1)
})

socket.on('close', () => {
	console.log('连接已关闭')
	process.exit(0)
})

function send(obj) {
	const line = typeof obj === 'string' ? obj : JSON.stringify(obj)
	socket.write(line + '\n')
}

function wait(ms) {
	return new Promise((r) => setTimeout(r, ms))
}

async function run() {
	if (mode === '-i') {
		const rl = readline.createInterface({ input: process.stdin, output: process.stdout })
		rl.on('line', (line) => {
			const text = line.trim()
			if (!text) return
			send(text)
		})
		return
	}

	if (mode !== 'demo') {
		send(mode)
		return
	}

	// 演示：把光标推到屏幕右下、点一下、滚两格、再按一下返回
	await wait(300)
	console.log('-> 移动指针')
	for (let i = 0; i < 20; i++) {
		send({ t: 'move', dx: 40, dy: 20 })
		await wait(20)
	}
	await wait(200)
	console.log('-> 单击')
	send({ t: 'tap' })
	await wait(500)
	console.log('-> 向下滚 2 格')
	send({ t: 'scroll', v: 2 })
	await wait(600)
	console.log('-> 方向键左')
	send({ t: 'key', code: 'left' })
	await wait(400)
	console.log('演示结束，按 Ctrl+C 退出')
}
