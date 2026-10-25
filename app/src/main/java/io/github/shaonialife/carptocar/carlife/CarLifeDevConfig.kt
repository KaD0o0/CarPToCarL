package io.github.shaonialife.carptocar.carlife

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * CarLife 引擎的画质参数，走官方调试通道写入。
 *
 * 手机端 `DeveloperVideoConfigs.isDevelopDebug()` 只判断 `/sdcard/BaiduCarlife/vehicle`
 * 这个文件存不存在；存在时 `isDevelopDebug()` 返回 true，随后
 * `getMediaFormatFormat()` 在**最后一步**调用 `readConfigFile()`，把
 * `/sdcard/BaiduCarlife/video_config.json` 里的每个键原样并进编码器 MediaFormat。
 *
 * **但实测这条通道对码率/帧率是无效的**（见 [CONFIG_JSON] 的注释）：键确实被读进去了
 * （否则 `prepend-sps-pps-to-idr-frames` 不会把编码器搞挂），但 CarLife 之后又用自己
 * 的档位把码率覆盖掉。真正能改变码流的是协议层的
 * [CarLifeProtocol.I_FRAME_INTERVAL_DEFAULT]。
 *
 * 保留它的唯一理由是 i-frame-interval，以及方便以后继续探测——不是因为它真能提画质。
 */
object CarLifeDevConfig {

    private const val DIR = "/sdcard/BaiduCarlife"
    private const val FLAG_FILE = "vehicle"
    private const val CONFIG_FILE = "video_config.json"

    /**
     * 只放实测过「确实有帧、且对观感有利」的键。
     *
     * **实测（2026-10-24）这条通道对码率完全无效**：把 `bitrate` 设成 100000（0.1Mbps）
     * 与 8000000（8Mbps），实测输出码率**完全一样**（画面动时 1.06Mbps、静止 0.17Mbps）——
     * 说明 CarLife 在 `readConfigFile()` 之后又用自己的档位覆盖了码率。
     * 所以这里**不再写 bitrate / bitrate-mode**，写了也只是一厢情愿；
     * 真正会改变码流的是协议层的 [CarLifeProtocol.I_FRAME_INTERVAL_DEFAULT]。
     *
     * **绝对不要加 `prepend-sps-pps-to-idr-frames`。** 真机逐个键实测（.tools/probe_keys.sh）：
     * 单独喂这个键时，手机照样回 `MD_VIDEO_ENC_INIT 1920x1080@20` 并「启动推流」，
     * 但**一帧都不出**——编码器配置被它弄挂了，且没有任何报错，只能靠「几秒无帧」发现。
     * 加它的本意是让每个 IDR 都带 SPS/PPS，但这件事我们已经在 `CarLifeHijack.ensureSpsPps`
     * 里用软件方式做了（留存 SPS/PPS 后前置到裸 IDR），不需要动编码器，也就不必冒这个险。
     */
    private val CONFIG_JSON = """
        {
          "i-frame-interval": 1
        }
    """.trimIndent() + "\n"

    /** 是否已经拿到「所有文件访问」权限（写 /sdcard 根下目录必须要它）。 */
    fun hasPermission(): Boolean = runCatching { Environment.isExternalStorageManager() }
        .getOrDefault(false)

    /**
     * 写好 CarLife 调试开关 + 画质配置。
     *
     * @return 给运行日志用的一句话；失败时返回原因。
     */
    fun provision(): String {
        if (!hasPermission()) {
            return "CARLIFE: 画质配置跳过 —— 未授予「所有文件访问」权限"
        }
        return runCatching {
            val dir = File(DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                return "CARLIFE: 画质配置失败 —— 无法创建 $DIR"
            }
            val flag = File(dir, FLAG_FILE)
            if (!flag.exists()) flag.writeText("dev\n")
            val cfg = File(dir, CONFIG_FILE)
            val wanted = CONFIG_JSON
            if (!cfg.exists() || cfg.readText() != wanted) cfg.writeText(wanted)
            "CARLIFE: 画质配置已写入 $DIR（i-frame 1s）；实测码率/帧率由引擎决定，此配置不保证提升"
        }.getOrElse { e ->
            "CARLIFE: 画质配置失败 —— ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** 撤销调试通道（删除开关文件），让 CarLife 回到自己的默认档位。 */
    fun revoke(): String = runCatching {
        val flag = File(DIR, FLAG_FILE)
        val removed = flag.exists() && flag.delete()
        if (removed) "CARLIFE: 已移除调试开关，CarLife 恢复默认档位（需重启 CarLife+）" else "CARLIFE: 调试开关本来就不存在"
    }.getOrElse { e -> "CARLIFE: 移除失败 —— ${e.message}" }

    /** 供 UI 提示用：权限被拒时引导到系统设置页。 */
    @Suppress("unused")
    fun requiresStorageAccess(context: Context): Boolean = !hasPermission() && context.packageName.isNotEmpty()
}
