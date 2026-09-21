package com.qcmian.clipper.desktop

import com.qcmian.clipper.core.data.source.createClipStorageDataSource
import com.qcmian.clipper.core.domain.model.ClipImage
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.ClipMeta
import com.qcmian.clipper.core.domain.model.ClipPayload
import com.qcmian.clipper.core.domain.model.ClipboardContent
import com.qcmian.clipper.core.domain.model.SourceApplication
import com.qcmian.clipper.core.domain.model.contentKeyOf
import com.qcmian.clipper.core.domain.search.ClipSearch
import com.qcmian.clipper.core.settings.ClipFilterType
import com.qcmian.clipper.core.settings.SearchMode
import com.qcmian.clipper.core.settings.SortBy
import com.qcmian.clipper.core.settings.SortOrder
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlinx.coroutines.runBlocking

/**
 * 开发用：往真实数据库里灌一批测试数据。
 *
 * 走的是应用自己的 `ClipStorageDataSource`，因此落盘的 schema、JSON 列格式与索引都和生产
 * 路径完全一致——用 `sqlite3` 手工拼 INSERT 会绕开 Room 的 identity hash 校验，下次启动
 * 会被 `fallbackToDestructiveMigration` 当成旧库清掉。
 *
 * 运行：`./gradlew :desktopApp:seedData`
 *
 * **会先清空现有历史。**
 */
private const val TOTAL = 20_000
private const val IMAGE_COUNT = 50

private val APPS = listOf(
    SourceApplication("Safari", "com.apple.Safari"),
    SourceApplication("Xcode", "com.apple.dt.Xcode"),
    SourceApplication("Terminal", "com.apple.Terminal"),
    SourceApplication("Visual Studio Code", "com.microsoft.VSCode"),
    SourceApplication("Notes", "com.apple.Notes"),
)

/**
 * JVM 入口点。
 *
 * 必须写成**块体、无参、返回 `Unit`** 的形式：`fun main() = runBlocking { ... }` 这种
 * 表达式体只会生成 `main()`，Kotlin 不会为它合成 `main(String[])`，JVM 启动时就会报
 * 「找不到 main 方法」。
 */
fun main() {
    seed()
}

private fun seed() = runBlocking {
    val storage = createClipStorageDataSource()
    try {
        storage.initialise()

        println("现有未置顶条目：${storage.countUnpinned()}")
        println("清空历史……")
        storage.deleteAll()

        println("开始写入 $TOTAL 条（其中 $IMAGE_COUNT 张图片）……")
        val random = Random(20260922)
        val now = System.currentTimeMillis()
        val started = System.currentTimeMillis()

        // 记下第一条带附加表示的条目，最后回读它——验证 CBOR + BLOB 的往返，
        // 免得出现「写得进、读不出」这种只靠 schema 看不出来的问题。
        var probeId: String? = null

        for (index in 0 until TOTAL) {
            val publishedAt = now - (TOTAL - index) * 1_000L
            val (meta, payload) = buildItem(random, index, publishedAt)
            storage.insert(meta, payload)
            if (probeId == null && payload.contents.isNotEmpty()) probeId = meta.id

            if ((index + 1) % 1_000 == 0) {
                val seconds = (System.currentTimeMillis() - started) / 1000
                println("  已写入 ${index + 1} / $TOTAL（${seconds}s）")
            }
        }

        println("完成，用时 ${(System.currentTimeMillis() - started) / 1000} 秒")
        println("最终未置顶条数：${storage.countUnpinned()}")

        probeId?.let { id ->
            val contents = storage.loadPayload(id)?.contents.orEmpty()
            println(
                "回读验证：附加表示 ${contents.size} 项 —— " +
                    contents.joinToString { "${it.type}(${it.value?.size ?: 0}B)" },
            )
        }

        // 搜索基准：拿全部元数据跑一遍四种模式。这四个数字就是「全量搜索」的真实成本，
        // 也是它必须放在后台线程的理由。
        val metas = storage.loadUnpinned(
            by = SortBy.LAST_COPIED_AT,
            order = SortOrder.DESCENDING,
            limit = Int.MAX_VALUE,
            offset = 0,
        )
        println("搜索基准（${metas.size} 条元数据）：")
        for (mode in SearchMode.entries) {
            val query = when (mode) {
                SearchMode.EXACT -> "SELECT"
                SearchMode.REGEXP -> "(SELECT|select)"
                SearchMode.FUZZY, SearchMode.MIXED -> "select"
            }
            val startedAt = System.currentTimeMillis()
            val hits = ClipSearch.search(query, metas, mode)
            println("  ${mode.label}('$query') → ${hits.size} 命中，${System.currentTimeMillis() - startedAt} ms")
        }
    } finally {
        storage.close()
    }
}

/** 造一条历史：前 [IMAGE_COUNT] 条带图片，其余是各种形态的文本。 */
private fun buildItem(random: Random, index: Int, publishedAt: Long): Pair<ClipMeta, ClipPayload> {
    val id = UUID.randomUUID().toString()
    val application = APPS[random.nextInt(APPS.size)]
    val image = if (index < IMAGE_COUNT) generateImage(random, index) else null
    val text = if (image == null) textFor(random, index) else null
    // 每 200 条造一个带 HTML 的条目：验证「附加表示」经 CBOR + BLOB 的往返，
    // 以及它在库里到底占多少字节（改之前这里是 JSON 数字数组）。
    val contents = if (image == null && index % 200 == 0) {
        listOf(
            ClipboardContent(
                type = "public.html",
                value = "<html><body><p style=\"color:#333\">$text</p></body></html>".encodeToByteArray(),
            ),
        )
    } else {
        emptyList()
    }

    // 标题：文本条目是原文（列表渲染时才格式化），图片条目是模拟的识别结果或空白。
    val recognized = image != null && random.nextInt(3) != 0
    val title = when {
        recognized -> "截图中的文字 $index：订单编号 ${random.nextInt(100_000)}"
        image != null -> ""
        else -> text.orEmpty().take(ClipItem.MAX_TITLE_LENGTH)
    }

    val imageBytes = image?.let(::ClipImage)
    val payloadBytes = (text?.encodeToByteArray()?.size ?: 0).toLong() + (image?.size ?: 0).toLong()

    val meta = ClipMeta(
        id = id,
        title = title,
        kind = if (image != null) ClipFilterType.IMAGE else ClipFilterType.TEXT,
        files = emptyList(),
        application = application,
        // 首次复制略早于最后一次，留出一点差距让「首次复制」排序不是全等值。
        firstCopiedAt = publishedAt - random.nextInt(600_000),
        lastCopiedAt = publishedAt,
        numberOfCopies = 1 + random.nextInt(30),
        // 三条置顶，用来验证置顶区块与「置顶不占额度」。
        pin = if (index % 7_000 == 0) ClipItem.PINNED_MARKER else null,
        payloadBytes = payloadBytes,
        contentKey = contentKeyOf(text, imageBytes, emptyList(), contents),
        hasRecognizedText = recognized,
        hasImage = image != null,
    )
    return meta to ClipPayload(text = text, image = imageBytes, contents = contents)
}

/** 六种形态轮换：URL、SQL、中文短句、多行长文本、邮箱、短串。 */
private fun textFor(random: Random, index: Int): String = when (index % 6) {
    0 -> "https://example.com/docs/${UUID.randomUUID().toString().take(8)}?page=${random.nextInt(10_000)}"

    1 -> "SELECT id, title, payloadBytes FROM clip_meta " +
        "WHERE pinned = 0 ORDER BY lastCopiedAt DESC LIMIT ${random.nextInt(500)};"

    2 -> "会议纪要 $index：确认了拆表方案，图片与元数据分开存储，写入量降了两个数量级。"

    3 -> buildString {
        repeat(1 + random.nextInt(3)) { paragraph ->
            append("第 ${paragraph + 1} 段：这是一段用于填充测试的长文本，长度不固定，")
            append("用来验证标题截断、模糊匹配与预览面板的滚动。\n")
        }
    }

    4 -> "user$index@example.com"

    else -> "a".repeat(5 + random.nextInt(40))
}

/**
 * 生成一张确定性的 PNG。
 *
 * 形状数量随 [index] 递增，因此 50 张图的字节数有明显差异（几十 KB 到几百 KB），
 * 足以验证「载荷表只做整条增删」这条路径。
 */
private fun generateImage(random: Random, index: Int): ByteArray {
    val width = 640 + random.nextInt(7) * 96
    val height = 480 + random.nextInt(6) * 96
    val canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)

    val graphics = canvas.createGraphics()
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

    val hue = random.nextInt(360)
    graphics.paint = GradientPaint(
        0f,
        0f,
        Color.getHSBColor(hue / 360f, 0.55f, 0.95f),
        width.toFloat(),
        height.toFloat(),
        Color.getHSBColor(((hue + 80) % 360) / 360f, 0.75f, 0.55f),
    )
    graphics.fillRect(0, 0, width, height)

    repeat(20 + index * 4) {
        graphics.color = Color(random.nextInt(256), random.nextInt(256), random.nextInt(256), 170)
        val x = random.nextInt(width)
        val y = random.nextInt(height)
        val w = 20 + random.nextInt(width / 3)
        val h = 20 + random.nextInt(height / 3)
        if (random.nextBoolean()) graphics.fillOval(x, y, w, h) else graphics.fillRect(x, y, w, h)
    }
    graphics.dispose()

    val out = ByteArrayOutputStream()
    ImageIO.write(canvas, "png", out)
    return out.toByteArray()
}
