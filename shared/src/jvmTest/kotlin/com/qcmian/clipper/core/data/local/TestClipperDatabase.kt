package com.qcmian.clipper.core.data.local

import androidx.room3.Room
import androidx.room3.RoomDatabase
import com.qcmian.clipper.core.data.source.SqlCipherDriver
import java.io.File

/**
 * 测试用的数据库工厂。
 *
 * 刻意复刻线上 [com.qcmian.clipper.core.data.source.createClipStorageDataSource] 的两处关键配置：
 * 同一个 [SqlCipherDriver]（默认明文）与同一个 `TRUNCATE` 日志模式——否则测的就不是线上那套
 * 单连接 + 回滚日志的并发模型了。
 *
 * 用内存库：每个用例一份全新 schema，互不串数据，也不必清理临时文件。
 * 内存库在 Room 里强制单连接池（见 `RoomDatabase.Builder.build`），与 `TRUNCATE` 的选择一致。
 */
internal fun openInMemoryDatabase(): ClipperDatabase =
    Room.inMemoryDatabaseBuilder<ClipperDatabase>(factory = ClipperDatabaseConstructor::initialize)
        .addMigrations(*CLIPPER_MIGRATIONS)
        .setDriver(SqlCipherDriver())
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        .build()

/**
 * 打开一个落在 [file] 上的库，用于验证「关掉再打开，数据还在」。
 *
 * 必须落盘才能验持久化：内存库一 `close` 就什么都不剩。
 */
internal fun openFileDatabase(file: File): ClipperDatabase {
    file.parentFile?.mkdirs()
    return Room.databaseBuilder<ClipperDatabase>(
        name = file.absolutePath,
        factory = ClipperDatabaseConstructor::initialize,
    )
        .addMigrations(*CLIPPER_MIGRATIONS)
        .setDriver(SqlCipherDriver())
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        .build()
}

/**
 * 构造一条测试用元数据行。
 *
 * 只给会参与断言的字段暴露参数，其余取与「一条普通文本」相符的默认值，避免每个用例都被
 * 十几个无关字段淹没。默认未置顶，与真实写入路径一致（新条目从不一进来就是置顶的）。
 */
internal fun metaRow(
    id: String,
    title: String = "title-$id",
    kind: Int = 0,
    files: String = "",
    applicationName: String? = null,
    applicationBundleId: String? = null,
    firstCopiedAt: Long = 0L,
    lastCopiedAt: Long = 0L,
    numberOfCopies: Int = 1,
    pinned: Int = UNPINNED,
    pinnedAt: Long = NOT_PINNED_AT,
    payloadBytes: Long = 0L,
    contentKey: String = "key-$id",
    hasRecognizedText: Boolean = false,
    hasImage: Boolean = false,
): ClipMetaEntity = ClipMetaEntity(
    id = id,
    title = title,
    kind = kind,
    files = files,
    applicationName = applicationName,
    applicationBundleId = applicationBundleId,
    firstCopiedAt = firstCopiedAt,
    lastCopiedAt = lastCopiedAt,
    numberOfCopies = numberOfCopies,
    pinned = pinned,
    pinnedAt = pinnedAt,
    payloadBytes = payloadBytes,
    contentKey = contentKey,
    hasRecognizedText = hasRecognizedText,
    hasImage = hasImage,
)

/** 构造一条测试用载荷行；三个内容列默认都为空，需要哪个再显式传。 */
internal fun payloadRow(
    id: String,
    text: String? = null,
    image: ByteArray? = null,
    contents: ByteArray? = null,
    recognizedText: String? = null,
): ClipPayloadEntity = ClipPayloadEntity(
    id = id,
    text = text,
    image = image,
    contents = contents,
    recognizedText = recognizedText,
)
