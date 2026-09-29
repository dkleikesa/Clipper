package com.qcmian.clipper.feature.history.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.core.domain.model.ClipImage
import com.qcmian.clipper.core.domain.model.ClipMeta
import com.qcmian.clipper.core.settings.ClipFilterType
import com.qcmian.clipper.core.settings.HighlightMatch
import com.qcmian.clipper.core.ui.ModifierFlags
import com.qcmian.clipper.core.ui.components.ImageCache
import kotlin.test.Test

/**
 * 图片条目在列表里的退化形态。
 *
 * 纯图片条目的标题来自识别结果，没跑或没识别出文字时它就是空的——所以「图片解不出来」在列表里
 * 表现为**整行一片空白**，与「还在加载」完全一样。这里钉住两者的区分。
 */
@OptIn(ExperimentalTestApi::class)
class HistoryRowTest {

    @Test
    fun `an image that cannot be decoded says so instead of going blank`() = runComposeUiTest {
        val image = undecodableImage()

        setContent {
            MaterialTheme {
                Row(meta = clipMeta(title = ""), image = image)
            }
        }

        onNodeWithText("图片无法显示。").assertIsDisplayed()
    }

    @Test
    fun `an undecodable image row keeps the title it does have`() = runComposeUiTest {
        // 有标题时标题本身就是有用的信息（识别结果），不该被那句说明顶掉。
        val image = undecodableImage()

        setContent {
            MaterialTheme {
                Row(meta = clipMeta(title = "识别出来的文字"), image = image)
            }
        }

        onNodeWithText("识别出来的文字").assertIsDisplayed()
    }

    /** 字节不是任何图片格式：解码会失败，并把负结果记进缓存。 */
    private fun undecodableImage(): ClipImage {
        val image = ClipImage(byteArrayOf(1, 2, 3, 4, 5))
        ImageCache.decode(
            key = ImageCache.keyOf(image, thumbnail = true),
            maxEdge = 512,
            data = image.toByteArray(),
        )
        return image
    }

    private fun clipMeta(title: String) = ClipMeta(
        id = "history-row-test",
        title = title,
        kind = ClipFilterType.IMAGE,
        files = emptyList(),
        application = null,
        firstCopiedAt = 0L,
        lastCopiedAt = 0L,
        numberOfCopies = 1,
        pin = null,
        payloadBytes = 0L,
        contentKey = "history-row-test",
        hasRecognizedText = false,
        hasImage = true,
    )
}

/** [HistoryRow] 的参数太多，测试里只关心图片那一路，其余取默认。 */
@androidx.compose.runtime.Composable
private fun Row(meta: ClipMeta, image: ClipImage?) {
    HistoryRow(
        meta = meta,
        image = image,
        ranges = emptyList(),
        shortcuts = emptyList(),
        flags = ModifierFlags(),
        isSelected = false,
        isCursor = false,
        highlight = HighlightMatch.BOLD,
        showColorSwatch = false,
        showSpecialSymbols = true,
        maxImageHeight = 40.dp,
        appIconBase64 = null,
        onClick = {},
        onHover = {},
    )
}
