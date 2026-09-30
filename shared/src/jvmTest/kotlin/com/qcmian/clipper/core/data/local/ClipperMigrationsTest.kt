package com.qcmian.clipper.core.data.local

import androidx.room3.useReaderConnection
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * `1 → 2` 的迁移：`clip_meta` 增加 `pinnedAt`。
 *
 * 这条迁移有历史包袱——**2026-09-25 丢过一次真实历史**：当时只改了实体、没写迁移，而构建器上挂着
 * `fallbackToDestructiveMigration()`，用户什么都没点、只是启动了一次应用，历史 + 置顶 + 偏好
 * 一起没了。现在没有那条兜底（缺迁移就打不开库，数据还在），但**迁移本身仍然没有测试**。
 *
 * 真正值得守的不是「加了列」（那只用看 schema），而是**回填的语义**：
 *
 * > 回填用 `lastCopiedAt`：旧库的置顶区本来就是按它排序的，拿它当戳正好把**当前看到的顺序
 * > 原样冻结下来**。若一律填 0，旧置顶项会全挤在同一个值上，先后只能听 rowid 的。
 *
 * 一律填 0 的迁移同样能跑通、同样不报错，只是用户的置顶顺序会被打乱——这条断言就是为了让它
 * 不能悄悄退化成那样。
 */
class ClipperMigrationsTest {

    private lateinit var directory: File
    private lateinit var file: File

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("clipper-migration").toFile()
        file = File(directory, "clipper.db")
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `从 v1 升级时把置顶顺序冻结在 lastCopiedAt 上`() = runBlocking {
        createVersionOneDatabase(file)

        val database = openFileDatabase(file)
        try {
            val dao = database.clipHistoryDao()

            assertEquals(4, dao.countAll(), "迁移不该丢数据")
            assertEquals(
                listOf("p2", "p3", "p1"),
                dao.loadPinned().map { it.id },
                "升级前的置顶顺序（按 lastCopiedAt 300/200/100）必须被原样冻结为 pinnedAt",
            )

            assertEquals(300L, assertNotNull(dao.loadMeta("p2")).pinnedAt)
            assertEquals(200L, assertNotNull(dao.loadMeta("p3")).pinnedAt)
            assertEquals(100L, assertNotNull(dao.loadMeta("p1")).pinnedAt)
            assertEquals(
                0L,
                assertNotNull(dao.loadMeta("u1")).pinnedAt,
                "未置顶行的 pinnedAt 是占位 0，不该被回填成别的东西",
            )

            assertEquals(
                1L,
                database.scalarLong(
                    "SELECT count(*) FROM sqlite_master WHERE type = 'index' " +
                        "AND name = 'index_clip_meta_pinned_pinnedAt'",
                ),
                "迁移必须把新索引一起建出来，否则置顶区的分页会退化成全表排序",
            )
            assertEquals(2L, database.scalarLong("PRAGMA user_version"), "迁移之后版本号要跟上")
        } finally {
            database.close()
        }
    }

    /** 按 `schemas/.../1.json` 原样建一个 v1 库；`identity_hash` 必须一致，否则 Room 迁移前就会拒绝打开。 */
    private fun createVersionOneDatabase(target: File) {
        DriverManager.getConnection("jdbc:sqlite:${target.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                VERSION_ONE_DDL.forEach(statement::execute)
                statement.execute("PRAGMA user_version = 1")
            }
            connection.prepareStatement(VERSION_ONE_INSERT).use { insert ->
                listOf(
                    // id, lastCopiedAt, pinned
                    Triple("p1", 100L, 1),
                    Triple("p2", 300L, 1),
                    Triple("p3", 200L, 1),
                    Triple("u1", 400L, 0),
                ).forEach { (id, lastCopiedAt, pinned) ->
                    insert.setString(1, id)
                    insert.setString(2, "标题 $id")
                    insert.setInt(3, 0)
                    insert.setString(4, "")
                    insert.setString(5, null)
                    insert.setString(6, null)
                    insert.setLong(7, lastCopiedAt)
                    insert.setLong(8, lastCopiedAt)
                    insert.setInt(9, 1)
                    insert.setInt(10, pinned)
                    insert.setLong(11, 0L)
                    insert.setString(12, "key-$id")
                    insert.setInt(13, 0)
                    insert.setInt(14, 0)
                    insert.addBatch()
                }
                insert.executeBatch()
            }
        }
    }

    private suspend fun ClipperDatabase.scalarLong(sql: String): Long =
        useReaderConnection { connection ->
            connection.usePrepared(sql) { statement -> if (statement.step()) statement.getLong(0) else -1L }
        }

    private companion object {
        /** `schemas/.../1.json` 里的 `identityHash`。 */
        const val VERSION_ONE_IDENTITY_HASH = "87d8015ea3a6c0856d6731bbead51174"

        /** v1 的 `clip_meta` 没有 `pinnedAt` 列，也没有对应的联合索引——这正是迁移要补的东西。 */
        val VERSION_ONE_DDL = listOf(
            "CREATE TABLE IF NOT EXISTS `clip_meta` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`kind` INTEGER NOT NULL, `files` TEXT NOT NULL, `applicationName` TEXT, " +
                "`applicationBundleId` TEXT, `firstCopiedAt` INTEGER NOT NULL, `lastCopiedAt` INTEGER NOT NULL, " +
                "`numberOfCopies` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, `payloadBytes` INTEGER NOT NULL, " +
                "`contentKey` TEXT NOT NULL, `hasRecognizedText` INTEGER NOT NULL, `hasImage` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `clip_payload` (`id` TEXT NOT NULL, `text` TEXT, `image` BLOB, " +
                "`contents` BLOB, `recognizedText` TEXT, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `app_settings` (`id` INTEGER NOT NULL, `payload` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_clip_meta_pinned_lastCopiedAt` ON `clip_meta` (`pinned`, `lastCopiedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_clip_meta_pinned_firstCopiedAt` ON `clip_meta` (`pinned`, `firstCopiedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_clip_meta_pinned_numberOfCopies` ON `clip_meta` (`pinned`, `numberOfCopies`)",
            "CREATE INDEX IF NOT EXISTS `index_clip_meta_pinned_payloadBytes` ON `clip_meta` (`pinned`, `payloadBytes`)",
            "CREATE INDEX IF NOT EXISTS `index_clip_meta_contentKey` ON `clip_meta` (`contentKey`)",
            "CREATE INDEX IF NOT EXISTS `index_clip_meta_applicationBundleId` ON `clip_meta` (`applicationBundleId`)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) " +
                "VALUES(42, '$VERSION_ONE_IDENTITY_HASH')",
        )

        const val VERSION_ONE_INSERT =
            "INSERT INTO clip_meta (id, title, kind, files, applicationName, applicationBundleId, " +
                "firstCopiedAt, lastCopiedAt, numberOfCopies, pinned, payloadBytes, contentKey, " +
                "hasRecognizedText, hasImage) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
    }
}
