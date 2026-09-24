package com.qcmian.clipper.feature.preferences.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.core.settings.ShortcutSlot
import com.qcmian.clipper.core.settings.shortcut
import com.qcmian.clipper.core.settings.withShortcut
import com.qcmian.clipper.core.ui.FixedShortcutGroup
import com.qcmian.clipper.core.ui.fixedShortcuts
import com.qcmian.clipper.core.ui.theme.hintColor
import com.qcmian.clipper.feature.history.ui.components.SearchField

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
 * 快捷键分区：筛选框 + 两段式清单。
 *
 * 这是全页最长的一处（可录制 5 行 + 固定十几行），因此做两件事：
 * - **筛选框**：按命令名与按键文本一起匹配（用户常记得「⌥P」却想不起它叫什么），
 *   十几行当场缩到两三行；筛选词只是浏览状态，不落盘；
 * - **两段式**：能改的（[ShortcutSlot]）与不能改的（[fixedShortcuts]）分成两段，
 *   后者顺带把「方向键、`⏎`、角标」这些一直没有任何说明的按键讲清楚。
 */
@Composable
internal fun ShortcutsSection(data: PreferencesUiData, actions: PreferencesActions) {
    val colors = MaterialTheme.colorScheme
    val settings = data.settings
    val recording = data.shortcutRecording
    // 上一次录制被拒绝的原因：录制没有因此退出，就地说明原因、继续等下一个组合。
    val problem = recording.problem

    // 筛选词是纯粹的浏览状态（不落盘、不影响任何功能），因此留在界面里。
    var filterText by remember { mutableStateOf("") }
    val keyword = filterText.trim()

    /** 固定项的键位也参与匹配：只比标题会漏掉「我知道按 ⌥P，但不知道它叫什么」这一路。 */
    fun matches(title: String, keys: String?): Boolean =
        keyword.isEmpty() ||
            title.contains(keyword, ignoreCase = true) ||
            keys?.contains(keyword, ignoreCase = true) == true

    // 固定项随偏好现算：其中三条（`⏎` + 修饰键）与呼出键那一条的键位都由设置决定。
    val fixed = remember(settings) { fixedShortcuts(settings) }
    // 正在录制的槽位始终保留：它可能正好落在筛选结果之外，而用户此刻正需要看到它的状态。
    val visibleSlots = ShortcutSlot.entries.filter { slot ->
        slot == recording.slot || matches(slot.title, settings.shortcut(slot)?.label)
    }
    val visibleFixed = fixed.filter { matches(it.title, it.keys) }

    SettingsGroup {
        SearchField(
            query = filterText,
            onQueryChange = { filterText = it },
            // 不主动抢焦点：筛选框只是可选的入口，抢焦点会让按键先落进输入框。
            focusRequester = remember { FocusRequester() },
        )
        Spacer(Modifier.height(6.dp))

        if (visibleSlots.isEmpty() && visibleFixed.isEmpty()) {
            Text(
                text = "没有匹配「$keyword」的快捷键。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.hintColor,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        if (visibleSlots.isNotEmpty()) {
            ShortcutPartLabel("可自定义")
            // 槽位自己的元信息（标题 / 是否系统级）在 `ShortcutSlot` 里，界面只负责渲染，
            // 因此新增一个可录制快捷键不需要在这里、以及在别处再各抄一份。
            visibleSlots.forEach { slot ->
                ShortcutRow(
                    title = slot.title,
                    spec = settings.shortcut(slot),
                    recording = recording.slot == slot,
                    onRecord = { actions.onStartShortcutRecording(slot) },
                    onClear = { actions.onSettingsChange { it.withShortcut(slot, null) } },
                )
            }
        }

        if (visibleFixed.isNotEmpty()) {
            ShortcutPartLabel("固定（不可修改）", divider = visibleSlots.isNotEmpty())
            Text(
                text = "以下是面板内置的按键：它们是交互方式本身，不是可替换的命令，因此不提供修改。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.hintColor,
                modifier = Modifier.padding(top = 4.dp),
            )
            FixedShortcutGroup.entries.forEach { group ->
                val rows = visibleFixed.filter { it.group == group }
                if (rows.isEmpty()) return@forEach
                ShortcutGroupLabel(group.title)
                rows.forEach { FixedShortcutRow(title = it.title, keys = it.keys) }
            }
        }

        Text(
            text = when {
                // 被拒绝时录制**没有**退出，就地告诉他原因、并继续等下一个组合。
                problem != null -> "${problem.message}请换一个组合，或按 Esc 取消。"
                recording.isActive -> "请按下新的快捷键…（至少要按一个修饰键）"
                // 呼出键被清除之后没有全局热键了，得说清楚还能从哪打开面板。
                settings.popupShortcut == null -> "呼出面板的快捷键已清除，可以从菜单栏图标打开面板。"
                else -> "点击快捷键即可重新录制，✕ 清除绑定；" + globalScopeHint()
            },
            style = MaterialTheme.typography.labelSmall,
            color = when {
                problem != null -> colors.error
                recording.isActive -> colors.primary
                else -> MaterialTheme.hintColor
            },
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** 快捷键区的一级小标题：分开「可自定义」与「固定」两段。[divider] 用于在两段之间落一条分隔线。 */
@Composable
private fun ShortcutPartLabel(text: String, divider: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    if (divider) {
        HorizontalDivider(
            thickness = 1.dp,
            color = colors.outline.copy(alpha = 0.2f),
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
        )
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = colors.onSurfaceVariant,
    )
}

/** 二级小标题：固定项里的分组名（列表导航 / 激活与选择 / 呼出与窗口）。 */
@Composable
private fun ShortcutGroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.hintColor,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}
