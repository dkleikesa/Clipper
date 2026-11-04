package com.qcmian.clipper.devtools.tools.logviewer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import com.qcmian.clipper.devtools.api.DataTypes
import com.qcmian.clipper.devtools.api.DevTool
import com.qcmian.clipper.devtools.api.DevToolGroup
import com.qcmian.clipper.devtools.api.DevToolHost
import com.qcmian.clipper.devtools.api.DevToolMetadata
import com.qcmian.clipper.devtools.api.devToolText
import com.qcmian.clipper.devtools.api.writeTextFile
import com.qcmian.logviewer.core.model.FileRef
import com.qcmian.logviewer.platform.LogViewerPlatform
import com.qcmian.logviewer.state.AppState
import com.qcmian.logviewer.ui.desktop.LogViewerDesktop
import com.qcmian.logviewer.ui.theme.LogViewerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

/**
 * 日志查看工具：把独立项目 LogViewer 的**整个工作台**搬进开发者工具面板。
 *
 * 多标签、打开文件、重载、粘贴导入、导出、跳转行号、后退/前进都由上游的
 * `LogViewerDesktop` + `AppState` 原样提供（见各自文件），这里只做三件事：
 *
 * 1. 把宿主的能力包成一个 [LogViewerPlatform]（文件对话框、剪贴板、偏好）；
 * 2. 把主面板交进来的那条剪贴板内容送成一个标签页；
 * 3. 去掉「应用外壳」——窗口标题栏与交通灯归宿主的系统标题栏（`windowTrafficLightsInset = false`）。
 */
internal object LogViewerDevTool : DevTool {
    override val metadata: DevToolMetadata = DevToolMetadata(
        id = "log",
        name = "日志查看",
        description = "按行浏览日志：多标签、打开/重载、包含与排除规则、正则、标记、查找、跳转与导出。",
        group = DevToolGroup.TEXT,
        icon = ClipperIconKind.TEXT_LINES,
    )

    override val acceptedDataTypes: Set<String> = setOf(DataTypes.LOG)

    /**
     * 照样吃任何内容。
     *
     * 日志与普通多行文本之间没有硬边界（一段命令输出、一次堆栈打印，探测器不一定认成日志），
     * 而「按行看」对所有多行文本都成立。声明 [DataTypes.LOG] 只是为了让它在真日志上被优先推荐。
     */
    override val acceptsAnyInput: Boolean = true

    @Composable
    override fun Content(input: ClipItem?, host: DevToolHost) {
        // 工作台自带主题与配色：跟着宿主的明暗走（内容区拿不到 Clipper 的 darkTheme 开关，从背景亮度反推）。
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        val platform = remember(host) { HostLogViewerPlatform(host) }
        val app = remember(platform) { AppState(platform) }

        // AppState 自带 scope（会话保存、外部打开轮询等常驻协程），面板出组合后必须显式收掉。
        DisposableEffect(app) {
            onDispose { app.scope.cancel() }
        }

        LogViewerTheme(dark = dark) {
            LaunchedEffect(input) {
                val item = input ?: return@LaunchedEffect
                val text = withContext(Dispatchers.Default) { item.devToolText() }
                if (text.isBlank()) return@LaunchedEffect
                app.openClipboardTab(text)
            }
            LogViewerDesktop(app)
        }
    }
}

/** 剪贴板内容固定落在这一页：换一条记录就换这一页的内容，不覆盖用户自己打开的文件标签。 */
private const val CLIPBOARD_TAB = "剪贴板内容"

/**
 * 把剪贴板文本放进标签页。
 *
 * - 已有「剪贴板内容」页：就地换内容（连同规则一起换，规则属于上一份日志）；
 * - 首次进来只有那个空的欢迎页：直接复用它，别在旁边多留一页空白；
 * - 用户已经在看别的标签：新开一页，不打断。
 */
private fun AppState.openClipboardTab(text: String) {
    val existing = tabs.firstOrNull { it.title == CLIPBOARD_TAB }
    if (existing != null) {
        activateTab(existing.id)
        existing.viewer.loadText(CLIPBOARD_TAB, text)
        return
    }
    val onlyChild = tabs.singleOrNull()
    if (onlyChild != null && onlyChild.viewer.document == null) {
        onlyChild.title = CLIPBOARD_TAB
        onlyChild.viewer.loadText(CLIPBOARD_TAB, text)
        activateTab(onlyChild.id)
        return
    }
    openTextTab(CLIPBOARD_TAB, text, newTab = true)
}

/* ==================== 宿主能力适配 ==================== */

/**
 * 把宿主的 [DevToolHost] 包成工作台要的平台能力。
 *
 * 上游的 `JvmPlatform` 不能直接用：它自己弹 AWT 对话框、把偏好写进 `java.util.prefs`，还会在
 * `/tmp` 里开一份诊断日志——那些都属于「独立应用」的行为，嵌进宿主后该由宿主统一负责。
 */
private class HostLogViewerPlatform(private val host: DevToolHost) : LogViewerPlatform {
    /**
     * 偏好只在本次会话内有效（会话恢复、按文件记忆的过滤器都还在，只是不落盘）。
     *
     * 落盘会把 Clipper 的设置文件变成第二份日志应用的数据目录；真需要跨会话保留时，应该走
     * 宿主自己的设置存储，而不是让插件偷偷写 `user.prefs`。
     */
    private val prefs = mutableMapOf<String, String>()

    override fun pickFiles(onResult: (List<FileRef>) -> Unit) {
        val path = host.pickFileToOpen() ?: return
        // platformData 传路径字符串：common 的默认 openReader 会走 openPathSource 顺序读
        onResult(listOf(FileRef(path.substringAfterLast('/'), path, path)))
    }

    override fun saveTextFile(defaultName: String, content: String, onDone: (Boolean) -> Unit) {
        val path = host.pickFileToSave(defaultName)
        onDone(path != null && writeTextFile(path, content))
    }

    override fun setClipboard(text: String, html: String?) {
        // 宿主只收纯文本：HTML 口味在这里丢掉，粘贴到富文本编辑器会退化成纯文本
        host.copyToClipboard(text)
    }

    /** 粘贴导入（工作台的「粘贴」按钮 / Cmd+V）读系统剪贴板，这条路宿主没有对应的读接口。 */
    override fun getClipboardText(): String? = runCatching {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard
            .getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
    }.getOrNull()

    override fun putPref(key: String, value: String) {
        prefs[key] = value
    }

    override fun getPref(key: String): String? = prefs[key]
}
