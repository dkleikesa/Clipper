package com.qcmian.clipper.feature.preferences.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.core.domain.action.ClipAction
import com.qcmian.clipper.core.domain.action.modifierFlagsOf
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
 *
 * 派生交互（`⇧` 连选、`⏎` 的修饰键映射）写在各行的灰色小字里：它们不是单独的键，而是
 * 某条绑定按住不同修饰键时的含义，改绑定不影响它们。
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
                ShortcutRow(
                    title = slot.title,
                    hint = slotHint(slot, settings),
                    spec = settings.shortcut(slot),
                    recording = recording.slot == slot,
                    onRecord = { actions.onStartShortcutRecording(slot) },
                    onClear = { actions.onSettingsChange { it.withShortcut(slot, null) } },
                )
            }
        }
    }

    // 录制状态与全局作用域的说明不属于任何一组，落在所有卡片之后。
    Text(
        text = when {
            // 被拒绝时录制**没有**退出，就地告诉他原因、并继续等下一个组合。
            problem != null -> "${problem.message}请换一个组合，或按 Esc 取消。"
            recording.isActive ->
                "请按下新的快捷键（至少要按一个修饰键，方向键等导航键除外）；" +
                    "裸按 Esc 取消录制，要把 Esc 本身录进去请配合一个修饰键。"
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
 * 某条槽位的灰色小字说明。
 *
 * 多数槽位是静态的（见 [ShortcutSlot.hint]）；激活键是唯一需要现算的——它按住 `⌘` / `⌥` /
 * `⌥⇧` 时分别对应复制 / 粘贴 / 去格式，映射随「默认粘贴 / 去格式」偏好变化，因此这里用
 * [modifierFlagsOf] 现推一份，与按键解析共用同一份规则，不会漂移。
 */
private fun slotHint(slot: ShortcutSlot, settings: AppSettings): String? =
    if (slot == ShortcutSlot.ACTIVATE) activationHint(settings) else slot.hint

/** 激活键的修饰键映射，如 `⌘⏎ 复制 / ⌥⏎ 粘贴 / ⌥⇧⏎ 去格式`。 */
private fun activationHint(settings: AppSettings): String? {
    val key = settings.activateShortcut?.character
        ?: return "按住 ⌘ / ⌥ / ⌥⇧ 决定复制、粘贴或去格式"
    val parts = listOf(
        ClipAction.COPY to "复制",
        ClipAction.PASTE to "粘贴",
        ClipAction.PASTE_WITHOUT_FORMATTING to "去格式",
    ).mapNotNull { (action, label) ->
        modifierFlagsOf(action, settings)
            .takeIf { it.isNotEmpty() }
            ?.let { "$it$key $label" }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" / ")
}
