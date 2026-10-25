package io.github.shaonialife.carptocar.capture

import android.content.Context
import android.view.Surface
import io.github.shaonialife.carptocar.launcher.VirtualLauncher
import io.github.shaonialife.carptocar.input.CarInputClient

/**
 * 隐形屏模式：以普通 API 创建与车机同分辨率的 PUBLIC 虚拟屏（surface 即编码输入），
 * 注：Shizuku 依赖已移除，本实现仅在旧的「隐形屏」形态下使用，当前会话路径不再调用。
 *
 * 启动目标优先「高德地图车机版」（天生横屏；其引导页会丢 --display，需显式启动
 * MainMapActivity 并强制 fullscreen），未安装回退手机版高德。
 * （移植自参考项目 android-auto 的 VirtualDisplaySource）
 */
class HiddenDisplaySource(
    private val context: Context,
    private val log: (String) -> Unit = {},
) : ScreenSource {
    override val name: String = "隐形屏"

    /** 兼容字段：双屏架构下指向应用屏 displayId。 */
    var displayId: Int = -1
        private set

    override fun attach(
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): Boolean {
        detach()
        // 双屏启动器：GL 合成器（dock 屏 + 应用屏）→ 编码 Surface
        val ok =
            VirtualLauncher.attach(
                context,
                surface,
                width,
                height,
                densityDpi,
                log,
            )
        displayId =
            if (ok) VirtualLauncher.appDisplayId else -1
        return ok
    }

    override fun detach() {
        VirtualLauncher
            .detach()
        displayId = -1
    }

    override fun release() = detach()
}
