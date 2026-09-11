package com.qcmian.clipper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.ui.Popup
import com.qcmian.clipper.ui.icons.ClipperIcon
import com.qcmian.clipper.ui.icons.ClipperIconKind

/**
 * Port of Maccy's `SearchFieldView`: a 23pt tall rounded box filled with the secondary
 * colour at 10% opacity, a magnifier and an inline clear button.
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    /** `true` while an input method has marked text, i.e. a candidate window is open. */
    onCompositionChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val foreground = colors.onSurface

    var value by remember { mutableStateOf(TextFieldValue(query)) }

    // Keep the field in sync when the query is changed from the outside (⌃H, ⌃W, clearing).
    LaunchedEffect(query) {
        if (value.text != query) {
            value = TextFieldValue(query, TextRange(query.length))
        }
    }

    // Port of `KeyHandlingView`'s `hasMarkedText()` check: while an input method has marked
    // text, every key press belongs to the candidate window and must not be treated as a
    // shortcut by the panel.
    LaunchedEffect(value.composition) {
        onCompositionChange(value.composition != null)
    }

    Box(
        modifier = modifier
            .height(23.dp)
            .clip(RoundedCornerShape(Popup.cornerRadius))
            .background(colors.onSurface.copy(alpha = 0.1f)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ClipperIcon(
                ClipperIconKind.SEARCH,
                size = 11.dp,
                tint = foreground.copy(alpha = 0.8f),
                modifier = Modifier.padding(start = 5.dp),
            )
            Spacer(Modifier.width(5.dp))

            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "搜索…",
                        fontSize = 13.sp,
                        color = foreground.copy(alpha = 0.5f),
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        onQueryChange(it.text)
                    },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = foreground),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
            }

            if (query.isNotEmpty()) {
                Spacer(Modifier.width(5.dp))
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .clickable { onQueryChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    ClipperIcon(
                        ClipperIconKind.CLEAR,
                        size = 11.dp,
                        tint = foreground.copy(alpha = 0.9f),
                    )
                }
            }
            Spacer(Modifier.width(5.dp))
        }
    }
}
