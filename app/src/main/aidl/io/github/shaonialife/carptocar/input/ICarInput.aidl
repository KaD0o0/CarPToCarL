// 触摸注入接口（历史遗留：旧实现由 Shizuku 的常驻 shell 进程承载）。
// 2026-10-24 起 Shizuku 依赖已移除，本接口仅作为类型占位，service 恒为 null。
package io.github.shaonialife.carptocar.input;

import android.view.Surface;

interface ICarInput {
    boolean tap(int displayId, int x, int y);
    boolean swipe(int displayId, int startX, int startY, int endX, int endY, int durationMs);

    // 连续拖拽原语：整段滑动共用一次 DOWN，MOVE 逐事件直注（真实时间戳），
    // 应用侧速度跟踪器才能算出正确的滚动惯性。一次只支持单指。
    boolean pointerDown(int displayId, int x, int y);
    boolean pointerMove(int displayId, int x, int y);
    boolean pointerUp(int displayId, int x, int y);

    // 受信虚拟屏（shell 权限）：第三方应用的新任务可落在其上，
    // 解决「应用内跳转的任务被重定向到手机主屏」问题（AOSP 安全策略）。
    // trusted=false：不声明 FLAG_TRUSTED——第三方任务无法被系统安置到该屏
    // （dock 屏只承载自有 DockActivity，去掉受信可杜绝其它应用任务逃逸上来）。
    int createDisplay(String name, int width, int height, int dpi, in Surface surface, boolean trusted);
    boolean releaseDisplay(int displayId);

    // 动态调整虚拟屏尺寸（创建者才可调）：屏上应用的配置变更由系统派发，
    // 应用自行 relayout——地图屏「分屏 ⇄ 全屏」切换即用此能力（任务不动，状态不丢）。
    boolean resizeDisplay(int displayId, int width, int height, int dpi);

    boolean ping();
    String diag();

    // 服务进程 PID：应用侧清理「非当前绑定进程」的陈旧 UserService 时用于排除自己
    // （旧进程持有的虚拟屏跨进程无法 release，杀进程是唯一释放途径，见 CarInputClient）。
    int pid();
}
