package com.qcmian.clipper.devtools.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.core.ui.code.rememberCodeColors
import com.qcmian.clipper.core.ui.theme.hintColor

/**
 * 「文件页」那张卡片的一副**共用长相**：外壳 + 缩略图 + 「文件名 / 明细」两行，空着时换成一句
 * 落点提示，另有 [preview] 放那种占满剩余高度的主内容（码图预览）。
 *
 * 抽出来是因为四个工具（Base64 / Hash / Hex / 条码）原本各写了一遍同样的排列。一份长相只有一个
 * 出处，才不会四处漂。
 *
 * 它只管**画**：标题行、文本 / 文件两页签、打开 / 清除都在 `DevToolInputField` 那边（见它的
 * `fieldActions`）；**绝对路径也归控件**——那是卡片上方那条可敲的路径行，卡片里因此不必再写一遍
 * （同一份路径写两处，一处能改一处不能改，读起来就是两回事）。
 *
 * @param name 文件名，或来源的称呼（剪贴板图片这类没有文件名的）；为 `null` 表示还没有来源，
 *   整块换成 [emptyHint]。
 * @param detail 明细：体积、图片尺寸这类。
 * @param emptyHint 还没有来源时的落点提示。
 * @param thumbnail 名称左边那张小图（图片文件的缩略图）。
 * @param preview 上方的主内容（码图预览就是它），占满剩余高度；自己带尺寸。
 */
@Composable
fun DevToolSourceCard(
    name: String?,
    detail: String?,
    emptyHint: String,
    modifier: Modifier = Modifier,
    thumbnail: ImageBitmap? = null,
    preview: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)
    val codeColors = rememberCodeColors()

    Column(
        modifier = modifier
            .clip(shape)
            // 底色取编辑框那一块：贴上去像「印在纸上」，而不是浮在面板上。
            .background(codeColors.editorBackground)
            .border(1.dp, colors.outline.copy(alpha = 0.6f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (name == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = emptyHint,
                    fontSize = 12.sp,
                    color = MaterialTheme.hintColor,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            if (preview != null) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    preview()
                }
                Spacer(Modifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(4.dp)),
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Column {
                    Text(
                        text = name,
                        fontSize = 13.sp,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (detail != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(text = detail, fontSize = 12.sp, color = MaterialTheme.hintColor)
                    }
                }
            }
        }
    }
}
