package com.qcmian.clipper.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.qcmian.clipper.core.data.source.unlockClipperDatabase
import com.qcmian.clipper.core.platform.macos.MacWorkspace
import com.qcmian.clipper.core.settings.ThemeMode
import com.qcmian.clipper.core.ui.components.ClipperTitleBar
import com.qcmian.clipper.core.ui.theme.ClipperTheme
import com.qcmian.clipper.core.ui.theme.hintColor
import com.qcmian.clipper.core.ui.theme.rememberClipperDarkTheme
import com.qcmian.clipper.desktop.domain.screenCenterLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 解锁窗的尺寸。
 *
 * 宽度取主面板的内容宽度（400dp）：它是这个应用给人的第一印象，和面板同宽才像一家人。
 * 高度按内容量算出来，不富余也不裁切。
 */
private val UnlockWindowSize = DpSize(400.dp, 268.dp)

/**
 * 启动解锁窗：数据库已加密时，先要口令、再建依赖图。
 *
 * 它是这个场合**唯一在场**的窗口：没有它，`AppContainer`、面板、设置、CLI 服务都还没有，
 * 因为加密库没有口令根本打不开。口令校验通过后把口令记进内存（见 `unlockClipperDatabase`），
 * 随后 [onUnlocked] 才让宿主继续启动。
 *
 * 外形与设置窗口同一套：无边框 + 透明的窗口，内部自己画 10dp 圆角的 [Surface]、40dp 标题栏与
 * 分隔线（见 [ClipperTitleBar]）——系统标题栏的底色由 AppKit 决定，不跟主题走，也和本应用的
 * 配色对不上。内容沿用设置页的卡片语言：页面大标题 + 一张 8dp 圆角的设置卡，主操作是右下角的
 * 文字按钮（与各对话框一致）。
 *
 * 主题只能用「跟随系统」：用户的主题偏好存在**库里**，而这一刻库还锁着——先有鸡还是先有蛋。
 */
@Composable
fun ApplicationScope.DatabaseUnlockWindow(onUnlocked: () -> Unit) {
    // 位置不在这里给：多屏时要落在**鼠标所在的那块屏幕**上，而 `WindowPosition(Alignment.Center)`
    // 是 Compose 按主屏算的。定位交给下面那个 `LaunchedEffect`（见 `screenCenterLocation`）。
    val windowState = rememberWindowState(size = UnlockWindowSize)
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val fieldFocus = remember { FocusRequester() }

    fun submit() {
        if (working || password.isEmpty()) return
        working = true
        error = null
        scope.launch {
            // 校验是一次本地 JDBC 打开（含首屏页读取）：放到 IO 线程，别卡住合成。
            val unlocked = withContext(Dispatchers.IO) {
                runCatching { unlockClipperDatabase(password) }.getOrDefault(false)
            }
            working = false
            if (unlocked) {
                onUnlocked()
            } else {
                error = "口令不正确，请重试。"
                password = ""
            }
        }
    }

    Window(
        // 解锁前没有任何业务可退：关掉这个窗就是退出应用。
        onCloseRequest = { exitApplication() },
        title = "解锁 Clipper",
        icon = rememberVectorPainter(ClipperAppIcon),
        state = windowState,
        resizable = false,
        undecorated = true,
        transparent = true,
    ) {
        val dragTitleBar = rememberTitleBarDragModifier(window)

        LaunchedEffect(Unit) {
            // 定位：居到**鼠标所在的那块屏幕**中央（索引 0 = 活动屏幕，见 `screenBounds`）。
            // 这一步排在首帧绘制之前，因此看不到「先出现在主屏、再跳过来」。
            val location = screenCenterLocation(UnlockWindowSize, screenIndex = 0)
            window.setLocation(location.x, location.y)
            // 与设置窗口同样的理由：本应用是菜单栏应用（`LSUIElement`），不激活则窗口成为不了
            // key window，输入框收不到键盘。
            launch(Dispatchers.IO) { runCatching { MacWorkspace.activateSelf() } }
            window.toFront()
            // 窗口刚显示时 Compose 还没拿到焦点节点，跨帧重试几次。
            repeat(FOCUS_ATTEMPTS) {
                if (focusKeyboardTarget(window)) {
                    runCatching { fieldFocus.requestFocus() }
                    return@LaunchedEffect
                }
                withFrameNanos { }
            }
        }

        // 口令错一次之后焦点可能停在按钮上（用户刚点过它），把光标还给输入框。
        LaunchedEffect(error) {
            if (error != null) runCatching { fieldFocus.requestFocus() }
        }

        ClipperTheme(darkTheme = rememberClipperDarkTheme(ThemeMode.SYSTEM, null)) {
            val colors = MaterialTheme.colorScheme
            val message = error

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = colors.background,
                border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        // 无边框窗口没有系统菜单，⌘W 得自己接上（设置窗口同款约定）。
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown &&
                                event.isMetaPressed &&
                                event.key == Key.W
                            ) {
                                exitApplication()
                                true
                            } else {
                                false
                            }
                        },
                ) {
                    ClipperTitleBar(
                        title = "解锁 Clipper",
                        closeTooltip = "退出 Clipper",
                        onClose = { exitApplication() },
                        dragModifier = dragTitleBar,
                    )
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = colors.outline.copy(alpha = 0.3f),
                    )

                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // 与设置页的页面大标题同档（titleLarge + SemiBold）。
                        Text(
                            text = "数据库已加密",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onBackground,
                        )
                        // 卡片沿用设置页分组卡片的视觉：8dp 圆角 + surface 底 + 1dp 描边 + 16/12 内边距。
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = colors.surface,
                            border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.28f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = {
                                        password = it
                                        error = null
                                    },
                                    label = { Text("口令") },
                                    singleLine = true,
                                    enabled = !working,
                                    isError = message != null,
                                    visualTransformation = PasswordVisualTransformation(),
                                    // 与设置页的加密密码框同一处理：没输入时 label 停在框里当提示，
                                    // 用应用统一的提示灰，而不是更深的 `onSurfaceVariant`。
                                    colors = OutlinedTextFieldDefaults.colors(
                                        unfocusedLabelColor = MaterialTheme.hintColor,
                                    ),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { submit() }),
                                    // 说明与错误共用这一行：错误时换 error 色，其余时候是 hintColor
                                    // 的说明（与设置页「历史上限」的 supportingText 同款）。
                                    supportingText = {
                                        Text(
                                            text = message ?: "口令不会保存，每次启动都需要输入。",
                                            color = if (message != null) colors.error else MaterialTheme.hintColor,
                                        )
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(fieldFocus),
                                )
                                // 主操作整行、带底色：它是本窗口唯一的目标动作，文字按钮的份量在这里
                                // 不够（也与登录类窗口的惯例一致）。圆角取 8dp，与上面的卡片、
                                // 输入框同一档，不用 M3 默认的胶囊形。
                                Button(
                                    onClick = { submit() },
                                    enabled = !working && password.isNotEmpty(),
                                    shape = RoundedCornerShape(8.dp),
                                    // 高度压到 35dp 之后，默认的上下各 8dp 内边距会把 14sp 的标签裁掉
                                    // （按钮按形状裁剪内容），因此纵向内边距清零、只留横向。
                                    contentPadding = PaddingValues(horizontal = 24.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        // 与上方输入框的说明文字再拉开一点：说明自带的下边距偏紧。
                                        .padding(top = 10.dp)
                                        .height(35.dp),
                                ) {
                                    Text(
                                        text = if (working) "解锁中…" else "解锁",
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 与 `ClipperWindow` / `ClipperSettingsWindow` 同量级的聚焦重试次数。 */
private const val FOCUS_ATTEMPTS = 8
