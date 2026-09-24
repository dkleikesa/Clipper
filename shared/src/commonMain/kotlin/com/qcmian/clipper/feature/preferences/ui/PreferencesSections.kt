package com.qcmian.clipper.feature.preferences.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.settings.HighlightMatch
import com.qcmian.clipper.core.settings.PinPosition
import com.qcmian.clipper.core.settings.PopupPosition
import com.qcmian.clipper.core.settings.ThemeMode
import com.qcmian.clipper.core.ui.theme.hintColor
import com.qcmian.clipper.feature.history.ui.components.HistoryFilterBar
import kotlin.math.roundToInt

/**
 * [PreferencesSection] 各分区的内容，快捷键分区除外（它在 [ShortcutsSection] 自己的文件里，
 * 是全页最长的一处）。
 *
 * 每个分区一个函数，只读 [PreferencesUiData]、把修改发回 [PreferencesActions]；分区之间不共享
 * 任何界面状态。
 */

/** 原「数据」分区的开关表；该分区已整体并入「存储与数据」。 */
private val DataSwitches = listOf(
    BooleanSetting(
        "退出时清空历史",
        { it.clearOnQuit },
        { value -> copy(clearOnQuit = value) },
        description = "只清除未置顶的项目。",
    ),
)

@Composable
internal fun StorageSection(data: PreferencesUiData, actions: PreferencesActions) {
    val colors = MaterialTheme.colorScheme
    val settings = data.settings
    SettingsGroup {
        GroupLabel("容量")
        HistoryLimitField(
            maxCount = settings.historyMaxCount,
            usageCount = data.historyCount,
            databaseBytes = data.storageBytes,
            onCountChange = { count ->
                actions.onSettingsChange { it.copy(historyMaxCount = count) }
            },
        )
    }
    // 原「数据」分区的开关与清除入口：改的都是存储里的内容，因此与上面同属一页。
    SettingsGroup {
        GroupLabel("清除")
        SwitchSettings(settings, DataSwitches, actions.onSettingsChange)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            // 清除是破坏性操作，按钮文字用 error 色与普通操作区分。
            TextButton(onClick = actions.onClearUnpinned) {
                Text("清除未置顶", color = colors.error)
            }
            TextButton(onClick = actions.onClearAll) {
                Text("全部清除", color = colors.error)
            }
        }
    }
}

/**
 * 历史上限输入框：用户按条数填写目标，超过后由仓库按当前排序丢弃最旧的未置顶记录。
 *
 * 条数**不设上限**：只校验「正整数」，没有最大值。`toIntOrNull()` 顺带挡掉超出 `Int` 范围的
 * 输入（返回 `null` 即丢弃），因此不需要额外的溢出保护。草稿以文本形式保存，这样用户清空
 * 重输时的中间状态（空串、半截数字）不会被弹回；设置被外部修改时（如重置默认值）草稿跟随刷新。
 *
 * 输入框下方始终显示当前条数与数据库文件大小：前者是上限的直接对象，后者是用户真正关心的磁盘
 * 占用（含置顶项与索引，删除后由空闲页回收 / 压紧负责让它跟着降）。
 */
@Composable
private fun HistoryLimitField(
    maxCount: Int,
    usageCount: Int,
    databaseBytes: Long?,
    onCountChange: (Int) -> Unit,
) {
    var draft by remember { mutableStateOf(maxCount.toString()) }
    var committed by remember { mutableStateOf(maxCount) }

    LaunchedEffect(maxCount) {
        if (maxCount != committed) {
            draft = maxCount.toString()
            committed = maxCount
        }
    }

    val databaseText = databaseBytes?.let { " · 数据库 ${formatSize(it)}" }.orEmpty()
    OutlinedTextField(
        value = draft,
        onValueChange = { text ->
            if (text.any { !it.isDigit() }) return@OutlinedTextField
            draft = text
            val count = text.toIntOrNull() ?: return@OutlinedTextField
            if (count < 1 || count == committed) return@OutlinedTextField
            committed = count
            onCountChange(count)
        },
        label = { Text("历史上限（条）") },
        supportingText = {
            Text(
                "当前 $usageCount 条$databaseText；超出后按排序丢弃最旧的未置顶记录，置顶项不占额度。",
                color = MaterialTheme.hintColor,
            )
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

/**
 * 把字节数格式化为带一位小数的可读文本：`512 B` / `912.3 KB` / `4.1 MB` / `1.2 GB`。
 *
 * 分级是必要的：库刚建好时只有几十 KB，只按 MB 显示会变成 `0 MB`。整数毫不出小数部分时省掉
 * `.0`（写 `1 MB` 而不是 `1.0 MB`）。
 */
private fun formatSize(bytes: Long): String = when {
    bytes < KILOBYTE -> "$bytes B"
    bytes < MEGABYTE -> scaledBy(bytes, KILOBYTE, "KB")
    bytes < GIGABYTE -> scaledBy(bytes, MEGABYTE, "MB")
    else -> scaledBy(bytes, GIGABYTE, "GB")
}

/** [bytes] 按 [unit] 换算成一位小数。 */
private fun scaledBy(bytes: Long, unit: Long, suffix: String): String {
    val tenths = bytes * 10 / unit
    return if (tenths % 10L == 0L) "${tenths / 10} $suffix" else "${tenths / 10}.${tenths % 10} $suffix"
}

private const val KILOBYTE = 1024L
private const val MEGABYTE = KILOBYTE * 1024L
private const val GIGABYTE = MEGABYTE * 1024L

/**
 * 行为分区里的开关表。
 *
 * 「开机时启动」由宿主能力决定去留，因此表要现算（[BooleanSetting.visible] 为假时整行不显示）。
 */
private fun behaviorSwitches(data: PreferencesUiData) = listOf(
    BooleanSetting(
        "连续粘贴后按回车",
        { it.pressReturnAfterPaste },
        { value -> copy(pressReturnAfterPaste = value) },
        description = "多条逐条粘贴时，每条之后补一个回车，让终端 / 聊天框腾出下一处落点。",
    ),
    BooleanSetting(
        "开机时启动",
        { it.launchAtLogin },
        { value -> copy(launchAtLogin = value) },
        description = "登录后自动运行 Clipper。",
        visible = data.supportsLaunchAtLogin,
    ),
    // 原先独占「忽略」分区的开关：它改的是「接下来还记不记录」，属于记录行为。
    BooleanSetting(
        "暂停记录新的复制",
        { it.ignoreEvents },
        { value -> copy(ignoreEvents = value) },
    ),
)

@Composable
internal fun BehaviorSection(data: PreferencesUiData, actions: PreferencesActions) {
    val settings = data.settings
    val switches = remember(data.supportsLaunchAtLogin) { behaviorSwitches(data) }
    SettingsGroup {
        SwitchSettings(settings, switches, actions.onSettingsChange)
        SliderRow(
            title = "剪贴板检查间隔",
            valueLabel = "${settings.clipboardCheckIntervalMillis} 毫秒",
            value = settings.clipboardCheckIntervalMillis.toFloat(),
            range = 100f..2_000f,
            onValueChange = { value ->
                actions.onSettingsChange {
                    it.copy(clipboardCheckIntervalMillis = value.roundToInt())
                }
            },
        )
    }
}

/** 搜索设置的开关表；原「搜索」分区已并入「外观」，因此与外观开关并列。 */
private val SearchSwitches = listOf(
    BooleanSetting("显示搜索框", { it.showSearch }, { value -> copy(showSearch = value) }),
)

/** 外观分区里的开关表。 */
private val AppearanceSwitches = listOf(
    BooleanSetting(
        "显示菜单栏图标",
        { it.showInStatusBar },
        { value -> copy(showInStatusBar = value) },
        description = "关闭后 Clipper 只在快捷键下工作。",
    ),
    BooleanSetting(
        "显示十六进制色块",
        { it.showHexColorSwatch },
        { value -> copy(showHexColorSwatch = value) },
        description = "为 #0A84FF 这类颜色显示色块。",
    ),
    BooleanSetting(
        "显示来源应用图标",
        { it.showApplicationIcons },
        { value -> copy(showApplicationIcons = value) },
        description = "在列表行与预览里显示复制来源应用的图标。",
    ),
    BooleanSetting(
        "显示特殊符号",
        { it.showSpecialSymbols },
        { value -> copy(showSpecialSymbols = value) },
        description = "把换行显示为 ⏎、制表符显示为 ⇥、首尾空格显示为 ·。",
    ),
)

@Composable
internal fun AppearanceSection(data: PreferencesUiData, actions: PreferencesActions) {
    val settings = data.settings
    SettingsGroup {
        GroupLabel("窗口")
        // 拖动过窗口边缘、或拖过预览分隔条之后出现：点一下放弃自定义尺寸与预览宽度，恢复
        // 「自动贴合内容」。两者必须一起还原——预览宽度是窗口里分出去的一段，只把主列表宽度
        // 恢复成默认、留着拖出来的预览宽度，窗口会比默认状态宽（或窄）出预览让位的那一截。
        AnimatedVisibility(
            visible = settings.customWindowWidth != null ||
                settings.previewWidth != AppSettings.DEFAULT_PREVIEW_WIDTH,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            TextButton(
                onClick = {
                    actions.onSettingsChange {
                        it.copy(
                            customWindowWidth = null,
                            customWindowHeight = null,
                            previewWidth = AppSettings.DEFAULT_PREVIEW_WIDTH,
                        )
                    }
                },
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text("恢复默认尺寸")
            }
        }
        SegmentedBlock(
            title = "主题",
            values = ThemeMode.entries,
            selected = settings.themeMode,
            label = { it.label },
            onSelect = { value -> actions.onSettingsChange { it.copy(themeMode = value) } },
        )
        SegmentedBlock(
            title = "弹窗位置",
            values = PopupPosition.entries,
            selected = settings.popupPosition,
            label = { it.label },
            onSelect = { value -> actions.onSettingsChange { it.copy(popupPosition = value) } },
        )
        // `AppearanceSettingsPane.screenPicker(for:)`：只为锚定到整块屏幕的位置提供屏幕选择器，
        // 并且仅在有多块屏幕可选时才显示。
        AnimatedVisibility(
            visible = data.screenCount > 1 &&
                settings.popupPosition == PopupPosition.SCREEN_CENTER,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            SegmentedBlock(
                title = "弹窗屏幕",
                values = (0 until data.screenCount).toList(),
                selected = settings.popupScreen.coerceIn(0, data.screenCount - 1),
                label = { index -> if (index == 0) "活动屏幕" else "屏幕 ${index + 1}" },
                onSelect = { value -> actions.onSettingsChange { it.copy(popupScreen = value) } },
            )
        }
    }
    SettingsGroup {
        GroupLabel("列表")
        SegmentedBlock(
            title = "置顶位置",
            values = PinPosition.entries,
            selected = settings.pinTo,
            label = { it.label },
            onSelect = { value -> actions.onSettingsChange { it.copy(pinTo = value) } },
        )
        // 筛选栏开关：开 → 工具栏显示筛选栏（设置页里这几个按钮隐藏）；
        // 关 → 设置页「外观」里显示筛选栏的同一套按钮。
        SwitchRow(
            title = "显示筛选栏",
            checked = settings.showFilterBar,
            description = "在工具栏上显示类型、排序与升降序按钮。",
        ) { value ->
            actions.onSettingsChange { it.copy(showFilterBar = value) }
        }
        AnimatedVisibility(
            visible = !settings.showFilterBar,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            HistoryFilterBar(
                filterTypes = settings.filterTypes,
                sortBy = settings.sortBy,
                sortOrder = settings.sortOrder,
                onFilterTypesChange = { value ->
                    actions.onSettingsChange { it.copy(filterTypes = value) }
                },
                onSortByChange = { value ->
                    actions.onSettingsChange { it.copy(sortBy = value) }
                },
                onSortOrderChange = { value ->
                    actions.onSettingsChange { it.copy(sortOrder = value) }
                },
            )
        }
        SwitchSettings(settings, AppearanceSwitches, actions.onSettingsChange)
        SliderRow(
            title = "图片最大高度",
            valueLabel = "${settings.imageMaxHeight} pt",
            value = settings.imageMaxHeight.toFloat(),
            // 允许 1…200。
            range = 1f..200f,
            onValueChange = { value ->
                actions.onSettingsChange { it.copy(imageMaxHeight = value.roundToInt()) }
            },
        )
    }
    // 原「搜索」分区：显示搜索框与匹配高亮改的都是界面长什么样，因此并到「外观」。
    SettingsGroup {
        GroupLabel("搜索")
        SwitchSettings(settings, SearchSwitches, actions.onSettingsChange)
        SegmentedBlock(
            title = "匹配高亮",
            values = HighlightMatch.entries,
            selected = settings.highlightMatch,
            label = { it.label },
            onSelect = { value -> actions.onSettingsChange { it.copy(highlightMatch = value) } },
        )
    }
}

/** AI 服务分区里的开关表；平台不支持时置灰并改说原因（而不是把这一项藏掉）。 */
private fun recognitionSwitches(data: PreferencesUiData) = listOf(
    BooleanSetting(
        "识别图片中的文字",
        { it.recognizeText },
        { value -> copy(recognizeText = value) },
        description = if (data.supportsTextRecognition) {
            "使用 Vision / ML Kit 识别图片文字，并作为纯图片条目的标题。"
        } else {
            "当前平台不支持图片文字识别。"
        },
        enabled = data.supportsTextRecognition,
    ),
)

@Composable
internal fun RecognitionSection(data: PreferencesUiData, actions: PreferencesActions) {
    val settings = data.settings
    val switches = remember(data.supportsTextRecognition) { recognitionSwitches(data) }
    SettingsGroup {
        SwitchSettings(settings, switches, actions.onSettingsChange)
    }
}

/**
 * 一键把全部偏好恢复为出厂默认值。
 *
 * 单项还原各自已有入口（快捷键、窗口尺寸、忽略类型）；这里是唯一的「全部还原」入口。
 * 它刻意不新增任何旁路：[onSettingsChange][PreferencesActions.onSettingsChange] 就是其它所有
 * 修改走的同一条路径，因此标题重算、丢弃不再收集的内容类型、窗口几何 / 托盘 / 热键重注册
 * 这些副作用，全部交给既有的观察者处理。
 *
 * 重置只覆盖设置：历史与置顶项都不属于设置，不会被它影响。
 */
@Composable
internal fun ResetSection(data: PreferencesUiData, actions: PreferencesActions) {
    val isDefault = data.settings == AppSettings()
    SettingsGroup {
        TextButton(
            onClick = {
                actions.onSettingsChange { AppSettings() }
                // 全部还原会把窗口尺寸、外观、快捷键一起改回去。关掉设置窗口，用户直接看到
                // 还原后的面板，而不是一张正在被改动的窗口。
                actions.onDismiss()
            },
            enabled = !isDefault,
        ) {
            Text("恢复默认设置")
        }
        Text(
            text = if (isDefault) {
                "当前所有设置都已是默认值。"
            } else {
                "把所有设置恢复为默认值；历史与置顶项目不受影响，设置窗口会关闭。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.hintColor,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
