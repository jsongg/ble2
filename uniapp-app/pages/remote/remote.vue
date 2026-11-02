<template>
	<view class="page">
		<!-- 顶部状态条 -->
		<view class="topbar">
			<view class="back" @click="goBack">
				<text class="back-icon">‹</text>
			</view>
			<view class="dev">
				<text class="dev-name">{{ devName }}</text>
				<text class="dev-state" :class="stateClass">{{ stateText }}</text>
			</view>
			<view class="right">
				<text class="rtt">{{ rtt }}ms</text>
				<text class="setting" @click="showSettings = true">⚙</text>
			</view>
		</view>

		<!-- 触摸板 -->
		<view class="pad" :class="{ active: padActive }" @touchstart.stop.prevent="onPadStart"
			@touchmove.stop.prevent="onPadMove" @touchend.stop.prevent="onPadEnd"
			@touchcancel.stop.prevent="onPadEnd">
			<view class="pad-hint" v-if="!used">
				<text class="ph-line">拖动 = 移动指针</text>
				<text class="ph-line">轻点 = 左键 · 长按 = 右键</text>
				<text class="ph-line">双指上下滑 = 滚轮</text>
			</view>
			<view class="pad-feedback" :class="feedbackClass" v-if="feedback">
				<text class="fb-text">{{ feedback }}</text>
			</view>
		</view>

		<!-- 功能键 -->
		<view class="key-row fn-row">
			<view class="key fn" hover-class="key-hover" @click="press('back')">
				<text class="key-text">返回</text>
			</view>
			<view class="key fn" hover-class="key-hover" @click="press('home')">
				<text class="key-text">主页</text>
			</view>
			<view class="key fn" hover-class="key-hover" @click="press('menu')">
				<text class="key-text">菜单</text>
			</view>
			<view class="key fn" hover-class="key-hover" @click="openText">
				<text class="key-text">输入</text>
			</view>
		</view>

		<!-- 方向键 + 音量 -->
		<view class="key-row dpad-row">
			<view class="dpad">
				<view class="cell"></view>
				<view class="key dpad-key" hover-class="key-hover" @touchstart.stop="holdStart('up')"
					@touchend.stop="holdEnd" @touchcancel.stop="holdEnd">
					<text class="arrow">▲</text>
				</view>
				<view class="cell"></view>

				<view class="key dpad-key" hover-class="key-hover" @touchstart.stop="holdStart('left')"
					@touchend.stop="holdEnd" @touchcancel.stop="holdEnd">
					<text class="arrow">◀</text>
				</view>
				<view class="key dpad-key ok" hover-class="key-hover" @click="press('ok')">
					<text class="ok-text">OK</text>
				</view>
				<view class="key dpad-key" hover-class="key-hover" @touchstart.stop="holdStart('right')"
					@touchend.stop="holdEnd" @touchcancel.stop="holdEnd">
					<text class="arrow">▶</text>
				</view>

				<view class="cell"></view>
				<view class="key dpad-key" hover-class="key-hover" @touchstart.stop="holdStart('down')"
					@touchend.stop="holdEnd" @touchcancel.stop="holdEnd">
					<text class="arrow">▼</text>
				</view>
				<view class="cell"></view>
			</view>

			<view class="vol">
				<view class="key vol-key" hover-class="key-hover" @touchstart.stop="holdStart('volup')"
					@touchend.stop="holdEnd" @touchcancel.stop="holdEnd">
					<text class="key-text">音量 +</text>
				</view>
				<view class="key vol-key" hover-class="key-hover" @click="press('mute')">
					<text class="key-text">静音</text>
				</view>
				<view class="key vol-key" hover-class="key-hover" @touchstart.stop="holdStart('voldown')"
					@touchend.stop="holdEnd" @touchcancel.stop="holdEnd">
					<text class="key-text">音量 −</text>
				</view>
			</view>
		</view>

		<!-- 设置弹层 -->
		<view class="mask" v-if="showSettings" @click="showSettings = false">
			<view class="dialog" @click.stop>
				<text class="dialog-title">指针设置</text>

				<view class="slider-item">
					<view class="slider-head">
						<text class="slider-label">移动灵敏度</text>
						<text class="slider-value">{{ sensitivity.toFixed(1) }}x</text>
					</view>
					<slider :value="sensitivity * 10" :min="6" :max="30" :step="1" activeColor="#3E9DFF"
						backgroundColor="#223046" block-size="22" @change="onSensChange" />
				</view>

				<view class="slider-item">
					<view class="slider-head">
						<text class="slider-label">滚轮速度</text>
						<text class="slider-value">{{ scrollStep }}</text>
					</view>
					<slider :value="scrollStep" :min="30" :max="150" :step="10" activeColor="#3E9DFF"
						backgroundColor="#223046" block-size="22" @change="onScrollChange" />
				</view>

				<view class="switch-row">
					<text class="slider-label">按键震动反馈</text>
					<switch :checked="vibrateOn" color="#3E9DFF" @change="onVibrateChange" />
				</view>

				<view class="btn primary full" @click="showSettings = false">
					<text class="btn-text">完成</text>
				</view>
			</view>
		</view>

		<!-- 文字输入弹层 -->
		<view class="mask" v-if="showText" @click="showText = false">
			<view class="dialog" @click.stop>
				<text class="dialog-title">发送文字到电视</text>
				<textarea class="textarea" v-model="textInput" :maxlength="200" placeholder="先让电视上的输入框获得焦点，再发送"
					placeholder-class="ph" auto-height />
				<view class="dialog-btns">
					<view class="btn ghost" @click="showText = false">
						<text class="btn-text">取消</text>
					</view>
					<view class="btn primary" @click="sendText">
						<text class="btn-text">发送</text>
					</view>
				</view>
			</view>
		</view>
	</view>
</template>

<script setup>
	import { ref, computed, onMounted, onUnmounted } from 'vue'
	import socket from '../../common/remote-socket.js'

	const SENS_KEY = 'tv_remote_sens'
	const SCROLL_KEY = 'tv_remote_scroll'
	const VIBRATE_KEY = 'tv_remote_vibrate'

	const serverState = ref(socket.state)
	const rtt = ref(0)
	const devName = ref('电视')
	const padActive = ref(false)
	const used = ref(false)
	const feedback = ref('')
	const showSettings = ref(false)
	const showText = ref(false)
	const textInput = ref('')

	const sensitivity = ref(1.6)
	const scrollStep = ref(80)
	const vibrateOn = ref(true)

	let feedbackTimer = null

	const stateText = computed(() => {
		if (serverState.value === 'open') return '已连接'
		if (serverState.value === 'connecting') return '连接中…'
		return '未连接'
	})

	const stateClass = computed(() => (serverState.value === 'open' ? 'ok' : 'bad'))

	const feedbackClass = computed(() => {
		if (feedback.value === '右键') return 'fb-right'
		if (feedback.value === '单击') return 'fb-tap'
		return ''
	})

	// ------------------------------------------------------------ 触摸板手势

	const LONG_PRESS_MS = 520
	const MOVE_THRESHOLD = 8
	const FLUSH_MS = 16

	let startX = 0
	let startY = 0
	let lastX = 0
	let lastY = 0
	let moved = false
	let multiUsed = false
	let longPressFired = false
	let longPressTimer = null
	let lastMultiY = null
	let scrollAcc = 0
	let accDx = 0
	let accDy = 0
	let flushTimer = null
	let holdTimer = null

	function clearLongPress() {
		if (longPressTimer) {
			clearTimeout(longPressTimer)
			longPressTimer = null
		}
	}

	function onPadStart(e) {
		padActive.value = true
		const touches = e.touches || []

		if (touches.length >= 2) {
			multiUsed = true
			moved = true
			clearLongPress()
			lastMultiY = (touches[0].clientY + touches[1].clientY) / 2
			return
		}

		const t = touches[0]
		if (!t) return
		startX = lastX = t.clientX
		startY = lastY = t.clientY
		moved = false
		longPressFired = false
		lastMultiY = null
		scrollAcc = 0

		clearLongPress()
		longPressTimer = setTimeout(() => {
			longPressTimer = null
			longPressFired = true
			used.value = true
			socket.send({ t: 'longpress', ms: 700 })
			flash('右键')
			buzz()
		}, LONG_PRESS_MS)
	}

	function onPadMove(e) {
		const touches = e.touches || []

		// 双指 -> 滚轮
		if (touches.length >= 2) {
			multiUsed = true
			clearLongPress()
			const cy = (touches[0].clientY + touches[1].clientY) / 2
			if (lastMultiY != null) {
				// 手指向上滑 => 内容向下滚，与手机浏览习惯一致
				scrollAcc += lastMultiY - cy
				if (Math.abs(scrollAcc) >= scrollStep.value) {
					const notches = scrollAcc / scrollStep.value
					socket.send({ t: 'scroll', v: Number(notches.toFixed(2)) })
					scrollAcc = 0
					used.value = true
				}
			}
			lastMultiY = cy
			return
		}

		if (multiUsed) return
		const t = touches[0]
		if (!t) return

		const dx = t.clientX - lastX
		const dy = t.clientY - lastY
		lastX = t.clientX
		lastY = t.clientY

		if (!moved && (Math.abs(t.clientX - startX) > MOVE_THRESHOLD ||
				Math.abs(t.clientY - startY) > MOVE_THRESHOLD)) {
			moved = true
			used.value = true
			clearLongPress()
		}

		if (moved) {
			const k = sensitivity.value * tvScale()
			accDx += dx * k
			accDy += dy * k
			scheduleFlush()
		}
	}

	function onPadEnd(e) {
		clearLongPress()
		const remain = (e.touches && e.touches.length) || 0

		if (multiUsed) {
			if (remain === 0) {
				multiUsed = false
				lastMultiY = null
				scrollAcc = 0
				padActive.value = false
			}
			return
		}

		if (remain > 0) return

		padActive.value = false

		if (longPressFired) {
			longPressFired = false
			return
		}
		if (!moved) {
			socket.send({ t: 'tap' })
			flash('单击')
			buzz()
		}
		flushMove()
	}

	/**
	 * 电视分辨率补偿：1080p 用 1.0，4K 自动放大到 2.0。
	 * 否则在大屏上手指要滑很久才能把指针从左边拖到右边。
	 */
	function tvScale() {
		const w = Number(socket.info && socket.info.w) || 1920
		return Math.min(3, Math.max(0.7, w / 1920))
	}

	function scheduleFlush() {
		if (flushTimer) return
		flushTimer = setTimeout(() => {
			flushTimer = null
			flushMove()
		}, FLUSH_MS)
	}

	function flushMove() {
		if (!accDx && !accDy) return
		const dx = Math.round(accDx)
		const dy = Math.round(accDy)
		accDx = 0
		accDy = 0
		if (dx || dy) socket.send({ t: 'move', dx, dy })
	}

	// ------------------------------------------------------------ 按键

	function press(code) {
		socket.send({ t: 'key', code })
		buzz()
	}

	function holdStart(code) {
		if (holdTimer) return
		press(code)
		holdTimer = setInterval(() => press(code), 200)
	}

	function holdEnd() {
		if (holdTimer) {
			clearInterval(holdTimer)
			holdTimer = null
		}
	}

	// ------------------------------------------------------------ 反馈

	function flash(text) {
		feedback.value = text
		if (feedbackTimer) clearTimeout(feedbackTimer)
		feedbackTimer = setTimeout(() => {
			feedback.value = ''
		}, 700)
	}

	function buzz() {
		if (!vibrateOn.value) return
		try {
			uni.vibrateShort({ fail() {} })
		} catch (e) {}
	}

	// ------------------------------------------------------------ 文字

	function openText() {
		textInput.value = ''
		showText.value = true
	}

	function sendText() {
		const text = textInput.value.trim()
		if (!text) {
			showText.value = false
			return
		}
		socket.send({ t: 'text', s: text })
		showText.value = false
		buzz()
	}

	// ------------------------------------------------------------ 设置

	function onSensChange(e) {
		sensitivity.value = e.detail.value / 10
		try {
			uni.setStorageSync(SENS_KEY, sensitivity.value)
		} catch (err) {}
	}

	function onScrollChange(e) {
		scrollStep.value = e.detail.value
		try {
			uni.setStorageSync(SCROLL_KEY, scrollStep.value)
		} catch (err) {}
	}

	function onVibrateChange(e) {
		vibrateOn.value = e.detail.value
		try {
			uni.setStorageSync(VIBRATE_KEY, vibrateOn.value)
		} catch (err) {}
	}

	// ------------------------------------------------------------ 生命周期

	function onSocketState(evt) {
		serverState.value = evt.state
		if (evt.state === 'closed') {
			uni.showToast({ title: '连接已断开', icon: 'none' })
			setTimeout(() => {
				uni.navigateBack({ fail: () => uni.reLaunch({ url: '/pages/index/index' }) })
			}, 900)
		}
	}

	let rttTimer = null

	onMounted(() => {
		try {
			const s = uni.getStorageSync(SENS_KEY)
			if (s) sensitivity.value = Number(s)
			const sc = uni.getStorageSync(SCROLL_KEY)
			if (sc) scrollStep.value = Number(sc)
			const v = uni.getStorageSync(VIBRATE_KEY)
			if (v === false) vibrateOn.value = false
		} catch (e) {}

		serverState.value = socket.state
		socket.on('state', onSocketState)

		const dev = socket.info && socket.info.dev
		if (dev && dev.name) devName.value = dev.name

		rttTimer = setInterval(() => {
			rtt.value = socket.rtt
			if (socket.info && socket.info.dev && socket.info.dev.name) {
				devName.value = socket.info.dev.name
			}
		}, 1000)
	})

	onUnmounted(() => {
		socket.off('state', onSocketState)
		holdEnd()
		clearLongPress()
		if (flushTimer) clearTimeout(flushTimer)
		if (rttTimer) clearInterval(rttTimer)
		if (feedbackTimer) clearTimeout(feedbackTimer)
	})

	function goBack() {
		uni.navigateBack({ fail: () => uni.reLaunch({ url: '/pages/index/index' }) })
	}
</script>

<style lang="scss">
	.page {
		height: 100vh;
		display: flex;
		flex-direction: column;
		padding: calc(var(--status-bar-height) + 16rpx) 24rpx 24rpx;
		background: #0E1218;
	}

	/* ---------------- 顶部 ---------------- */
	.topbar {
		height: 92rpx;
		display: flex;
		align-items: center;
		padding: 0 8rpx;
	}

	.back {
		width: 72rpx;
		height: 72rpx;
		display: flex;
		align-items: center;
		justify-content: center;
	}

	.back-icon {
		font-size: 56rpx;
		color: #E6EDF5;
		line-height: 1;
	}

	.dev {
		flex: 1;
		padding-left: 8rpx;
	}

	.dev-name {
		display: block;
		font-size: 30rpx;
		color: #E6EDF5;
		font-weight: 600;
	}

	.dev-state {
		display: block;
		margin-top: 2rpx;
		font-size: 22rpx;
	}

	.dev-state.ok {
		color: #4CD964;
	}

	.dev-state.bad {
		color: #FF6B6B;
	}

	.right {
		display: flex;
		align-items: center;
	}

	.rtt {
		font-size: 22rpx;
		color: #566274;
		margin-right: 20rpx;
	}

	.setting {
		font-size: 40rpx;
		color: #7D8899;
		padding: 0 12rpx;
	}

	/* ---------------- 触摸板 ---------------- */
	.pad {
		flex: 1;
		margin: 8rpx 0 20rpx;
		background: #141B26;
		border: 2rpx solid #1F2A3A;
		border-radius: 28rpx;
		display: flex;
		align-items: center;
		justify-content: center;
		position: relative;
		transition: background 0.12s;
		overflow: hidden;
	}

	.pad.active {
		background: #1A2433;
		border-color: #2E4260;
	}

	.pad-hint {
		display: flex;
		flex-direction: column;
		align-items: center;
	}

	.ph-line {
		font-size: 26rpx;
		color: #3E4C60;
		line-height: 2;
	}

	.pad-feedback {
		position: absolute;
		padding: 14rpx 32rpx;
		border-radius: 40rpx;
		background: rgba(62, 157, 255, 0.18);
	}

	.pad-feedback.fb-right {
		background: rgba(255, 176, 32, 0.18);
	}

	.pad-feedback.fb-tap {
		background: rgba(76, 217, 100, 0.18);
	}

	.fb-text {
		font-size: 28rpx;
		color: #E6EDF5;
	}

	/* ---------------- 按键 ---------------- */
	.key-row {
		display: flex;
		margin-bottom: 16rpx;
	}

	.key {
		background: #182130;
		border-radius: 20rpx;
		display: flex;
		align-items: center;
		justify-content: center;
	}

	.key-hover {
		background: #24344A;
	}

	.key-text {
		font-size: 28rpx;
		color: #C9D4E2;
	}

	.fn-row {
	}

	.fn {
		flex: 1;
		height: 96rpx;
		margin-right: 16rpx;
	}

	.fn:last-child {
		margin-right: 0;
	}

	.dpad-row {
		align-items: stretch;
	}

	.dpad {
		width: 340rpx;
		height: 340rpx;
		display: grid;
		grid-template-columns: repeat(3, 1fr);
		grid-template-rows: repeat(3, 1fr);
		gap: 12rpx;
	}

	.cell {
	}

	.dpad-key {
		border-radius: 18rpx;
	}

	.dpad-key.ok {
		background: #223046;
	}

	.arrow {
		font-size: 30rpx;
		color: #C9D4E2;
	}

	.ok-text {
		font-size: 30rpx;
		color: #FFFFFF;
		font-weight: 700;
	}

	.vol {
		flex: 1;
		margin-left: 16rpx;
		display: flex;
		flex-direction: column;
	}

	.vol-key {
		flex: 1;
		margin-bottom: 12rpx;
	}

	.vol-key:last-child {
		margin-bottom: 0;
	}

	/* ---------------- 弹层 ---------------- */
	.mask {
		position: fixed;
		left: 0;
		top: 0;
		right: 0;
		bottom: 0;
		background: rgba(0, 0, 0, 0.65);
		display: flex;
		align-items: center;
		justify-content: center;
		z-index: 99;
	}

	.dialog {
		width: 620rpx;
		background: #182130;
		border-radius: 28rpx;
		padding: 40rpx 36rpx;
	}

	.dialog-title {
		display: block;
		font-size: 32rpx;
		color: #E6EDF5;
		font-weight: 600;
		margin-bottom: 28rpx;
	}

	.slider-item {
		margin-bottom: 34rpx;
	}

	.slider-head {
		display: flex;
		justify-content: space-between;
		align-items: center;
		margin-bottom: 6rpx;
	}

	.slider-label {
		font-size: 26rpx;
		color: #9AA7B8;
	}

	.slider-value {
		font-size: 26rpx;
		color: #3E9DFF;
		font-family: monospace;
	}

	.switch-row {
		display: flex;
		justify-content: space-between;
		align-items: center;
		margin-bottom: 34rpx;
	}

	.textarea {
		width: 100%;
		min-height: 160rpx;
		background: #101823;
		border-radius: 16rpx;
		padding: 24rpx;
		font-size: 28rpx;
		color: #E6EDF5;
		margin-bottom: 28rpx;
	}

	.ph {
		color: #4A5666;
	}

	.dialog-btns {
		display: flex;
	}

	.btn {
		flex: 1;
		height: 90rpx;
		border-radius: 18rpx;
		display: flex;
		align-items: center;
		justify-content: center;
	}

	.btn.primary {
		background: #3E9DFF;
		margin-left: 20rpx;
	}

	.btn.ghost {
		background: #223046;
	}

	.btn.full {
		margin-left: 0;
	}

	.btn-text {
		font-size: 30rpx;
		color: #FFFFFF;
		font-weight: 600;
	}
</style>
