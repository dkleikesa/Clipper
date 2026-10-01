package com.qcmian.clipper.testing

import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.ClipPayload
import com.qcmian.clipper.core.domain.model.ClipboardContent
import com.qcmian.clipper.core.domain.model.FILE_URL_CONTENT_TYPE
import com.qcmian.clipper.core.domain.model.PNG_CONTENT_TYPE
import com.qcmian.clipper.core.domain.model.toMeta
import com.qcmian.clipper.core.domain.model.toPayload
import com.qcmian.clipper.core.domain.repository.ClipboardRepository

/**
 * 造历史条目的共享助手：A 层（`:shared`）与 B 层（`:cli`）都靠它把数据喂进**真存储**。
 *
 * 全部走仓库的公开写入路径（[ClipboardRepository.insert]），因此落库口径与真实捕获完全一致；
 * 断言读到的就是线上那条 SQL 与映射的结果，而不是镜像。
 *
 * 集中在这里而不是各自实现一份：`:shared` 与 `:cli` 的测试源集彼此看不见，分散写就会漂移出
 * 「字节一样但字段不同」的两套造数，最后一边绿一边红还看不出差别。
 */

/** 一串带 PNG 魔数的字节：`imageFormatOf` 只需魔数就能认出格式，无需真实图片。 */
val TEST_PNG_BYTES: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02)

/**
 * 落一条完整条目；[payload] 默认由条目派生，只有需要「载荷里带识别原文」时才显式传。
 *
 * 不能一律用 `item.toPayload()`：它只带回 [ClipItem.contents] 与 [ClipItem.text]，
 * **丢掉识别原文那一列**，而 `--ocr` 与预览正是靠它。
 */
suspend fun ClipboardRepository.seed(item: ClipItem, payload: ClipPayload = item.toPayload()) {
    insert(item.toMeta(), payload)
}

/** 一条普通文本条目；`lastCopiedAt` 显式给出，便于断言排序。 */
suspend fun ClipboardRepository.seedText(id: String, text: String, lastCopiedAt: Long, copies: Int = 1) = seed(
    ClipItem(
        id = id,
        text = text,
        contents = listOf(ClipboardContent("public.utf8-plain-text", text.encodeToByteArray())),
        firstCopiedAt = lastCopiedAt,
        lastCopiedAt = lastCopiedAt,
        numberOfCopies = copies,
    ),
)

/**
 * 一条「标题里没有、正文里才有」的条目。
 *
 * 走的正是线上口径：标题是正文按 `ClipItem.MAX_TITLE_LENGTH` 截断后落库的，正文另存一份。
 */
suspend fun ClipboardRepository.seedLongText(id: String, text: String, lastCopiedAt: Long) = seed(
    ClipItem(
        id = id,
        text = text,
        contents = listOf(ClipboardContent("public.utf8-plain-text", text.encodeToByteArray())),
        firstCopiedAt = lastCopiedAt,
        lastCopiedAt = lastCopiedAt,
        title = text,
    ),
)

/**
 * 一条图片条目；给了 [recognizedText] 即模拟「识别已完成」。
 *
 * 识别原文必须落在**载荷**里（而不是只进标题）：`--ocr`、预览与「复制图片文字」都读它。
 */
suspend fun ClipboardRepository.seedImage(id: String, lastCopiedAt: Long, recognizedText: String? = null) {
    val contents = listOf(ClipboardContent(PNG_CONTENT_TYPE, TEST_PNG_BYTES.copyOf()))
    val item = ClipItem(
        id = id,
        contents = contents,
        firstCopiedAt = lastCopiedAt,
        lastCopiedAt = lastCopiedAt,
        title = "图片条目",
        hasRecognizedText = recognizedText != null,
        recognizedText = recognizedText,
    )
    seed(item, ClipPayload(contents = contents, text = null, recognizedText = recognizedText))
}

/** 一条文件条目；文件路径留在元数据里，内容走文件 URL 表示。 */
suspend fun ClipboardRepository.seedFile(id: String, lastCopiedAt: Long) = seed(
    ClipItem(
        id = id,
        files = listOf("/tmp/不存在的文件.txt"),
        contents = listOf(ClipboardContent(FILE_URL_CONTENT_TYPE, "file:///tmp/不存在.txt".encodeToByteArray())),
        firstCopiedAt = lastCopiedAt,
        lastCopiedAt = lastCopiedAt,
    ),
)

/** 一条富文本条目：只写 HTML、不写纯文本，标题要从附加表示里提取。 */
suspend fun ClipboardRepository.seedRichText(id: String, lastCopiedAt: Long, html: String = "<p>你好世界</p>") = seed(
    ClipItem(
        id = id,
        contents = listOf(ClipboardContent("public.html", html.encodeToByteArray())),
        firstCopiedAt = lastCopiedAt,
        lastCopiedAt = lastCopiedAt,
    ),
)
