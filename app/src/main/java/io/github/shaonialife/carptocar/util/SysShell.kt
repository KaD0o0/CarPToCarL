package io.github.shaonialife.carptocar.util

import android.os.Looper
import java.util.concurrent.TimeUnit

/**
 * 以**本应用自身 uid** 执行一条 shell（`sh -c`）。
 *
 * 历史背景：旧实现走 Shizuku 的 `IShizukuService.newProcess`，等同于 adb shell，
 * 能力很强但要求用户额外装 Shizuku 并授权——一旦未就绪，整个连接流程会被门禁拦住。
 * 2026-10-24 起 Shizuku 依赖整体移除：本类退化为普通 `ProcessBuilder`，
 * **没有 shell 权限时命令必然失败**（exitCode != 0），所有调用点都按「失败即降级」
 * 处理，不会抛异常、不影响 CarPlay / CarLife 主链路。
 *
 * 仍然保留 [mainThreadViolations] 与 [violationSink]：shell 是阻塞调用，
 * 主线程串几条就会 ANR，这类问题只在真机暴露，必须进文件日志。
 */
object SysShell {
    class Result(
        val exitCode: Int,
        val output: String,
    ) {
        val ok: Boolean get() = exitCode == 0
    }

    /** 主线程调用 `exec` 的累计次数（诊断用）。 */
    @Volatile
    var mainThreadViolations: Int = 0
        private set

    /** 主线程告警出口（由 SessionController 注入到文件日志）。 */
    @Volatile
    var violationSink: ((String) -> Unit)? = null

    fun exec(
        cmd: String,
        timeoutMs: Long = 6_000L,
    ): Result {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            mainThreadViolations++
            violationSink?.invoke(
                "SHELL: ⚠ 主线程调用 shell（第 $mainThreadViolations 次）cmd=${cmd.take(60)}" +
                    "（$timeoutMs ms 上限）——应改用 Bg.run / 后台线程",
            )
        }
        var process: Process? = null
        return try {
            val started = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
            process = started
            val output = StringBuilder()
            val reader =
                Thread({
                    runCatching {
                        started.inputStream.bufferedReader().forEachLine { output.appendLine(it) }
                    }
                }, "sys-shell-read").apply {
                    isDaemon = true
                    start()
                }
            if (!started.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                started.destroy()
                return Result(-1, "命令超时（${timeoutMs}ms）")
            }
            reader.join(200)
            Result(started.exitValue(), output.toString().trim())
        } catch (error: Exception) {
            runCatching { process?.destroy() }
            Result(-1, "命令执行失败：${error.message ?: error.javaClass.simpleName}")
        }
    }
}
