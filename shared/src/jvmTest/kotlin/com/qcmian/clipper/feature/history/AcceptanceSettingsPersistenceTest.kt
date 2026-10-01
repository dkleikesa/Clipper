package com.qcmian.clipper.feature.history

import com.qcmian.clipper.core.data.local.AppSettingsEntity
import com.qcmian.clipper.core.data.local.openFileDatabase
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.settings.PopupPosition
import com.qcmian.clipper.core.settings.SortBy
import com.qcmian.clipper.core.settings.SortOrder
import com.qcmian.clipper.core.settings.ThemeMode
import com.qcmian.clipper.core.testutil.InProcessCluster
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F7（A 层）：**设置与持久化**。
 *
 * 用**落盘**的库（而不是内存库）：偏好存在 `app_settings` 的单行 JSON 里，「重启后保持」只有把
 * 库关掉再开一次才验得出来。恢复默认走的是设置页那条 `onSettingsChange { AppSettings() }`。
 *
 * 覆盖：改设置即时生效并落盘、重启后保持、恢复默认回到出厂值且不动历史、旧存档里的清除槽位 /
 * 已删除枚举 / 新增字段都读得出来。
 */
class AcceptanceSettingsPersistenceTest {

    private lateinit var directory: File
    private lateinit var file: File

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("clipper-settings").toFile()
        file = File(directory, "clipper.db")
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `改设置即时生效 重启后仍保持`() = runBlocking<Unit> {
        val custom = AppSettings(
            sortBy = SortBy.NUMBER_OF_COPIES,
            sortOrder = SortOrder.ASCENDING,
            historyMaxCount = 77,
            themeMode = ThemeMode.DARK,
            popupPosition = PopupPosition.CURSOR,
            previewWidth = 420,
            showTypeIcons = false,
            previewOpen = true,
            pauseShortcut = null,
        )

        open().let { cluster ->
            try {
                cluster.start()
                cluster.repository.setSettings(custom)
                assertEquals(custom, cluster.repository.settings.value, "内存里立即生效，不必等落盘那一刻")
            } finally {
                cluster.shutdown()
            }
        }

        open().let { reopened ->
            try {
                reopened.start()
                assertEquals(custom, reopened.repository.settings.value, "重启后仍是用户那一份，包括被清除的槽位")
            } finally {
                reopened.shutdown()
            }
        }
    }

    @Test
    fun `恢复默认设置回到出厂值 且不影响历史`() = runBlocking<Unit> {
        open().let { cluster ->
            try {
                cluster.start()
                cluster.seedText("id0", "一条历史", lastCopiedAt = 1)
                cluster.repository.setSettings(
                    AppSettings(themeMode = ThemeMode.DARK, historyMaxCount = 5, sortBy = SortBy.FILE_SIZE),
                )

                // 设置页底部的「恢复默认设置」：`onSettingsChange { AppSettings() }`。
                cluster.useCases.updateSettings { AppSettings() }

                assertEquals(AppSettings(), cluster.repository.settings.value)
                assertEquals(1, cluster.historySize, "恢复默认只覆盖设置——历史与置顶项都不属于设置")
            } finally {
                cluster.shutdown()
            }
        }

        open().let { reopened ->
            try {
                reopened.start()
                assertEquals(AppSettings(), reopened.repository.settings.value, "恢复后的默认值也要落盘")
                assertEquals(1, reopened.historySize)
            } finally {
                reopened.shutdown()
            }
        }
    }

    @Test
    fun `旧存档里清除的槽位 已删除的枚举 新增字段都读得出`() = runBlocking<Unit> {
        // 直接写一份「上一版」留下的 JSON：一个被清除的槽位、一个已删除的枚举成员、一个本版还不认识的字段。
        val database = openFileDatabase(file)
        try {
            database.appSettingsDao().save(
                AppSettingsEntity(
                    id = AppSettingsEntity.SINGLE_ROW_ID,
                    payload = """{"pauseShortcut":null,"sortBy":"GONE","historyMaxCount":33,"futureField":true}""",
                ),
            )
        } finally {
            database.close()
        }

        open().let { cluster ->
            try {
                cluster.start()
                val settings = cluster.repository.settings.value
                assertEquals(null, settings.pauseShortcut, "被清除的槽位仍是「未绑定」，不会被默认值悄悄填回来")
                assertEquals(
                    SortBy.LAST_COPIED_AT,
                    settings.sortBy,
                    "已删除的枚举成员只让那一项退回默认，而不是让整份偏好解不出来",
                )
                assertEquals(33, settings.historyMaxCount, "同一份存档里其它偏好必须照常读得出")
            } finally {
                cluster.shutdown()
            }
        }
    }

    private fun open(): InProcessCluster = InProcessCluster(database = openFileDatabase(file))
}
