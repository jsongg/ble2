<template>
	<view class="page">
		<view class="header">
			<text class="title">电视遥控</text>
			<text class="subtitle">同一 WiFi 下控制电视的鼠标与键盘</text>
		</view>

		<view class="card">
			<view class="field">
				<text class="label">电视 IP</text>
				<input class="input" v-model="host" type="text" placeholder="192.168.1.100" placeholder-class="ph"
					confirm-type="done" />
			</view>
			<view class="field">
				<text class="label">端口</text>
				<input class="input" v-model="port" type="number" placeholder="8881" placeholder-class="ph" />
			</view>

			<view class="btn-row">
				<view class="btn ghost" :class="{ disabled: scanning }" @click="onDiscover">
					<text class="btn-text">{{ scanning ? '搜索中…' : '自动搜索' }}</text>
				</view>
				<view class="btn primary" :class="{ disabled: connecting }" @click="onConnect">
					<text class="btn-text">{{ connecting ? '连接中…' : '连接电视' }}</text>
				</view>
			</view>

			<text class="hint" v-if="hint">{{ hint }}</text>
		</view>

		<view class="card" v-if="history.length">
			<text class="card-title">最近连接</text>
			<view class="history-item" v-for="d in history" :key="d.host" @click="quickConnect(d)">
				<view class="hi-main">
					<text class="hi-name">{{ d.name }}</text>
					<text class="hi-ip">{{ d.host }}:{{ d.port }}</text>
				</view>
				<text class="hi-del" @click.stop="onRemove(d.host)">删除</text>
			</view>
		</view>

		<view class="tips">
			<text class="tip">1. 电视端安装「电视遥控服务」，并开启无障碍服务</text>
			<text class="tip">2. 手机和电视连接同一个 WiFi / 热点</text>
			<text class="tip">3. 电视屏幕上会显示本机 IP，填到这里即可</text>
			<text class="tip">4. 连上后：拖动=移动指针，轻点=左键，长按=右键</text>
		</view>
	</view>
</template>

<script setup>
	import { ref, onMounted } from 'vue'
	import socket from '../../common/remote-socket.js'
	import { discoverDevices, loadHistory, pushHistory, removeHistory } from '../../common/discovery.js'

	const host = ref(socket.host || '')
	const port = ref(socket.port || 8881)
	const scanning = ref(false)
	const connecting = ref(false)
	const hint = ref('')
	const history = ref([])

	onMounted(() => {
		const last = socket.loadLastTarget()
		if (last.host && !host.value) host.value = last.host
		if (last.port) port.value = last.port
		history.value = loadHistory()
	})

	async function onDiscover() {
		if (scanning.value) return
		scanning.value = true
		hint.value = '正在局域网内搜索…'
		try {
			const list = await discoverDevices(1500)
			if (list.length > 0) {
				const first = list[0]
				host.value = first.host
				port.value = first.port
				list.forEach((d) => pushHistory(d))
				history.value = loadHistory()
				hint.value = `找到 ${list.length} 台设备，已填入 ${first.name || first.host}`
			} else {
				hint.value = '没搜到设备。请确认电视端无障碍服务已开启，或直接手填电视上显示的 IP。'
			}
		} catch (e) {
			hint.value = '搜索不可用，请手动填写 IP'
		} finally {
			scanning.value = false
		}
	}

	async function onConnect() {
		if (connecting.value) return
		if (!host.value) {
			hint.value = '请先填写电视 IP'
			return
		}
		connecting.value = true
		hint.value = '正在连接…'
		try {
			await socket.connect(host.value, Number(port.value) || 8881)
			socket.saveTarget(host.value, Number(port.value) || 8881)
			const info = socket.info && socket.info.dev ? socket.info.dev : {}
			pushHistory({
				host: host.value,
				port: Number(port.value) || 8881,
				name: info.name || host.value,
				model: info.model || ''
			})
			hint.value = ''
			uni.navigateTo({ url: '/pages/remote/remote' })
		} catch (e) {
			hint.value = e && e.message ? e.message : '连接失败'
		} finally {
			connecting.value = false
		}
	}

	function quickConnect(d) {
		host.value = d.host
		port.value = d.port
		onConnect()
	}

	function onRemove(hostIp) {
		history.value = removeHistory(hostIp)
	}
</script>

<style lang="scss">
	.page {
		min-height: 100vh;
		padding: calc(var(--status-bar-height) + 48rpx) 40rpx 60rpx;
		background: linear-gradient(180deg, #101823 0%, #0E1218 40%);
	}

	.header {
		margin-bottom: 44rpx;
	}

	.title {
		display: block;
		font-size: 60rpx;
		font-weight: 700;
		color: #FFFFFF;
		letter-spacing: 2rpx;
	}

	.subtitle {
		display: block;
		margin-top: 12rpx;
		font-size: 26rpx;
		color: #7D8899;
	}

	.card {
		background: #182130;
		border-radius: 24rpx;
		padding: 36rpx 32rpx;
		margin-bottom: 28rpx;
	}

	.card-title {
		display: block;
		font-size: 26rpx;
		color: #7D8899;
		margin-bottom: 16rpx;
	}

	.field {
		margin-bottom: 26rpx;
	}

	.label {
		display: block;
		font-size: 24rpx;
		color: #7D8899;
		margin-bottom: 12rpx;
	}

	.input {
		height: 92rpx;
		background: #101823;
		border-radius: 16rpx;
		padding: 0 28rpx;
		font-size: 34rpx;
		color: #E6EDF5;
		font-family: monospace;
	}

	.ph {
		color: #4A5666;
	}

	.btn-row {
		display: flex;
		margin-top: 12rpx;
	}

	.btn {
		flex: 1;
		height: 96rpx;
		border-radius: 18rpx;
		display: flex;
		align-items: center;
		justify-content: center;
	}

	.btn.primary {
		background: #3E9DFF;
		margin-left: 20rpx;
		flex: 1.4;
	}

	.btn.ghost {
		background: #223046;
	}

	.btn.disabled {
		opacity: 0.5;
	}

	.btn-text {
		font-size: 30rpx;
		color: #FFFFFF;
		font-weight: 600;
	}

	.hint {
		display: block;
		margin-top: 24rpx;
		font-size: 24rpx;
		line-height: 1.6;
		color: #FFB020;
	}

	.history-item {
		display: flex;
		align-items: center;
		padding: 22rpx 0;
		border-bottom: 1rpx solid #202B3C;
	}

	.history-item:last-child {
		border-bottom: none;
	}

	.hi-main {
		flex: 1;
	}

	.hi-name {
		display: block;
		font-size: 30rpx;
		color: #E6EDF5;
	}

	.hi-ip {
		display: block;
		margin-top: 6rpx;
		font-size: 24rpx;
		color: #7D8899;
		font-family: monospace;
	}

	.hi-del {
		font-size: 24rpx;
		color: #FF6B6B;
		padding: 10rpx 16rpx;
	}

	.tips {
		margin-top: 16rpx;
	}

	.tip {
		display: block;
		font-size: 24rpx;
		line-height: 2;
		color: #566274;
	}
</style>
