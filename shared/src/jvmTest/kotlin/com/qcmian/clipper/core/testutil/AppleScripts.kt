package com.qcmian.clipper.core.testutil

import java.io.File
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue

/** 跑一段 AppleScript，返回它的标准输出；退出码非 0 时把输出一并报出来。 */
internal fun runAppleScript(source: String): String {
    val script = File.createTempFile("clipper-selftest", ".applescript").apply {
        writeText(source)
        deleteOnExit()
    }
    val process = ProcessBuilder("/usr/bin/osascript", script.absolutePath)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.readBytes().decodeToString().trim()
    assertEquals(0, process.waitFor(), "osascript 执行失败：$output")
    return output
}

/**
 * 试着把 Finder 切到最前，返回切换之后真正在最前的应用名。
 *
 * `keystroke` 是**全局**投递的——`tell process "Finder"` 只决定脚本在谁的上下文里跑，事件
 * 本身仍然落到当前最前的应用上。Finder 不在最前时按键会被别的应用吃掉，测试只会看到
 * 「剪贴板没变化」这种完全看不出原因的失败。
 *
 * 而 macOS 会拦下后台进程的激活请求：`activate` 与 `open -a` 都可能悄无声息地失败（表现为
 * 最前应用纹丝不动）。因此调用方必须**核对返回值**，不能假设切换成功了。
 */
private fun activateFinder(): String = runAppleScript(
    """
    tell application "Finder" to activate
    delay 1
    tell application "System Events" to return name of first process whose frontmost is true
    """.trimIndent(),
)

/**
 * Finder 切不到最前就跳过，并说清原因。
 *
 * 这是环境前提而不是代码问题（和辅助功能权限一样），所以用 assumption 跳过而不是断言失败
 * ——测试没能跑起来不代表代码有问题。跳过理由会带着当前最前的应用名一起报出来。
 */
private fun assumeFinderIsFrontmost(): String {
    val frontmost = activateFinder()
    assumeTrue(
        "Finder 没能被激活到最前（当前是「$frontmost」），键盘事件会被它吃掉，本用例跳过。" +
            "手动把 Finder 切到最前再跑，或检查终端 / IDE 的辅助功能权限。",
        frontmost == "Finder",
    )
    return frontmost
}

/**
 * 在 Finder 里选中 [dir] 之下名为 [names] 的文件并按下 `⌘C`。
 *
 * 走的是 Finder 自己的复制路径，因此需要进程具备「辅助功能」权限；没有授权时 `⌘C` 不会送达
 * Finder，剪贴板保持不变——调用方会在断言处看到，而不是看到一个假的通过。
 */
internal fun copyFilesInFinder(dir: File, names: List<String>) {
    assumeFinderIsFrontmost()

    val wanted = names.joinToString(", ") { "\"$it\"" }
    runAppleScript(
        """
        tell application "Finder"
            set theFolder to (POSIX file "${dir.absolutePath}") as alias
            open theFolder
            delay 1.5
            select (every file of theFolder whose name is in {$wanted})
            delay 1
        end tell
        tell application "System Events" to tell process "Finder" to keystroke "c" using command down
        delay 1.5
        """.trimIndent(),
    )
}

/**
 * 让 Finder 打开 [dir] 并按下 `⌘V`。用来验证「我们写进剪贴板的东西，系统真的能粘出来」——
 * 同样需要「辅助功能」权限与 Finder 在最前。
 */
internal fun pasteInFinder(dir: File) {
    assumeFinderIsFrontmost()

    runAppleScript(
        """
        tell application "Finder"
            set theFolder to (POSIX file "${dir.absolutePath}") as alias
            open theFolder
            delay 1.5
        end tell
        tell application "System Events" to tell process "Finder" to keystroke "v" using command down
        delay 2.5
        """.trimIndent(),
    )
}
