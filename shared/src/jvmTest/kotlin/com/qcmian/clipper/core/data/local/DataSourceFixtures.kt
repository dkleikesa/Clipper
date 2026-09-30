package com.qcmian.clipper.core.data.local

import com.qcmian.clipper.core.data.source.EncryptionBackup
import com.qcmian.clipper.core.data.source.SessionKey
import com.qcmian.clipper.core.domain.model.ClipImage
import com.qcmian.clipper.core.domain.model.ClipMeta
import com.qcmian.clipper.core.domain.model.ClipPayload
import com.qcmian.clipper.core.domain.model.ClipboardContent
import com.qcmian.clipper.core.domain.model.SourceApplication
import com.qcmian.clipper.core.settings.ClipFilterType

// ---------------------------------------------------------------------------------------
// 领域模型夹具
//
// 与 `metaRow` / `payloadRow` 分开：那两个构造的是 Room 实体（测 DAO 用），这里构造的是
// 领域模型——`ClipStorageDataSource` 的接口只认后者，中间那次映射本身也在被测范围内。
// ---------------------------------------------------------------------------------------

/** 构造一条测试用领域元数据；默认就是「一条普通文本、未置顶」。 */
internal fun clipMeta(
    id: String,
    title: String = "title-$id",
    kind: ClipFilterType = ClipFilterType.TEXT,
    files: List<String> = emptyList(),
    application: SourceApplication? = null,
    firstCopiedAt: Long = 0L,
    lastCopiedAt: Long = 0L,
    numberOfCopies: Int = 1,
    pin: String? = null,
    payloadBytes: Long = 0L,
    contentKey: String = "key-$id",
    hasRecognizedText: Boolean = false,
    hasImage: Boolean = false,
): ClipMeta = ClipMeta(
    id = id,
    title = title,
    kind = kind,
    files = files,
    application = application,
    firstCopiedAt = firstCopiedAt,
    lastCopiedAt = lastCopiedAt,
    numberOfCopies = numberOfCopies,
    pin = pin,
    payloadBytes = payloadBytes,
    contentKey = contentKey,
    hasRecognizedText = hasRecognizedText,
    hasImage = hasImage,
)

/** 构造一条测试用领域载荷；什么都不传即「空载荷」（`isEmpty` 为真）。 */
internal fun clipPayload(
    text: String? = null,
    recognizedText: String? = null,
    contents: List<ClipboardContent> = emptyList(),
    legacyImage: ClipImage? = null,
): ClipPayload = ClipPayload(
    contents = contents,
    text = text,
    recognizedText = recognizedText,
    legacyImage = legacyImage,
)

// ---------------------------------------------------------------------------------------
// 加密相关的假实现
//
// `rekey` 的成功 / 失败分支要靠它们驱动：真实口令换钥在这条路径上不好造失败，
// 而「会话口令写回失败」正好是 `runCatching` 里会走到 `backup.restore()` 的那一支。
// ---------------------------------------------------------------------------------------

/** 只记状态的会话密钥。[failOnUpdate] 用来模拟写回失败，从而走到 `rekey` 的回滚分支。 */
internal class FakeSessionKey(
    override var encrypted: Boolean = false,
    private val failOnUpdate: Boolean = false,
) : SessionKey {
    /** 最近一次写回的口令；`null` 表示写回成了「已解密」。 */
    var passphrase: String? = null
        private set

    var updates: Int = 0
        private set

    override fun update(passphrase: String?) {
        updates++
        if (failOnUpdate) throw IllegalStateException("写回会话口令失败")
        this.passphrase = passphrase
    }
}

/** 只记账的备份；[snapshotSucceeds] 为假时模拟「备份没做成」，此时回滚也不该发生。 */
internal class FakeBackup(private val snapshotSucceeds: Boolean = true) : EncryptionBackup {
    var snapshots: Int = 0
        private set
    var restores: Int = 0
        private set
    var discards: Int = 0
        private set

    override fun snapshot(): Boolean {
        snapshots++
        return snapshotSucceeds
    }

    override fun restore() {
        restores++
    }

    override fun discard() {
        discards++
    }
}
