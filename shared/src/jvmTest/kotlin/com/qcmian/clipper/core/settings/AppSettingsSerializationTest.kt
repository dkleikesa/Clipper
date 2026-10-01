package com.qcmian.clipper.core.settings

import com.qcmian.clipper.core.domain.action.ClipAction
import com.qcmian.clipper.core.util.decodeJsonOrNull
import com.qcmian.clipper.core.util.encodeJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * 偏好设置的**存档契约**与槽位表的一致性。
 *
 * 这类错误只在「改名 / 增删字段 / 换枚举成员」之后、读到旧存档时才显形，而且**静默**：字段对
 * 不上号就退回默认值，用户的自定义快捷键、排序、尺寸会一起消失，界面上却什么提示都没有。
 */
class AppSettingsSerializationTest {

    @Test
    fun `factory defaults match the slot defaults for every slot`() {
        val settings = AppSettings()
        for (slot in ShortcutSlot.entries) {
            assertEquals(
                slot.default,
                settings.shortcut(slot),
                "槽位 ${slot.name} 的出厂绑定必须与声明处一致",
            )
        }
    }

    @Test
    fun `withShortcut writes every slot and leaves the others alone`() {
        val spec = ShortcutSpec("Q", command = true, shift = true)

        for (slot in ShortcutSlot.entries) {
            val updated = AppSettings().withShortcut(slot, spec)
            assertEquals(spec, updated.shortcut(slot), "withShortcut 必须覆盖槽位 ${slot.name}")

            for (other in ShortcutSlot.entries) {
                if (other == slot) continue
                assertEquals(
                    other.default,
                    updated.shortcut(other),
                    "改 ${slot.name} 不该动到 ${other.name}",
                )
            }
        }
    }

    @Test
    fun `clearing a binding is distinct from reverting to the default`() {
        val cleared = AppSettings().withShortcut(ShortcutSlot.PIN, null)

        assertEquals(null, cleared.shortcut(ShortcutSlot.PIN), "null 是「用户清除」，不是「恢复默认」")
        assertNotEquals(null, AppSettings().shortcut(ShortcutSlot.PIN))
    }

    @Test
    fun `settings survive a json round trip`() {
        val settings = AppSettings()
            .withShortcut(ShortcutSlot.PIN, ShortcutSpec("K", option = true, shift = true))
            .withShortcut(ShortcutSlot.PAUSE, null)
            .copy(
                sortBy = SortBy.FILE_SIZE,
                sortOrder = SortOrder.ASCENDING,
                filterTypes = setOf(ClipFilterType.IMAGE, ClipFilterType.FILE),
                historyMaxCount = 42,
                themeMode = ThemeMode.DARK,
                popupPosition = PopupPosition.CURSOR,
                clickAction = ClipAction.COPY_WITHOUT_FORMATTING,
                customWindowWidth = 640,
                previewOpen = true,
            )

        val restored = decodeJsonOrNull<AppSettings>(encodeJson(settings))
        assertNotNull(restored)
        assertEquals(settings, restored)
        assertEquals(null, restored.shortcut(ShortcutSlot.PAUSE), "清除过的槽位必须还原成 null")
    }

    @Test
    fun `an empty archive decodes to the factory defaults`() {
        assertEquals(AppSettings(), decodeJsonOrNull<AppSettings>("{}"))
    }

    @Test
    fun `unknown fields from a newer version are ignored and missing ones use the defaults`() {
        val restored = decodeJsonOrNull<AppSettings>("""{"historyMaxCount":7,"brandNewField":true}""")

        assertNotNull(restored)
        assertEquals(7, restored.historyMaxCount)
        assertEquals(AppSettings().themeMode, restored.themeMode, "缺失字段用默认值补上")
    }

    @Test
    fun `a removed enum member falls back to that field only`() {
        // coerceInputValues 的全部价值：旧存档里写着已被删除的枚举值时，只把那一项退回默认，
        // 而不是让整个 decode 失败、把用户的所有偏好一起重置。
        val restored = decodeJsonOrNull<AppSettings>(
            """{"sortBy":"SOMETHING_REMOVED","historyMaxCount":9}""",
        )

        assertNotNull(restored, "未知枚举值不该让整份存档解不出来")
        assertEquals(SortBy.LAST_COPIED_AT, restored.sortBy)
        assertEquals(9, restored.historyMaxCount, "同一份存档里的其它字段不能被一起丢掉")
    }
}
