package com.qcmian.clipper.devtools.tools.timestamp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.core.ui.icons.ClipperIcon
import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import com.qcmian.clipper.core.ui.theme.hintColor
import com.qcmian.clipper.devtools.ui.components.DevToolButton
import kotlin.time.Instant
import kotlinx.datetime.offsetAt

/** 下拉面板宽度：放得下最长的时区 ID（`America/Argentina/Buenos_Aires`）还有余量。 */
private val MenuWidth = 300.dp

/** 列表最大高度：约十行，超出的滚动；再高就把窗口撑爆了。 */
private val ListMaxHeight = 288.dp

/**
 * 时区选择器：一个按钮（显示当前 ID）点开一个**可搜索**的时区列表。
 *
 * 为什么要搜索：时区有几百个，纯下拉翻不过来。输入几个字母（`shanghai`、`new_york`）就能定位，
 * 常用项则排在最前，多数时候一眼可见、不用打字。
 *
 * 与 [com.qcmian.clipper.devtools.ui.components.DevToolMenuButton] 同一副长相（描边格子 + 下拉
 * 箭头），但内容不止一列静态选项，所以没有复用它——它是「少量互斥选项」的控件，这里是「大量
 * 选项 + 过滤」的控件。
 */
@Composable
internal fun TimeZonePicker(
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val all = remember { TimestampZones.all() }
    val matches = remember(query, all) {
        val keyword = query.trim()
        if (keyword.isEmpty()) all else all.filter { it.contains(keyword, ignoreCase = true) }
    }
    // 每个时区相对 UTC 的偏移，以「现在」为参照（夏令时因此按当前季节算）。列表内容一变就整体
    // 重算一次；`TimestampZones.of` 有缓存，代价主要在拼这些标签本身。
    val offsets = remember(matches) {
        val now = TimestampConvert.now()
        matches.associateWith { zoneOffsetLabel(it, now) }
    }

    fun close() {
        expanded = false
        query = ""
    }

    Box(modifier) {
        DevToolButton(
            title = selectedId,
            outlined = true,
            onClick = { expanded = true },
            trailing = { contentColor ->
                Spacer(Modifier.width(3.dp))
                ClipperIcon(ClipperIconKind.CHEVRON_DOWN, size = 15.dp, tint = contentColor)
            },
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = ::close,
            modifier = Modifier.width(MenuWidth),
        ) {
            ZoneSearchField(query = query, onQueryChange = { query = it })

            HorizontalDivider(color = colors.outline.copy(alpha = 0.5f))

            if (matches.isEmpty()) {
                Text(
                    text = "没有匹配的时区",
                    fontSize = 13.sp,
                    color = MaterialTheme.hintColor,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            } else {
                // 用普通 Column 而不是 LazyColumn：`DropdownMenu` 内部按 `IntrinsicSize.Max` 测量
                // 内容宽度，而 Lazy 布局**不支持 intrinsic 测量**，放进去会直接抛
                // `Asking for intrinsic measurements of SubcomposeLayout ...`。时区总数几百个，
                // 一次性组合的代价可以接受。
                Column(
                    Modifier
                        .heightIn(max = ListMaxHeight)
                        .verticalScroll(rememberScrollState()),
                ) {
                    matches.forEach { id ->
                        ZoneRow(
                            id = id,
                            offset = offsets[id].orEmpty(),
                            selected = id == selectedId,
                            onClick = {
                                onSelect(id)
                                close()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoneSearchField(query: String, onQueryChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = 13.sp, color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { innerTextField ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = "搜索时区，如 Shanghai、New_York",
                        fontSize = 13.sp,
                        color = MaterialTheme.hintColor,
                    )
                }
                innerTextField()
            }
        },
    )
}

/** 列表里的一行：勾选列 + 时区 ID + 它当前的 UTC 偏移（编号）。 */
@Composable
private fun ZoneRow(id: String, offset: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 固定尺寸的勾选列：选中与未选中的行因此一样高，滚动时不会跳。
        Box(Modifier.width(14.dp).height(14.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                ClipperIcon(ClipperIconKind.CHECKMARK, size = 14.dp, tint = colors.primary)
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = id,
            fontSize = 13.sp,
            color = if (selected) colors.primary else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (offset.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = offset,
                fontSize = 11.sp,
                color = MaterialTheme.hintColor,
                maxLines = 1,
            )
        }
    }
}

/** `UTC+08:00` 这样的偏移标签；认不出的时区返回空串。 */
private fun zoneOffsetLabel(id: String, instant: Instant): String =
    runCatching { "UTC" + TimestampZones.of(id).offsetAt(instant) }.getOrDefault("")
