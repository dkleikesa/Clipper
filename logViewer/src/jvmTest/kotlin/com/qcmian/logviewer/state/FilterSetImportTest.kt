package com.qcmian.logviewer.state

import com.qcmian.logviewer.core.model.FileRef
import com.qcmian.logviewer.core.model.FilterConfig
import com.qcmian.logviewer.core.model.FilterSet
import com.qcmian.logviewer.platform.LogViewerPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 规则集合的导入。
 *
 * 规则靠 `id` 认身份（改一条、删一条都按 id 找），而导入文件里带的是**当时那份 id**：原样装进来
 * 就会和现有规则撞号，之后动一条等于同时动两条。
 */
class FilterSetImportTest {

    private fun newState() = ViewerState(0, NoPlatform, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `导入的规则重新分配 id`() {
        val state = newState()
        val duplicated = 42L
        state.applyFilterSet(
            FilterSet(
                filters = listOf(
                    FilterConfig(id = duplicated, pattern = "ERROR"),
                    FilterConfig(id = duplicated, pattern = "WARN"),
                ),
            )
        )

        assertEquals(2, state.filters.size)
        assertEquals(2, state.filters.map { it.id }.distinct().size)
    }

    @Test
    fun `导入不改变现有规则的 id`() {
        val state = newState()
        state.addFilter(FilterConfig(pattern = "existing"))
        val existingId = state.filters.single().id

        state.applyFilterSet(FilterSet(filters = listOf(FilterConfig(pattern = "imported"))))

        // 整组替换：旧规则走人，新规则拿到新号（不会复用旧号，否则撤销/重做会串）。
        assertEquals(1, state.filters.size)
        assertTrue(state.filters.none { it.id == existingId })
    }

    /** 导入与过滤都不碰平台能力，这里只需要一个满足接口的空壳。 */
    private object NoPlatform : LogViewerPlatform {
        override fun pickFiles(onResult: (List<FileRef>) -> Unit) = Unit

        override fun saveTextFile(defaultName: String, content: String, onDone: (Boolean) -> Unit) = Unit

        override fun setClipboard(text: String, html: String?) = Unit

        override fun getClipboardText(): String? = null

        override fun putPref(key: String, value: String) = Unit

        override fun getPref(key: String): String? = null
    }
}
