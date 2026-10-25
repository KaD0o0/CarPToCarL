package io.github.shaonialife.carptocar.carlife

import io.github.shaonialife.carptocar.audio.AudioSender

/**
 * CarLife 音频桥接（手机 → 车机方向的**直通落地点**）。
 *
 * 早期实现是「本机播放 + 播放捕获」：把 CarLife 引擎的 PCM 用 `USAGE_MEDIA` 的
 * `AudioTrack` 在本机播出来，再让 [AudioSender] 的 `AudioPlaybackCapture` 采回去
 * 推给车机。代价是依赖 MediaProjection 录屏授权 + 一次系统内混音/重采样往返。
 *
 * 现在改为**直通**：直接把引擎的裸 PCM 喂给 [AudioSender.feedPcm]，由其内部完成
 * 采样率/声道对齐 + RTP/ChaCha20 封包发送。不依赖录屏授权、少一次中转。
 *
 * 前提：AirPlay 音频通道已协商成功（[SessionController.audioSender] 正在直通模式运行），
 * 否则直接丢弃（与旧实现的「未开启声音则丢弃」语义一致，由 [CarLifeHijack.routeAudio] 判断）。
 */
internal class CarLifeAudioSink(
    private val log: (String) -> Unit,
) {
    /** CarLife 引擎宣告的源采样率（CarlifeMusicInit；<=0 表示尚未宣告，按 48k 处理）。 */
    @Volatile
    private var srcRate = 0

    /** CarLife 引擎宣告的源声道数（1 或 2）。 */
    @Volatile
    private var srcChannels = 2

    private var packets = 0L
    private var bytes = 0L
    private var lastReportAt = 0L

    /**
     * 播放状态联动（车机按暂停 → 手机音乐 App PAUSED → 这里截断）。
     *
     * 根因（2026-10-24 实测）：音乐 App 收到暂停后，CarLife 引擎（`com.baidu.carlife.honor`）
     * 的音频推流管道**不跟随停止**，继续把暂停前排队的 PCM 往车机送，表现为「重复播放」。
     * 所以 CarDeck 侧必须自己兜底：检测到会话 PAUSED 时主动丢弃引擎仍在推的残留 PCM。
     */
    @Volatile
    private var playbackPaused = false

    /**
     * 外部联动开关：音乐会话暂停时置 true（丢弃残留 PCM），恢复播放时置 false。
     */
    fun setPlaybackPaused(paused: Boolean) {
        playbackPaused = paused
        if (paused) {
            log("CARLIFE: 音乐会话暂停——丢弃引擎残留 PCM（避免重复播放）")
        }
    }

    /** 手机宣告的音频格式（CarlifeMusicInit）。重复调用无副作用。 */
    @Synchronized
    fun configure(
        sampleRate: Int,
        channels: Int,
    ) {
        srcRate = sampleRate.coerceIn(MIN_RATE, MAX_RATE)
        srcChannels = if (channels == 1) 1 else 2
        log("CARLIFE: 音频格式 ${srcRate}Hz ${if (srcChannels == 2) "立体声" else "单声道"} 16bit（直通）")
    }

    /** 收到一包裸 PCM（16bit 交织），直通给正在运行的 AirPlay 音频发送端。 */
    @Synchronized
    fun write(
        sampleRate: Int,
        channels: Int,
        pcm: ByteArray,
        size: Int,
    ) {
        if (size <= 0) return
        // 音乐会话已暂停：丢弃引擎仍在推的残留 PCM（避免重复播放）
        if (playbackPaused) return
        if (srcRate <= 0) {
            configure(
                if (sampleRate > 0) sampleRate else DEFAULT_RATE,
                if (channels > 0) channels else 2,
            )
        }
        val sender = io.github.shaonialife.carptocar.SessionController.audioSender
        if (sender == null || !sender.isRunning) return
        // 直通：交给 AudioSender 重采样 + 封包 + 发送
        sender.feedPcm(pcm, size, srcRate, srcChannels)
        packets++
        bytes += size
        reportIfDue()
    }

    @Synchronized
    fun stop() {
        srcRate = 0
        srcChannels = 2
        packets = 0
        bytes = 0
        lastReportAt = 0
    }

    private fun reportIfDue() {
        val now = android.os.SystemClock.uptimeMillis()
        if (lastReportAt == 0L) {
            lastReportAt = now
            return
        }
        if (now - lastReportAt < REPORT_INTERVAL_MS) return
        val seconds = (now - lastReportAt) / 1000.0
        val kbps = if (seconds > 0) (bytes * 8 / 1000.0 / seconds).toInt() else 0
        log(
            "CARLIFE: 音频直通 ${packets} 包 / ${bytes / 1024}KB / ${kbps}kbps",
        )
        lastReportAt = now
        packets = 0
        bytes = 0
    }

    private companion object {
        const val DEFAULT_RATE = 48_000
        const val MIN_RATE = 8_000
        const val MAX_RATE = 48_000
        const val REPORT_INTERVAL_MS = 5_000L
    }
}

/** 音频包类型 → 可读名（日志用）。 */
internal fun carLifeAudioTypeName(type: Int): String =
    when (type) {
        CarLifeProtocol.MUSIC_PKG_INIT -> "INIT"
        CarLifeProtocol.MUSIC_PKG_NORMAL -> "PCM"
        else -> "0x%X".format(type)
    }
