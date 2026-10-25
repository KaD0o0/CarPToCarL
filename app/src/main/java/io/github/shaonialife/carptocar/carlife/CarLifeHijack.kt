package io.github.shaonialife.carptocar.carlife

import android.content.Context
import io.github.shaonialife.carptocar.media.MediaSessionsState
import io.github.shaonialife.carptocar.video.VideoSender
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「本地劫持原生 CarLife」总控 —— **可选**的增强画面来源。
 *
 * 2026-10-24 产品方向变更：默认画面来源回归 **CarDeck 自绘桌面**（CarDeck 自己扮演
 * 「CarLife+ 发送端」的内容角色，协议仍是 CarPlay/AirPlay，见 SessionController
 * 的自绘分支）。本模块降级为 opt-in：只有用户在设置里打开「本地 CarLife 劫持」
 * 才会守候 7240 并接管原生 CarLife+ 码流。
 *
 * 开启后的行为不变（劫持**先于**车机会话启动）：
 *
 * ```
 * 打开 App → prewarm()：只起一个「守候线程」，**不拉起 CarLife+**
 * 你手动打开 CarLife+ 点「无线连接」→ 7240 开始监听
 *          → 守候线程发现端口 → 冒充车机连上去 → 收到 H.264 并缓存最近一个 GOP
 * 点「连接车机」→ 会话 RECORD → startPassthrough() → attach()：把缓存 GOP 灌进去
 *          → 之后每帧实时直通（不重编码）
 * ```
 */
object CarLifeHijack {

    private const val PREFS = "settings"
    private const val KEY_ENABLED = "carlife_hijack_enabled"

    /** 向 CarLife 引擎申请的编码帧率（60 = 跟手，30 = 省电）。 */
    private const val KEY_FPS = "carlife_fps"
    /**
     * 目标编码帧率。
     *
     * 实测结论（0.2.7）：CarLife 引擎把视频源钉在 20fps（50ms 定时器），98316 改到 30 反而
     * 掉到 15.4fps——33ms 轮询去取 50ms 才来一帧的源，拍频成 66ms。
     *
     * 0.2.9 方案 A 实测：把 98311 声明的 fps 直接拉到 60，看手机引擎回显的
     * `MD_VIDEO_ENC_INIT` 是否跟着变、以及实际推流帧率有没有提升。若引擎仍回 20，
     * 说明 98311 的 fps 确实只是回显、采集线程钉死 20，此路不通。
     */
    private const val DEFAULT_FPS = 60

    /** 98386 连接类型（5=WiFi；1~4 为 USB 等其它档位，用于试更高编码档）。 */
    private const val KEY_CONNECT_TYPE = "carlife_connect_type"

    /** 逗号分隔的帧率变更命令候选 id，用于实测反推协议命令号。 */
    private const val KEY_PROBE_IDS = "fps_probe_ids"
    private const val DEFAULT_CONNECT_TYPE = 5

    /**
     * 一次性迁移标记 v1（历史）：曾把劫持对老安装强制开启一次。
     *
     * 2026-10-24 方向变更后被 v2 取代：v2 把被 v1 强开的劫持一次性关回去，
     * 默认画面来源回归 CarDeck 自绘桌面。
     */
    private const val KEY_MIGRATED_ON = "carlife_hijack_default_on_v1"

    /** 一次性迁移标记 v2：劫持回归 opt-in，默认关闭（只执行一次）。 */
    private const val KEY_MIGRATED_OFF_V2 = "carlife_hijack_default_off_v2"

    /**
     * 一次性迁移标记 v3：劫持回归默认开启（只执行一次）。
     *
     * 2026-10-24 起画面唯一来源就是「本地劫持原生 CarLife」（自绘桌面已移除），
     * 关着劫持 = 永远黑屏，所以默认改回开启。老安装若被 v2 关掉过，这里一次性打开。
     */
    private const val KEY_MIGRATED_ON_V3 = "carlife_hijack_default_on_v3"

    /** 未接入会话时缓存的最大 GOP 字节数 / 帧数（约 2 秒 720p 的量级）。 */
    private const val MAX_GOP_BYTES = 6 * 1024 * 1024
    private const val MAX_GOP_FRAMES = 240

    private val running = AtomicBoolean(false)

    /** 向 CarLife 引擎申请的编码帧率（默认 60，可在偏好 carlife_fps 里改）。 */
    @Volatile
    private var requestedFps = 20

    /** 98386 里的 CONNECT_TYPE（默认 5=WiFi，可在偏好 carlife_connect_type 里改）。 */
    @Volatile
    private var connectType = 5

    /** 帧率变更命令的候选 id（可由偏好覆盖）。 */
    private var probeIds: IntArray = CarLifeProtocol.FRAME_RATE_PROBE_IDS

    @Volatile
    private var client: CarLifeHuClient? = null

    /** 当前会话的视频通道；为 null 时收到的帧只进 GOP 缓存。 */
    @Volatile
    private var sender: VideoSender? = null

    private var audioSink: CarLifeAudioSink? = null

    /** 最近一条 CarLife 媒体信息（0x10035，歌名/歌手/封面）。供 iAP2 0x5001 转发。 */
    @Volatile
    var latestMediaInfo: CarLifeMediaInfo? = null
        private set

    /** 媒体信息变化监听（歌名/歌手变化时触发）。 */
    private val mediaInfoListeners = java.util.concurrent.CopyOnWriteArrayList<(CarLifeMediaInfo) -> Unit>()

    fun addMediaInfoListener(listener: (CarLifeMediaInfo) -> Unit) {
        mediaInfoListeners.add(listener)
    }

    fun removeMediaInfoListener(listener: (CarLifeMediaInfo) -> Unit) {
        mediaInfoListeners.remove(listener)
    }

    private var worker: Thread? = null

    /**
     * 音乐会话状态监听：车机按暂停 → 手机音乐 App PAUSED → [MediaSessionsState] 触发这里
     * → 联动 [CarLifeAudioSink.setPlaybackPaused] 截断/放行音频。prewarm 时注册一次，stop 时移除。
     */
    private val mediaPlaybackListener: () -> Unit = {
        val sink = audioSink
        if (sink != null) {
            // 明确 PAUSED 才截断；读不到会话（null）不截断——避免误丢引擎 PCM（本地模拟无声的坑）
            val paused = MediaSessionsState.nowPlaying?.playing == false
            sink.setPlaybackPaused(paused)
        }
    }

    /** 会话日志出口（prewarm 时注入）。 */
    @Volatile
    private var logSink: ((String) -> Unit)? = null

    /** 车机通告的视图尺寸（触摸报文坐标系），由会话建立时写入。 */
    @Volatile
    private var viewW = 0

    @Volatile
    private var viewH = 0

    /**
     * 车机真实分辨率（编码分辨率申请）。
     *
     * 缓存原因见 [setRequestedResolution]：AirPlay SETUP 早于 CarLife 握手完成，
     * 若只转发给当时还不存在的 client 会丢失。0 表示未收到过车机通告。
     */
    @Volatile
    private var requestedW = 0

    @Volatile
    private var requestedH = 0

    /** 触摸按下状态（HID 只给 down 布尔，需要自己合成 DOWN/MOVE/UP）。 */
    @Volatile
    private var touchPressed = false

    private var lastTouchAt = 0L
    private var touchCount = 0

    /** 未开启音频通道时被丢弃的 PCM 累计字节（限频提示）。 */
    private var audioDroppedBytes = 0L
    private var lastAudioDropLog = 0L

    /** 最近一个 GOP 缓存（从关键帧起），供会话建立瞬间「补历史帧」。 */
    private val gop = ArrayDeque<Pair<ByteArray, Boolean>>()
    private var gopBytes = 0
    private val gopLock = Any()

    /**
     * 最近一次见到的 SPS+PPS（含起始码）。
     *
     * CarLife 引擎**只在编码器刚初始化时**的那一个 IDR 里带 SPS/PPS，之后的周期性 IDR
     * （码率自适应会频繁插入）只有裸 IDR 片。若会话恰好在「带 SPS 的那帧」已经滚出缓存
     * 之后才建立，`VideoSender` 的 configSent 会永远为 false → **每一帧都被丢弃**，
     * 表现为「车机连上了但永久黑屏」（2026-10-24 实机复现：已补 8 帧 / 实际推送 0）。
     * 所以这里把 SPS/PPS 单独留存，遇到裸 IDR 时临时补回去。
     */
    @Volatile
    private var spsPps: ByteArray? = null

    /** 已收到过至少一帧（用于 UI 判断「可以连车机了」）。 */
    @Volatile
    var hasVideo: Boolean = false
        private set

    /** 已劫持并收到流（车机连接前就该为 true）。 */
    val isStreaming: Boolean get() = running.get() && client?.isRunning == true && hasVideo

    /** 已成功写进车机通道的帧数（区分「握手成功」与「车机真在收画面」）。 */
    @Volatile
    var framesDelivered: Long = 0
        private set

    private var firstDeliverLogged = false
    private var deliverWindowStart = 0L
    private var deliverWindowFrames = 0
    private var deliverWindowBytes = 0
    private var recvWindowFrames = 0
    private var recvWindowKeys = 0
    private var recvWindowBytes = 0

    /** 车机通道是否已接上（画面正在直通）。 */
    val isAttached: Boolean get() = sender != null

    /** 劫持是否活跃（兼容旧调用点：触摸路由据此切换目标）。 */
    val isActive: Boolean get() = running.get() && client?.isRunning == true

    /** 当前 CarLife 视频分辨率（prewarm 阶段即可用于计算推流画布）。 */
    val videoSize: Pair<Int, Int>
        get() = client?.videoSize ?: (1280 to 720)

    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // v3 迁移：画面唯一来源就是 CarLife 劫持，默认改回开启。老安装若被 v2 关掉过，
        // 这里一次性打开；之后完全尊重用户在设置里的选择。
        if (!prefs.getBoolean(KEY_MIGRATED_ON_V3, false)) {
            prefs.edit()
                .putBoolean(KEY_ENABLED, true)
                .putBoolean(KEY_MIGRATED_ON_V3, true)
                .apply()
        }
        return prefs.getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * 开始守候本机 CarLife+（**不拉起任何应用**，也不需要视频会话）。
     *
     * 只在后台轮询 7240：用户自己进 CarLife+ 点「无线连接」后端口一开就被接管。
     * 可在 App 启动时调用；重复调用安全（已在跑直接返回 true）。
     * 成功只代表「守候线程已启动」，真正连上后 [isStreaming] 才变 true。
     */
    fun prewarm(
        context: Context,
        log: (String) -> Unit,
    ): Boolean {
        if (running.get()) return true
        running.set(true)
        logSink = log
        touchPressed = false
        touchCount = 0
        hasVideo = false
        requestedFps = context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_FPS, DEFAULT_FPS)
            .coerceIn(3, 60)
        connectType = context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CONNECT_TYPE, DEFAULT_CONNECT_TYPE)
            .coerceIn(1, 8)
        probeIds = context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROBE_IDS, null)
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.toIntArray()
            ?.takeIf { it.isNotEmpty() }
            ?: CarLifeProtocol.FRAME_RATE_PROBE_IDS

        worker = Thread({ workerLoop() }, "carlife-hijack").apply {
            isDaemon = true
            start()
        }
        MediaSessionsState.addListener(mediaPlaybackListener)
        return true
    }

    /**
     * 会话建立后把车机通道接上：把缓存的 GOP 立刻灌进去，之后每帧实时直通。
     *
     * 调用前 [VideoSender.startPassthrough] 必须已成功（数据端口就绪）。
     */
    fun attach(videoSender: VideoSender, log: (String) -> Unit) {
        logSink = log
        val pending = synchronized(gopLock) {
            val snapshot = gop.toList()
            gop.clear()
            gopBytes = 0
            snapshot
        }
        sender = videoSender
        startFirstFrameWatchdog(videoSender)
        if (pending.isEmpty()) {
            log("CARLIFE: 尚无可补的历史帧（缓存为空），等待 CarLife 推流…")
            return
        }
        var pushed = 0
        for ((data, key) in pending) {
            if (videoSender.submitH264(data, key)) {
                pushed++
                noteDelivered(data.size)
            }
        }
        log("CARLIFE: 已补 ${pending.size} 帧历史画面（实际推送 $pushed）")
        // 一帧都没送出去 = 缓存里没有 SPS/PPS（VideoConfig 发不出去就会全速丢帧）。
        // 让引擎重建编码器，它随后会补一个带 SPS 的 IDR，画面即可恢复。
        if (pushed == 0) {
            log("CARLIFE: 缓存里没有 SPS/PPS——请求引擎重建编码器（约 1 秒后出首帧）")
            client?.requestVideoReinit()
        }
    }

    /**
     * 接上通道后 3 秒仍一帧都送不出去，说明 VideoConfig 卡住了（SPS 缺失）。
     * 再补一次编码器重建；重建后引擎必然发「SPS+PPS+IDR」，链路自愈。
     */
    private fun startFirstFrameWatchdog(videoSender: VideoSender) {
        val base = framesDelivered
        Thread {
            try {
                Thread.sleep(3_000)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (sender !== videoSender || framesDelivered > base) return@Thread
            logSink?.invoke("CARLIFE: 接上通道 3 秒零帧——再请求一次编码器重建")
            client?.requestVideoReinit()
        }.apply { isDaemon = true }.start()
    }

    /** 会话结束：断开视频通道，回到「只缓存」状态（CarLife 连接保持不断）。 */
    fun detach() {
        sender = null
        firstDeliverLogged = false
        deliverWindowFrames = 0
    }

    /** 完全停止（退出 App / 关闭开关）。 */
    fun stop() {
        running.set(false)
        MediaSessionsState.removeListener(mediaPlaybackListener)
        sender = null
        client?.stop()
        client = null
        audioSink?.stop()
        audioSink = null
        touchPressed = false
        worker?.interrupt()
        worker = null
        logSink = null
        hasVideo = false
        synchronized(gopLock) {
            gop.clear()
            gopBytes = 0
        }
    }

    // ---- 后台主循环 ----

    private fun workerLoop() {
        var announcedWait = false
        while (running.get()) {
            // 2026-10-25 回滚：**不再自动拉起** CarLife 引擎。
            // 自动拉起（bindService / startForegroundService / CarlifeActivity）实测能让
            // 引擎起来并监听 7240，但拿不到荣耀的画面源——引擎只会把「荣耀车联 Powered by
            // MagicOS」欢迎页反复编码送过来（恒定 ~10KB 静止帧），车机侧表现为
            // 「连接成功但长时间没画面」。真实画面必须由荣耀车联自己创建虚拟屏后才有，
            // 所以改回：用户手动打开 CarLife+ 点「无线连接」，本线程只负责守候并接管。
            if (!portListening(CarLifeProtocol.PORT_CMD)) {
                hasVideo = false
                if (!announcedWait) {
                    announcedWait = true
                    logSink?.invoke(
                        "CARLIFE: 7240 未监听，等待本机 CarLife 引擎启动" +
                            "（请在手机端打开 CarLife+ 并点「无线连接」，端口一开立即接管）",
                    )
                }
                sleep(1500)
                continue
            }
            announcedWait = false
            val sink = CarLifeAudioSink(logSink ?: {})
            audioSink = sink
            // 创建即应用当前播放状态：若此刻音乐已是 PAUSED，立刻截断（否则 listener 只在
            // 状态「变化」时触发，稳态 PAUSED 下新 sink 会漏掉这次联动）。
            // 注意：nowPlaying == null（读不到媒体会话：未授权通知使用权 / 本地模拟无会话）
            // 时**不能**当作「暂停」来截断——那会误丢引擎仍在推的 PCM，导致本地模拟无声。
            // 读不到状态 = 不确定，宁可不截断（放行），交由引擎侧 STOP/DATA 自行决定。
            sink.setPlaybackPaused(MediaSessionsState.nowPlaying?.playing == false)
            val hu = CarLifeHuClient(
                host = "127.0.0.1",
                log = { line -> logSink?.invoke(line) },
                onVideoFrame = { data, key -> onFrame(data, key) },
                onVideoParams = { w, h, f -> logSink?.invoke("CARLIFE: 手机编码参数 ${w}x$h@$f") },
                onAudioFormat = { rate, channels -> sink.configure(rate, channels) },
                onAudioPcm = { pcm, size -> routeAudio(sink, pcm, size) },
                onMediaInfo = { info -> onMediaInfo(info) },
                requestedFps = requestedFps,
                connectType = connectType,
                probeIds = probeIds,
                // 回放 SETUP 阶段缓存的车机真实分辨率：首次 98311 就报对，不必二次重发。
                // 未收到通告时退回 1280×720（引擎默认档位）。
                initialWidth = if (requestedW > 0) requestedW else 1280,
                initialHeight = if (requestedH > 0) requestedH else 720,
            )
            if (hu.start()) {
                client = hu
                logSink?.invoke("CARLIFE: 劫持成功，已接管本机 CarLife 码流（等待连接车机）")
            } else {
                logSink?.invoke("CARLIFE: 握手失败，2 秒后重试（确认 CarLife 停在「正在连接」）")
            }
            // 保持连接直到断开或停止
            while (running.get() && hu.isRunning) {
                sleep(500)
            }
            if (client === hu) client = null
            sink.stop()
            if (audioSink === sink) audioSink = null
            hasVideo = false
            if (running.get()) sleep(1500)
        }
    }

    /** 一节视频帧：有会话就直通，没有就缓存（从关键帧开始）。 */
    private fun onFrame(
        data: ByteArray,
        keyFrame: Boolean,
    ) {
        recvWindowFrames++
        recvWindowBytes += data.size
        if (keyFrame) recvWindowKeys++
        if (!hasVideo && data.isNotEmpty()) {
            hasVideo = true
            // 独立于一会话：还没连车机时也要能看出「CarLife 到底在不在推流」。
            // 排查编码器被配置搞挂时，这是唯一的直接证据。
            logSink?.invoke("CARLIFE: ✓ 收到 CarLife 首帧（${data.size}B，keyFrame=$keyFrame）")
        }
        rememberSpsPps(data)
        val frame = ensureSpsPps(data)
        val target = sender
        if (target != null) {
            if (target.submitH264(frame, keyFrame)) noteDelivered(frame.size)
            return
        }
        synchronized(gopLock) {
            if (keyFrame) {
                gop.clear()
                gopBytes = 0
            } else if (gop.isEmpty()) {
                return // 没有关键帧打头，非关键帧单独送过去也解不出画面
            }
            gop.addLast(frame to keyFrame)
            gopBytes += frame.size
            while (gop.size > MAX_GOP_FRAMES || gopBytes > MAX_GOP_BYTES) {
                val removed = gop.removeFirstOrNull() ?: break
                gopBytes -= removed.first.size
            }
        }
    }

    /** AnnexB 起始码长度（3 或 4），非起始码返回 0。 */
    private fun startCodeLen(
        data: ByteArray,
        index: Int,
    ): Int =
        when {
            index + 4 <= data.size && data[index] == 0.toByte() && data[index + 1] == 0.toByte() &&
                data[index + 2] == 0.toByte() && data[index + 3] == 1.toByte() -> 4
            index + 3 <= data.size && data[index] == 0.toByte() && data[index + 1] == 0.toByte() &&
                data[index + 2] == 1.toByte() -> 3
            else -> 0
        }

    /**
     * 从帧里摘出 SPS(7)+PPS(8) 留存。CarLife 只在编码器初始化后的第一个 IDR 里带它们，
     * 之后周期性 IDR 是裸片——必须自己记住。
     */
    private fun rememberSpsPps(data: ByteArray) {
        val headPrefix = startCodeLen(data, 0)
        if (headPrefix == 0) return
        val headType = data[headPrefix].toInt() and 0x1F
        // 快路径：普通 P 帧（类型 1）不可能带 SPS，直接跳过整帧扫描。
        val couldCarrySps = headType == 7 || headType == 9 || (spsPps == null && headType == 5)
        if (!couldCarrySps) return
        var spsAt = -1
        var ppsEnd = -1
        var index = 0
        while (index < data.size) {
            val prefix = startCodeLen(data, index)
            if (prefix == 0) {
                index++
                continue
            }
            val nalStart = index
            val bodyStart = index + prefix
            if (bodyStart >= data.size) break
            val type = data[bodyStart].toInt() and 0x1F
            var next = bodyStart + 1
            while (next < data.size && startCodeLen(data, next) == 0) next++
            if (type == 7 && spsAt < 0) spsAt = nalStart
            if (type == 8) ppsEnd = next
            index = next
        }
        if (spsAt < 0 || ppsEnd <= spsAt) return
        val snapshot = data.copyOfRange(spsAt, ppsEnd)
        if (snapshot.size > 4) spsPps = snapshot
    }

    /**
     * 关键帧若是裸 IDR（不含 SPS），把留存的 SPS/PPS 补到前面，保证 `VideoSender`
     * 能发出 VideoConfig；否则 configSent 永远为 false，每一帧都会被静默丢弃。
     */
    private fun ensureSpsPps(data: ByteArray): ByteArray {
        if (data.size < 5) return data
        val prefix = startCodeLen(data, 0)
        if (prefix == 0) return data
        val type = data[prefix].toInt() and 0x1F
        if (type == 7) return data // 自带 SPS
        if (type != 5) return data // 只有 IDR 才需要补
        val csd = spsPps ?: return data
        val merged = ByteArray(csd.size + data.size)
        System.arraycopy(csd, 0, merged, 0, csd.size)
        System.arraycopy(data, 0, merged, csd.size, data.size)
        return merged
    }

    /**
     * 送帧统计：首帧一行 + 之后每 10s 一行。
     *
     * 除了 fps 还必须报**实测码率**——帧率被引擎钉死在 20 之后，画质是唯一还能
     * 调节的维度，而画质好不好只能靠码率判断（1080p@20 低于 4Mbps 就能看到压缩块）。
     * 有这个数字才能验证 `/sdcard/BaiduCarlife/video_config.json` 到底生效没有。
     */
    private fun noteDelivered(bytes: Int) {
        framesDelivered++
        deliverWindowFrames++
        deliverWindowBytes += bytes
        val now = android.os.SystemClock.uptimeMillis()
        if (!firstDeliverLogged) {
            firstDeliverLogged = true
            deliverWindowStart = now
            deliverWindowFrames = 0
            deliverWindowBytes = 0
            logSink?.invoke("CARLIFE: ✓ 已开始向车机送帧（首帧 ${bytes}B）")
            return
        }
        if (now - deliverWindowStart < 10_000) return
        val elapsed = now - deliverWindowStart
        val fps = deliverWindowFrames * 1000f / elapsed
        val bps = deliverWindowBytes * 8_000L / elapsed
        // 同时报收帧侧的 I 帧频率：这是验证 98386 的 I_FRAME_INTERVAL 有没有被
        // 引擎采纳的硬指标——间隔 300 时 15 秒才一个 I 帧，间隔 20 时每秒一个。
        logSink?.invoke(
            "CARLIFE: 车机送帧中 %.1f fps · %.2f Mbps · 均帧 %d KB".format(
                fps,
                bps / 1_000_000f,
                deliverWindowBytes / deliverWindowFrames.coerceAtLeast(1) / 1024,
            ) + " ｜ 收到 %d 帧 / I 帧 %d 个 · %.2f Mbps".format(
                recvWindowFrames,
                recvWindowKeys,
                recvWindowBytes * 8_000L / elapsed / 1_000_000f,
            ),
        )
        deliverWindowStart = now
        deliverWindowFrames = 0
        deliverWindowBytes = 0
        recvWindowFrames = 0
        recvWindowKeys = 0
        recvWindowBytes = 0
    }

    /** 会话建立时由 SessionController 写入车机视图尺寸（触摸报文坐标系）。 */
    fun setViewSize(
        width: Int,
        height: Int,
    ) {
        viewW = width
        viewH = height
    }

    /**
     * 车机连上后把真实屏幕分辨率转给 CarLife 引擎，让它按车机画布编码。
     *
     * 与 [setViewSize] 的区别：这里写的是**编码分辨率申请**（98311 width/height），
     * 而 setViewSize 写的是**触摸坐标映射**用的车机视图尺寸。两者通常同源
     * （都来自 AirPlay SETUP 的 displayWidth/Height），但语义不同，分开命名避免混淆。
     *
     * **必须缓存**：AirPlay SETUP（此时调用本方法）通常早于 CarLife 握手完成（workerLoop
     * 里 client 才创建），此前的实现 `client?.setRequestedResolution(...)` 在 client 为 null
     * 时把分辨率直接丢了，导致 98311 永远报默认 1280×720、画面被拉伸（2026-10-25 实测）。
     * 现在缓存下来，client 创建时作为 initialWidth/Height 传入。
     */
    fun setRequestedResolution(
        width: Int,
        height: Int,
    ) {
        requestedW = width
        requestedH = height
        client?.setRequestedResolution(width, height)
    }

    /**
     * PCM 的去向判定：**只有音频通道真的在跑时才本机播放**。
     *
     * 本次连接若没勾「开启声音」，AirPlay 音频流不存在，本地播放只会从手机喇叭
     * 外放（用户不想要、也白耗电），所以直接丢弃并限频提示。
     */
    private fun routeAudio(
        sink: CarLifeAudioSink,
        pcm: ByteArray,
        size: Int,
    ) {
        val routed = io.github.shaonialife.carptocar.SessionController.audioSender?.isRunning == true
        if (routed) {
            sink.write(0, 0, pcm, size)
            return
        }
        audioDroppedBytes += size
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastAudioDropLog >= AUDIO_DROP_LOG_MS) {
            lastAudioDropLog = now
            logSink?.invoke(
                "CARLIFE: 收到 CarLife 音频 ${audioDroppedBytes / 1024}KB，但本次连接未开启声音" +
                    "（「启动连接」弹窗里勾选后即可出声）；已丢弃",
            )
            audioDroppedBytes = 0
        }
    }

    /** 收到 CarLife 媒体信息（歌名/歌手/封面）：记录并广播，供 iAP2 0x5001 转发。 */
    private fun onMediaInfo(info: CarLifeMediaInfo) {
        latestMediaInfo = info
        mediaInfoListeners.forEach { runCatching { it(info) } }
    }

    // ---- 触控回传（车机 → 手机）----

    /**
     * 处理一次车机触摸报文。返回 true = 已被劫持链路消费（调用方不要再走虚拟屏注入）。
     *
     * 坐标映射按**等比缩放 + 居中黑边（contain）**：CarLife 推的是 1280×720（16:9），
     * 车机视图通常不是同一比例，直接线性缩放会让点击整体偏移。
     */
    fun handleTouch(
        slot: Int,
        x: Int,
        y: Int,
        down: Boolean,
    ): Boolean {
        if (!isActive) return false
        if (slot != 0) return true // 只支持单指；多指其余槽直接吞掉，避免漏到注入路径
        val hu = client ?: return true
        val (vw, vh) = hu.videoSize
        if (viewW <= 0 || viewH <= 0 || vw <= 0 || vh <= 0) return true

        val scale = minOf(viewW.toFloat() / vw, viewH.toFloat() / vh)
        if (scale <= 0f) return true
        val offsetX = (viewW - vw * scale) / 2f
        val offsetY = (viewH - vh * scale) / 2f
        val tx = ((x - offsetX) / scale).toInt().coerceIn(0, vw - 1)
        val ty = ((y - offsetY) / scale).toInt().coerceIn(0, vh - 1)

        when {
            down && !touchPressed -> {
                touchPressed = true
                hu.touchDown(tx, ty)
            }

            down -> {
                // MOVE 节流：过密只会把事件堆在手机侧（与 TouchRouter 同一策略）
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastTouchAt < MOVE_THROTTLE_MS) return true
                lastTouchAt = now
                hu.touchMove(tx, ty)
            }

            !down && touchPressed -> {
                touchPressed = false
                hu.touchUp(tx, ty)
            }

            else -> return true
        }
        touchCount++
        if (touchCount <= 6 || touchCount % 100 == 0) {
            logSink?.invoke(
                "CARLIFE: 触控 ($x,$y) → ($tx,$ty) " +
                    "${if (down) "按下/移动" else "抬起"}（视图 ${viewW}x$viewH → 视频 ${vw}x$vh）",
            )
        }
        return true
    }

    /** MOVE 注入节流（毫秒），与 TouchRouter 默认值一致。 */
    private const val MOVE_THROTTLE_MS = 24L

    /** 「音频被丢弃」提示的限频（毫秒）。 */
    private const val AUDIO_DROP_LOG_MS = 10_000L

    private fun portListening(port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 800)
            true
        }
    } catch (error: Exception) {
        false
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
