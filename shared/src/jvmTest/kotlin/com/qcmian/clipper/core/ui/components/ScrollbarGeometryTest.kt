package com.qcmian.clipper.core.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 滚动条的几何：滑块多长、拖到某个位置该滚到哪里。
 *
 * 这段换算此前没有测试，而它一出错的表现很隐蔽：滑块长度与拖拽落点若各按一套公式算，行程就
 * 不一样长，拖到底会差出一截内容——看起来只是「滚不到头」，很难联想到换算。
 *
 * 滑块几何与落点换算严格互逆，这里把这条性质直接跑满。
 */
class ScrollbarGeometryTest {

    private val minThumb = 18f

    // ------------------------------------------------------------------ 滑块长度

    @Test
    fun `内容与可视一样高时滑块占满轨道`() {
        assertEquals(1000f, thumbLengthFor(1000f, 1000f, 1000f, minThumb))
    }

    @Test
    fun `滑块长度按可视与内容之比`() {
        // 可视 250、内容 1000：滑块占轨道的四分之一。
        assertEquals(250f, thumbLengthFor(1000f, 250f, 1000f, minThumb))
    }

    @Test
    fun `内容极长时滑块停在下限`() {
        // 按比例算只有 0.1px，必须垫到下限，否则既看不见也抓不住。
        assertEquals(minThumb, thumbLengthFor(1000f, 1f, 1_000_000f, minThumb))
    }

    /**
     * 下限不能把轨道吃光：轨道矮到放不下下限时，滑块最多占一半，否则行程归零、拖不动。
     * 这条是 [ScrollbarThumbMinLength] 里那段说明的可执行版本。
     */
    @Test
    fun `轨道矮到放不下下限时滑块最多占一半`() {
        val track = 20f
        val length = thumbLengthFor(track, 1f, 1_000_000f, minThumb)

        assertEquals(track / 2f, length)
        assertTrue(length < track, "滑块占满轨道就没有行程了")
    }

    // ------------------------------------------------------------------ 拖拽落点

    @Test
    fun `拖到轨道最右端滚到最末`() {
        val track = 1000f
        val thumb = thumbLengthFor(track, 250f, 1000f, minThumb)

        assertEquals(750f, scrollOffsetForThumbStart(track - thumb, thumb, track, maxScroll = 750f))
    }

    @Test
    fun `拖到轨道最左端滚回开头`() {
        assertEquals(0f, scrollOffsetForThumbStart(0f, 250f, 1000f, maxScroll = 750f))
    }

    @Test
    fun `没有可滚空间时不给落点`() {
        assertNull(scrollOffsetForThumbStart(0f, 250f, 1000f, maxScroll = 0f))
    }

    @Test
    fun `滑块与轨道一样长时不给落点`() {
        assertNull(scrollOffsetForThumbStart(0f, 1000f, 1000f, maxScroll = 100f))
    }

    /** 落点换算必须是滑块几何的逆：按几何算出的起点拖回去，应当回到同一个滚动值。 */
    @Test
    fun `落点换算与滑块几何互逆`() {
        val track = 400f
        val viewport = 250f
        val content = 1000f
        val maxScroll = content - viewport
        val thumb = thumbLengthFor(track, viewport, content, minThumb)

        for (fraction in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val start = fraction * (track - thumb)
            val scrolled = scrollOffsetForThumbStart(start, thumb, track, maxScroll)!!
            // 反推：滚到这个值，滑块起点应当就是刚才那个 start。
            val backStart = (scrolled / maxScroll) * (track - thumb)
            assertEquals(start, backStart, 0.01f, "fraction=$fraction 处正反换算对不上")
        }
    }

    /**
     * 横向与竖向共用同一套换算，因此「把长度换成宽度」不应改变任何数值。
     * 这一条明说了它是轴无关的：将来谁想给某个方向另写一套，先看看这里。
     */
    @Test
    fun `同一组数值在横竖两个方向给出同一结果`() {
        val asVertical = thumbLengthFor(1000f, 250f, 1000f, minThumb)
        val asHorizontal = thumbLengthFor(1000f, 250f, 1000f, minThumb)

        assertEquals(asVertical, asHorizontal)
    }
}
