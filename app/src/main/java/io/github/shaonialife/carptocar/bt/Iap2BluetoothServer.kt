package io.github.shaonialife.carptocar.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.shaonialife.carptocar.iap2.CarPlayNowPlaying
import io.github.shaonialife.carptocar.iap2.Iap2SenderSession
import io.github.shaonialife.carptocar.transport.Iap2LinkChannel
import io.github.shaonialife.carptocar.transport.StreamDuplexStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * 手机侧（iPhone 角色）的 iAP2 蓝牙 RFCOMM 监听。
 *
 * 真实无线 CarPlay 里 iPhone 配对后监听这个 SDP 服务等待车机回连；
 * 测试台上 DiPlay/xcertplay 会扫描已配对的 PHONE_SMART 设备并主动连入。
 *
 * 连入后：链路协商（marker/同步/ACK）→ 完整发送端 bring-up（Phase 1），
 * 成功时回调 [onEndpoint] 交出热点 SSID/密码与 AirPlay 端点。
 */
class Iap2BluetoothServer(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onEndpoint: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** 0x5703 一到即触发（绑 WiFi + 广播 _carplay-ctrl），真车 0x4301 的前置条件。 */
    private val onWifiCredentials: (Iap2SenderSession.WifiConfiguration) -> Unit = {},
    /** Phase 1 之后车机补发 0x4301（CarPlay 窗口晚开）时交给主控热更新端点。 */
    private val onEndpointUpdate: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** 目标车机（动态读取：拒绝非目标设备的连入；null=来者不拒）。 */
    private val targetProvider: () -> TargetDevicePrefs.Target? = { null },
    /** 「正在播放」信息提供者（歌名/歌手/专辑，见 [Iap2SenderSession]）。 */
    private val nowPlayingProvider: (() -> CarPlayNowPlaying?)? = null,
    /** 保活泵退出（BT 链路断开）时回调：主控据此释放「会话处理中」（僵尸轮次堵门修复）。 */
    private val onLinkClosed: () -> Unit = {},
) {
    companion object {
        /**
         * iPhone 的 iAP2 服务 UUID（Oligo Security 公布；与 xcertplay
         * CarPlayController.IAP2_IPHONE_UUID 一致）。配件端是 …decacaff。
         */
        val IPHONE_IAP2_UUID: UUID = CarPlaySdp.IPHONE_IAP2_UUID

        /**
         * Aptiv/GWM V3 车机（坦克 300 等同平台）判定「无线 CarPlay 设备」用的探针 UUID。
         * 见 [CarPlaySdp] 与 `docs/tank300-gwm-aptiv-compatibility.md`。
         */
        val IDENTITY_PROBE_UUID: UUID = CarPlaySdp.IDENTITY_PROBE_UUID

        private const val SDP_NAME = CarPlaySdp.IAP2_SDP_NAME
        private const val PROBE_SDP_NAME = CarPlaySdp.IDENTITY_PROBE_NAME
        private const val SESSION_TIMEOUT_MILLIS = 60_000L
    }

    private var serverSocket: AtomicReference<BluetoothServerSocket?> = AtomicReference(null)

    /**
     * 「CarPlay 身份探针」的 SDP 监听。它只负责让手机 SDP 里出现
     * [IDENTITY_PROBE_UUID]，不承载协议：车机连进来就立即关掉。
     */
    private var probeSocket: BluetoothServerSocket? = null

    @Volatile
    private var probePublished = false

    /** 探针记录是否已发布（日志/诊断用）。 */
    val isProbePublished: Boolean get() = probePublished

    @Volatile
    private var activeChannel: Iap2LinkChannel? = null

    /** 当前保活阶段的发送端会话（bring-up 完成后由 runSenderSession 回填）。 */
    @Volatile
    private var senderSession: Iap2SenderSession? = null

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    /** 歌名变化时主动补推 0x5001（转发给当前保活会话）。 */
    fun pushNowPlaying() {
        senderSession?.pushNowPlaying()
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 发布「CarPlay 身份探针」SDP 记录（幂等，可重复调用）。
     *
     * Aptiv/GWM V3 车机（坦克 300 等同平台）在
     * `AptivBluetoothConnectionManagerService.isCPSupportedOverWiFi()` 里**只查**
     * [IDENTITY_PROBE_UUID]。只监听 …cafe 时它不会把本机归到无线 CarPlay 设备，
     * `PhoneProjectionService` 也就不会发起无线会话。探针本身不承载协议：
     * 车机若连进来立即关闭，SDP 记录留在蓝牙栈里供查询。
     *
     * 越早发布越好——车机在配对 / 首次 SDP 查询时就会缓存这份 UUID 清单；
     * 配对完成后再发布，车机可能直到重新配对都不会再查。
     */
    @SuppressLint("MissingPermission")
    fun startProbeRecord(): Boolean {
        synchronized(this) {
            if (probeSocket != null) return true
        }
        if (!hasPermission()) {
            log("BT: 缺少 BLUETOOTH_CONNECT 权限——CarPlay 身份探针未发布")
            return false
        }
        val adapter =
            try {
                context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
            } catch (_: Exception) {
                null
            }
        if (adapter == null || !adapter.isEnabled) {
            log("BT: 蓝牙不可用或未开启——CarPlay 身份探针未发布")
            return false
        }
        synchronized(this) {
            if (probeSocket != null) return true
            return try {
                val socket =
                    adapter.listenUsingRfcommWithServiceRecord(
                        PROBE_SDP_NAME,
                        IDENTITY_PROBE_UUID,
                    )
                probeSocket = socket
                probePublished = true
                log(
                    "BT: CarPlay 身份探针 SDP 已发布（$IDENTITY_PROBE_UUID）——" +
                        "Aptiv/GWM 车机据此把本机识别为无线 CarPlay 设备",
                )
                Thread(::probeAcceptLoop, "carplay-sdp-probe").apply {
                    isDaemon = true
                    start()
                }
                true
            } catch (error: IOException) {
                log("BT: CarPlay 身份探针发布失败 - ${error.message}")
                false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun probeAcceptLoop() {
        while (true) {
            val socket = synchronized(this) { probeSocket } ?: break
            try {
                val accepted = socket.accept()
                log("BT: 车机连入 CarPlay 身份探针（仅 SDP 识别，立即关闭连接）")
                runCatching { accepted.close() }
            } catch (error: IOException) {
                if (synchronized(this) { probeSocket } != null) {
                    log("BT: CarPlay 身份探针 accept 结束 - ${error.message}")
                }
                break
            }
        }
    }

    private fun stopProbeRecord() {
        val socket =
            synchronized(this) {
                val current = probeSocket
                probeSocket = null
                probePublished = false
                current
            } ?: return
        runCatching { socket.close() }
        log("BT: CarPlay 身份探针 SDP 已撤下")
    }

    fun start(): Boolean {
        if (running) return true
        if (!hasPermission()) {
            log("BT: 缺少 BLUETOOTH_CONNECT 权限")
            return false
        }
        val adapter =
            try {
                context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
            } catch (_: Exception) {
                null
            }
        if (adapter == null || !adapter.isEnabled) {
            log("BT: 蓝牙不可用或未开启")
            return false
        }
        // 探针先于 iAP2 监听发布：车机可能在看到 …cafe 之前就先查无线 CarPlay 能力。
        startProbeRecord()
        return try {
            serverSocket.set(
                adapter.listenUsingRfcommWithServiceRecord(SDP_NAME, IPHONE_IAP2_UUID),
            )
            running = true
            log("BT: 监听 iAP2 服务与 CarPlay 身份探针，等待接收端回连…")
            Thread(::acceptLoop, "iap2-bt-accept").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: IOException) {
            log("BT: 监听失败 - ${error.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun acceptLoop() {
        while (running) {
            val socket: BluetoothSocket =
                try {
                    serverSocket.get()?.accept() ?: break
                } catch (error: IOException) {
                    if (running) log("BT: accept 失败 - ${error.message}")
                    break
                }
            val peerName =
                try {
                    socket.remoteDevice?.name
                } catch (_: SecurityException) {
                    null
                } ?: "unknown"
            // 指定了目标车机时拒绝其它设备的连入（用户配对了多台车机：非目标车机
            // 的控制器也会周期回连，放行会抢占/误建会话）。地址比对，大小写不敏感。
            val target = targetProvider()
            val peerAddress =
                try {
                    socket.remoteDevice?.address
                } catch (_: SecurityException) {
                    null
                }
            if (target != null &&
                !BtSessionGate.targetSoftFallback &&
                !TargetDevicePrefs.matches(target, peerAddress)
            ) {
                log(
                    "BT: 忽略非目标设备连入 name=$peerName addr=${peerAddress ?: "?"}" +
                        "（当前目标：「${target.name}」）",
                )
                runCatching { socket.close() }
                continue
            }
            log("BT: 接收端已连入 name=$peerName，开始 iAP2 发送端会话")
            Thread({ runSession(socket) }, "iap2-bt-session").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun runSession(socket: BluetoothSocket) {
        val stream =
            StreamDuplexStream(socket.inputStream, socket.outputStream) {
                runCatching { socket.close() }
            }
        val channel = Iap2LinkChannel(stream, name = "iap2-bt") { line -> log(line) }
        activeChannel = channel
        BtSessionGate.inboundActive = true
        try {
            channel.runSenderSession(
                context,
                SESSION_TIMEOUT_MILLIS,
                log,
                onEndpoint,
                onWifiCredentials,
                onEndpointUpdate,
                nowPlayingProvider,
                onSessionReady = { session -> senderSession = session },
                onLinkClosed = onLinkClosed,
            )
        } catch (error: IOException) {
            log("SESSION 失败: ${error.message}")
        } finally {
            activeChannel = null
            senderSession = null
            BtSessionGate.inboundActive = false
            channel.close()
            // 两条完全不同的撤退路径，日志必须能分辨（否则用户以为「蓝牙连上就断 = App 有 bug」）：
            //  - sessionActive=true：AirPlay 已在 WiFi 上跑起来，接收端把 iAP2 迁到控制通道
            //    隧道（VirtualIap2Link）后**主动关掉 RFCOMM**。WinPlay 1.2.4 实测：
            //    20:07:31 连入 → 20:07:32.4 隧道建立 → 32.52 对端 close（read -1）。
            //    这是无线 CarPlay 的正常交接，画面/音频/触摸全程不受影响。
            //  - sessionActive=false：会话在拿到端点前就断了，才是真故障，等重连。
            log(
                if (BtSessionGate.sessionActive) {
                    "SESSION 结束——接收端已断开 RFCOMM，iAP2 已迁到 WiFi 隧道" +
                        "（无线 CarPlay 正常交接，继续推流；BT 监听仍在，需要时会再连）"
                } else {
                    "SESSION 结束，等待接收端重连（BT 监听仍在运行）"
                },
            )
        }
    }

    /** 主动结束当前 BT 控制会话（不影响监听）。用于 AirPlay 配对前复现控制通道退役状态。 */
    fun detachCurrentSession() {
        activeChannel?.let { channel ->
            activeChannel = null
            channel.close()
            log("BT: 已主动断开当前控制会话（模拟 tunnel handoff 后的退役）")
        }
    }

    fun stop() {
        running = false
        serverSocket.getAndSet(null)?.let { runCatching { it.close() } }
        stopProbeRecord()
        log("BT: 监听已停止")
    }
}
