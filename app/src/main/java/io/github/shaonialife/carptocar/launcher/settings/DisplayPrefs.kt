package io.github.shaonialife.carptocar.launcher.settings

import android.content.Context

/**
 * 「显示」面板偏好：视频流/虚拟屏的分辨率缩放与目标帧率。
 *
 * - 分辨率缩放（30%–160%）：按车机通告分辨率的百分比生成流画布与虚拟屏，
 *   密度同步缩放（逻辑布局不变，只改像素量）。**仅下次连接生效**——
 *   编码器与全部虚拟屏按会话建立时的尺寸创建，运行中改无意义。
 * - 帧率：**固定 60fps**（2026-10-25 起不再提供 30/60 选择——车联侧把帧率
 *   拉到 60 之后，App 侧再压回 30 只会让滑动发涩，没有保留价值）。
 *
 * 「实际生效值」由 SessionController 在会话建立时记录（activeScalePercent /
 * activeTargetFps）；英雄卡只显示运行时分辨率与实测帧率。
 */
object DisplayPrefs {
    const val SCALE_MIN = 30
    const val SCALE_MAX = 160
    const val SCALE_STEP = 5
    const val SCALE_DEFAULT = 100

    /** 目标帧率（固定值，无设置项）。 */
    const val FPS = 60

    /** 触摸 move 注入节流默认值（ms）：0=默认关闭，交互滞后时在「显示」面板调高试验。 */
    const val MOVE_THROTTLE_DEFAULT = 0
    const val MOVE_THROTTLE_MAX = 48

    private const val PREFS_NAME = "settings"
    private const val KEY_SCALE = "display_scale_percent"
    private const val KEY_MOVE_THROTTLE = "move_throttle_ms"

    /** 流分辨率缩放百分比（30–160，步进 5；默认 100）。 */
    fun scalePercent(context: Context): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SCALE, SCALE_DEFAULT)
            .coerceIn(SCALE_MIN, SCALE_MAX)

    fun setScalePercent(
        context: Context,
        percent: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SCALE, percent.coerceIn(SCALE_MIN, SCALE_MAX))
            .apply()
    }

    /** 触摸 move 注入节流（ms；0=关闭）。 */
    fun moveThrottleMs(context: Context): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_MOVE_THROTTLE, MOVE_THROTTLE_DEFAULT)
            .coerceIn(0, MOVE_THROTTLE_MAX)

    fun setMoveThrottleMs(
        context: Context,
        ms: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MOVE_THROTTLE, ms.coerceIn(0, MOVE_THROTTLE_MAX))
            .apply()
    }
}
