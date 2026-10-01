package com.qcmian.clipper.core.ui

import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.settings.ShortcutSlot
import com.qcmian.clipper.core.settings.ShortcutSpec
import com.qcmian.clipper.core.settings.occupiedSpecs
import com.qcmian.clipper.core.settings.withShortcut
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 录制快捷键时的本地判定 [shortcutProblem] 与派生组合表 [occupiedSpecs]。
 *
 * 规则表密集、纯函数，走 UI 才能触达，成本高；写歪了会**静默**放行一个打架的组合。
 * [ShortcutProblem.OCCUPIED] 不在本地判定——它必须由调用方真的去系统里注册一次才能得出。
 */
class ShortcutValidationTest {

    @Test
    fun `a unique combination with a modifier is accepted`() {
        assertEquals(
            null,
            shortcutProblem(ShortcutSpec("K", command = true, shift = true), ShortcutSlot.PIN, AppSettings()),
        )
    }

    @Test
    fun `bare typing characters need a modifier`() {
        // 字母在录制时已被归一到大写（见 `normalizeShortcutCharacter`），因此这里只列大写形。
        for (character in listOf("A", "K", "5", ",", " ")) {
            assertEquals(
                ShortcutProblem.NEEDS_MODIFIER,
                shortcutProblem(ShortcutSpec(character), ShortcutSlot.PIN, AppSettings()),
                "裸键 '$character' 会吞掉搜索输入，必须拦下",
            )
        }
    }

    @Test
    fun `non typing keys may stay bare in panel scoped slots but never globally`() {
        val settings = AppSettings()

        assertEquals(
            null,
            shortcutProblem(ShortcutSpec("\u2190"), ShortcutSlot.PIN, settings),
            "方向键不是输入字符，面板内裸按不吞打字（默认绑定就是裸 ↑ / ↓）",
        )
        assertEquals(
            ShortcutProblem.NEEDS_MODIFIER,
            shortcutProblem(ShortcutSpec("\u2190"), ShortcutSlot.POPUP, settings),
            "系统级热键再裸也不能没有修饰键",
        )
    }

    @Test
    fun `quick select only accepts digits`() {
        val settings = AppSettings()

        assertEquals(
            ShortcutProblem.QUICK_SELECT_DIGIT,
            shortcutProblem(ShortcutSpec("K", command = true), ShortcutSlot.QUICK_SELECT, settings),
            "数字只是占位，录进字母会让人误以为绑定了那个键",
        )
        assertEquals(
            null,
            shortcutProblem(ShortcutSpec("5", command = true), ShortcutSlot.QUICK_SELECT, settings),
        )
    }

    @Test
    fun `an identical binding elsewhere is a duplicate`() {
        val settings = AppSettings().withShortcut(ShortcutSlot.PIN, ShortcutSpec("K", command = true))

        assertEquals(
            ShortcutProblem.DUPLICATE,
            shortcutProblem(ShortcutSpec("K", command = true), ShortcutSlot.DELETE, settings),
        )
    }

    @Test
    fun `derived shift combinations from navigation are duplicates too`() {
        // MOVE_NEXT 的默认是裸 ↓；它的派生组合 ⇧↓ 同样会与别的功能打架。
        assertEquals(
            ShortcutProblem.DUPLICATE,
            shortcutProblem(ShortcutSpec("\u2193", shift = true), ShortcutSlot.PIN, AppSettings()),
        )
    }

    @Test
    fun `digits sharing the quick select modifiers are reserved`() {
        // 默认快速粘贴是 ⌘1：同一修饰键下的任意数字都会被它先接走。
        assertEquals(
            ShortcutProblem.RESERVED,
            shortcutProblem(ShortcutSpec("5", command = true), ShortcutSlot.PIN, AppSettings()),
        )
        assertEquals(
            null,
            shortcutProblem(ShortcutSpec("5", option = true), ShortcutSlot.PIN, AppSettings()),
            "换一组修饰键就不在快速粘贴的范围内",
        )
    }

    @Test
    fun `an exact quick select digit is reported as duplicate before reserved`() {
        assertEquals(
            ShortcutProblem.DUPLICATE,
            shortcutProblem(ShortcutSpec("1", command = true), ShortcutSlot.PIN, AppSettings()),
            "与已有绑定完全一致时，先报「重复」而不是内置占位",
        )
    }

    @Test
    fun `the cheapest rule wins when several apply`() {
        // 裸字母进快速粘贴：先拦「需要修饰键」，而不是先报「不是数字键」。
        assertEquals(
            ShortcutProblem.NEEDS_MODIFIER,
            shortcutProblem(ShortcutSpec("A"), ShortcutSlot.QUICK_SELECT, AppSettings()),
        )
    }

    @Test
    fun `the local check never fabricates platform occupancy`() {
        // 本地只判断「与面板自身的关系」，没有任何冲突时必须返回 null，而不是猜一个 OCCUPIED。
        assertEquals(
            null,
            shortcutProblem(ShortcutSpec("K", command = true, shift = true), ShortcutSlot.PIN, AppSettings()),
        )
    }

    @Test
    fun `occupied specs add the shift variant only for plain navigation`() {
        assertEquals(
            listOf(ShortcutSpec("\u2193"), ShortcutSpec("\u2193", shift = true)),
            ShortcutSlot.MOVE_NEXT.occupiedSpecs(ShortcutSpec("\u2193")),
        )
        assertEquals(
            listOf(ShortcutSpec("\u2191", shift = true)),
            ShortcutSlot.MOVE_PREVIOUS.occupiedSpecs(ShortcutSpec("\u2191", shift = true)),
            "已经有修饰键时不再派生第二个组合",
        )
        assertEquals(
            listOf(ShortcutSpec("K", command = true)),
            ShortcutSlot.PIN.occupiedSpecs(ShortcutSpec("K", command = true)),
            "其余槽位没有派生组合",
        )
        assertTrue(
            ShortcutSlot.PIN.occupiedSpecs(ShortcutSpec("K", command = true)).size == 1,
            "非导航槽位永远只有一个组合",
        )
    }
}
