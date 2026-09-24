package com.qcmian.clipper.feature.preferences.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.core.domain.action.ClipAction
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.settings.ShortcutGroup
import com.qcmian.clipper.core.settings.ShortcutSlot
import com.qcmian.clipper.core.settings.shortcut
import com.qcmian.clipper.core.settings.withShortcut
import com.qcmian.clipper.core.ui.theme.hintColor

/**
 * 「哪些快捷键在系统范围内生效」那句提示。
 *
 * 从槽位表里推出来而不是手写：某个槽位换组时（比如暂停记录从全局改成面板内），
 * 这行说明不会跟着过期。
 */
private fun globalScopeHint(): String {
    val global = ShortcutSlot.entries.filter { it.global }.joinToString("、") { it.title }
    return if (global.isEmpty()) {
        "所有快捷键都只在面板里有焦点时生效。"
    } else {
        "${global}在系统范围内生效，其余快捷键只在面板里有焦点时生效。"
    }
}

/**
 * 快捷键分区：**一组一张卡片**，卡片标题即分组名。
 *
 * 分组做成独立的卡片而不是一张大卡里的小标题：一张卡里塞十四五条、靠一条比正文还淡的小标题
 * 分隔，扫读时根本分不出组。一张卡片一个分组之后，分组边界由卡片的边框与间距直接表达。
 *
 * 所有快捷键——包括面板内置的导航键、`⏎`、`⎋`、`⌘,`、`⌘1…⌘9`——都是 [ShortcutSlot]，
 * 因此这一页没有「能改的」与「不能改的」两段，只有一种行。
 */
@Composable
internal fun ShortcutsSection(data: PreferencesUiData, actions: PreferencesActions) {
    val colors = MaterialTheme.colorScheme
    val settings = data.settings
    val recording = data.shortcutRecording
    // 上一次录制被拒绝的原因：录制没有因此退出，就地说明原因、继续等下一个组合。
    val problem = recording.problem

    // 槽位自己的元信息（标题 / 分组 / 是否系统级）在 `ShortcutSlot` 里，界面只负责渲染，
    // 因此新增一个可录制快捷键不需要在这里、以及在别处再各抄一份。
    ShortcutGroup.entries.forEach { group ->
        val slots = ShortcutSlot.entries.filter { it.group == group }
        if (slots.isEmpty()) return@forEach
        SettingsGroup {
            GroupLabel(group.title)
            slots.forEach { slot ->
                val isRecording = recording.slot == slot
                val spec = settings.shortcut(slot)
                ShortcutRow(
                    title = slot.title,
                    hint = slot.hint,
                    spec = spec,
                    // 「快速粘贴」一带九：显示成 `⌘1…9`，只写占位的 `1` 会被误读成单个按键。
                    label = if (slot == ShortcutSlot.QUICK_SELECT) {
                        spec?.let { "${it.modifiers}1…9" }
                    } else {
                        null
                    },
                    recording = isRecording,
                    // 正在录制的这一行，再点一次即取消：这是取消录制的**唯一**按键方式
                    // （没有「取消录制」的快捷键，见 `ShortcutRecorder`）。
                    onRecord = {
                        if (isRecording) {
                            actions.onCancelShortcutRecording()
                        } else {
                            actions.onStartShortcutRecording(slot)
                        }
                    },
                    onClear = { actions.onSettingsChange { it.withShortcut(slot, null) } },
                )
                // 紧跟在四条激活动作之后：鼠标单击走的是同一个四选一，放在一起才看得出
                // 「点击也是激活」。快速粘贴（数字键）排在它后面，因为那是另一件事。
                if (slot == ShortcutSlot.PASTE_WITHOUT_FORMATTING) {
                    MouseClickBlock(settings, actions)
                }
            }
        }
    }

    // 录制状态与全局作用域的说明不属于任何一组，落在所有卡片之后。
    Text(
        text = when {
            // 被拒绝时录制**没有**退出，就地告诉他原因、并继续等下一个组合。
            problem != null -> "${problem.message}请换一个组合，或再点一次胶囊取消。"
            recording.isActive -> "请按下新的快捷键（至少要按一个修饰键，方向键等导航键除外）；再点一次这个胶囊可取消。"
            // 呼出键被清除之后没有全局热键了，得说清楚还能从哪打开面板。
            settings.popupShortcut == null -> "呼出面板的快捷键已清除，可以从菜单栏图标打开面板。"
            else -> "点击快捷键即可重新录制，右侧垃圾桶清除绑定；" + globalScopeHint()
        },
        style = MaterialTheme.typography.labelSmall,
        color = when {
            problem != null -> colors.error
            recording.isActive -> colors.primary
            else -> MaterialTheme.hintColor
        },
        modifier = Modifier.padding(top = 2.dp),
    )
}

/**
 * 鼠标**单击**条目时做什么。
 *
 * 鼠标不读上面那四条绑定：手势与按键是两套输入，让点击跟着某条绑定走，用户改一次按键就会连带
 * 改掉点击行为。默认「激活」——单击即复制；想「单击就粘贴」把它改成「直接粘贴」即可，而这不会
 * 影响键盘上的任何一条。
 *
 * `⌥` / `⌥⇧` 点击是固定的两条粘贴手势，不经过这一项（它们没有可选项，因此这里也就不列）。
 */
@Composable
private fun MouseClickBlock(settings: AppSettings, actions: PreferencesActions) {
    SegmentedBlock(
        title = "鼠标单击条目",
        values = ClipAction.entries,
        selected = settings.clickAction,
        label = { it.clickLabel },
        onSelect = { value -> actions.onSettingsChange { it.copy(clickAction = value) } },
    )
}

/** 四选一的显示名；与那四条绑定的标题同一套用词，免得同一件事有两个名字。 */
private val ClipAction.clickLabel: String
    get() = when (this) {
        ClipAction.COPY -> "激活"
        ClipAction.COPY_WITHOUT_FORMATTING -> "去格式激活"
        ClipAction.PASTE -> "直接粘贴"
        ClipAction.PASTE_WITHOUT_FORMATTING -> "去格式粘贴"
    }

