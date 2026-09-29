package com.qcmian.clipper.core.testutil

import com.qcmian.clipper.core.domain.model.ClipboardContent
import com.qcmian.clipper.core.platform.macos.MacPasteboard
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue

/**
 * 需要**真实系统粘贴板**的测试的基类。
 *
 * 这类测试没有替代办法：类型过滤、item 分组、`writeObjects:` 的语义，都只有真跑一遍才知道。
 * 代价是它们会占用系统剪贴板（驱动 Finder 的那几个还会抢焦点、发 `⌘C` / `⌘V`），因此日常
 * 开发里默认不跑——设环境变量 `CLIPPER_PASTEBOARD_TESTS=1` 才启用。
 *
 * 启用后每个用例跑完都会把原来的剪贴板内容写回去。
 */
abstract class LivePasteboardTest {

    private var original: List<ClipboardContent> = emptyList()

    @BeforeTest
    fun prepareLivePasteboard() {
        // 用 JUnit 的 assumption 而不是断言：环境不具备时这些用例应当被**跳过**而不是失败，
        // 它们没能跑起来不代表代码有问题。
        assumeTrue(
            "真实粘贴板测试默认跳过；设 CLIPPER_PASTEBOARD_TESTS=1 启用",
            livePasteboardTestsEnabled(),
        )
        original = MacPasteboard.readContents()
    }

    @AfterTest
    fun restoreLivePasteboard() {
        // assumption 在 `@Before` 里失败时这里仍会被调用，此时 `original` 还是空的，直接跳过。
        if (original.isNotEmpty()) MacPasteboard.write(original)
    }
}

/** 只有显式设了环境变量才跑真实粘贴板测试。 */
internal fun livePasteboardTestsEnabled(): Boolean = System.getenv("CLIPPER_PASTEBOARD_TESTS") == "1"
