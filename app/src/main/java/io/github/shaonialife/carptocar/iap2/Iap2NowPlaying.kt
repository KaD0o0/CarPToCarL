package io.github.shaonialife.carptocar.iap2

/**
 * iAP2「正在播放」信息（0x5001 NowPlayingUpdate）的载荷与编码。
 *
 * 字段布局来自 iAP2 NowPlaying 规范（MFi Accessory Interface Specification；
 * 亦与开源实现 iap2-rs 的 NowPlayingParser 解码字段一一对应）：
 *
 *   顶层 param 0x0000 = media item 组：
 *     0x0001 title(string) · 0x0004 duration(u32 ms) · 0x000C artist(string) ·
 *     0x001A album(string)
 *   顶层 param 0x0001 = playback 组：
 *     0x0000 status(u8: 0 停止/1 播放/2 暂停) · 0x0001 elapsed(u32 ms) ·
 *     0x0005 shuffle(u8) · 0x0006 repeat(u8) · 0x0007 appName(string)
 *
 * 数据源可以是 CarLife 内置播放器经 0x10035 推来的媒体信息，或系统媒体会话
 * （MediaSessionsState）——由各链路的 provider 决定。
 */
data class CarPlayNowPlaying(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Int,
    val status: Int,
    val elapsedMs: Int,
    val shuffleMode: Int = 0,
    val repeatMode: Int = 0,
    val appName: String?,
)

object Iap2NowPlaying {
    /** 播放状态枚举（与 iap2-rs PlaybackStatus 一致）。 */
    const val STATUS_STOPPED = 0
    const val STATUS_PLAYING = 1
    const val STATUS_PAUSED = 2

    /**
     * 构造 0x5001 NowPlayingUpdate 完整帧（带真实歌名/歌手/专辑/播放状态）。
     * 空字符串字段仍给一个空串 param，保持结构与空态常量同构。
     */
    fun encode(np: CarPlayNowPlaying): ByteArray {
        val mediaItem =
            Iap2Csm.string(0x0001, np.title.orEmpty()) +
                Iap2Csm.u32(0x0004, np.durationMs.toLong()) +
                Iap2Csm.string(0x000C, np.artist.orEmpty()) +
                Iap2Csm.string(0x001A, np.album.orEmpty())
        val playback =
            Iap2Csm.u8(0x0000, np.status) +
                Iap2Csm.u32(0x0001, np.elapsedMs.toLong()) +
                Iap2Csm.u8(0x0005, np.shuffleMode) +
                Iap2Csm.u8(0x0006, np.repeatMode) +
                Iap2Csm.string(0x0007, np.appName.orEmpty())
        val body = Iap2Csm.param(0x0000, mediaItem) + Iap2Csm.param(0x0001, playback)
        return Iap2Csm.encode(Iap2MessageIds.NOW_PLAYING_UPDATE, body)
    }
}
