<script>
	import remoteSocket from './common/remote-socket.js'

	export default {
		onLaunch() {
			// App 端启动时把上次连接的电视地址准备好，页面里直接用
			remoteSocket.loadLastTarget()
		},
		onHide() {
			// 后台时保持连接（用户可能切出去看时间再回来）
		}
	}
</script>

<style>
	/* 全局基础样式：整体走深色，避免夜里看手机刺眼 */
	page {
		background-color: #0E1218;
		color: #E6EDF5;
		font-family: -apple-system, "Helvetica Neue", "PingFang SC", "Microsoft YaHei", sans-serif;
	}

	view,
	text,
	button,
	input {
		box-sizing: border-box;
	}
</style>
