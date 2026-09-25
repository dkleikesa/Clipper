package com.qcmian.clipper.host.cli

import com.qcmian.clipper.core.domain.action.ClipAction
import com.qcmian.clipper.core.domain.model.ClipMeta
import com.qcmian.clipper.core.domain.search.ClipSearch
import com.qcmian.clipper.core.domain.usecase.SelectResult
import com.qcmian.clipper.core.settings.SortOrder
import com.qcmian.clipper.di.AppContainer
import com.qcmian.clipper.protocol.CliAffectedView
import com.qcmian.clipper.protocol.CliCodec
import com.qcmian.clipper.protocol.CliCommand
import com.qcmian.clipper.protocol.CliErrorCode
import com.qcmian.clipper.protocol.CliListView
import com.qcmian.clipper.protocol.CliPingView
import com.qcmian.clipper.protocol.CliRequest
import com.qcmian.clipper.protocol.CliResponse
import com.qcmian.clipper.protocol.CliStatsView
import com.qcmian.clipper.protocol.DEFAULT_LIMIT
import com.qcmian.clipper.protocol.MAX_LIMIT
import com.qcmian.clipper.protocol.PROTOCOL_VERSION

/**
 * 把一次 CLI 请求翻译成对 [AppContainer] 的调用。
 *
 * 这是瘦客户端方案里 app 侧的全部内容：**它不重新实现任何业务规则**，只是把已有的
 * 仓库与用例暴露出去。因此 CLI 拿到的结果与界面里看到的永远一致——包括「重复复制会
 * 把它排到最前」这种细节。
 *
 * 所有成员都是挂起函数，因为仓库就是这么设计的（Room 在非 Android 平台上要求 DAO 是
 * 挂起的）。调用方负责提供协程上下文，见 [CliServer]。
 */
internal class CliRequestHandler(
    private val container: AppContainer,
    private val appVersion: String,
) {
    private val startedAtMillis: Long = System.currentTimeMillis()

    suspend fun handle(request: CliRequest): CliResponse = when (request.cmd) {
        CliCommand.PING -> CliCodec.success(
            CliPingView(
                appVersion = appVersion,
                protocolVersion = PROTOCOL_VERSION,
                uptimeMillis = System.currentTimeMillis() - startedAtMillis,
            )
        )

        CliCommand.LIST -> list(request)
        CliCommand.SEARCH -> search(request)
        CliCommand.GET -> get(request)
        CliCommand.COPY -> copy(request)
        CliCommand.PIN -> setPinned(request, pinned = true)
        CliCommand.UNPIN -> setPinned(request, pinned = false)
        CliCommand.DELETE -> delete(request)
        CliCommand.STATS -> CliCodec.success(stats())

        else -> CliCodec.failure(
            code = CliErrorCode.UNKNOWN_COMMAND,
            message = "不认识命令「${request.cmd}」",
            hint = "可用命令：${CliCommand.ALL.joinToString("、")}",
        )
    }

    // -------------------------------------------------------------------------------------
    // 读
    // -------------------------------------------------------------------------------------

    private fun list(request: CliRequest): CliResponse {
        val kind = request.kind?.let { raw ->
            CliViewMapper.kindFromName(raw) ?: return CliCodec.failure(
                CliErrorCode.BAD_REQUEST,
                "不认识的类型「$raw」",
                "只能是 text / image / file / richtext",
            )
        }

        val field = request.sort?.let { raw ->
            SortField.from(raw) ?: return CliCodec.failure(
                CliErrorCode.BAD_REQUEST,
                "不认识的排序字段「$raw」",
                "只能是 ${SortField.entries.joinToString(" / ") { it.wireName }}",
            )
        }

        val order = request.order?.let { raw ->
            parseOrder(raw) ?: return CliCodec.failure(
                CliErrorCode.BAD_REQUEST,
                "不认识的排序方向「$raw」",
                "只能是 desc / asc",
            )
        }

        var metas = allMetas()
        if (kind != null) metas = metas.filter { it.kind == kind }
        request.pinned?.let { onlyPinned -> metas = metas.filter { it.isPinned == onlyPinned } }
        if (field != null) metas = sortMetas(metas, field, order ?: SortOrder.DESCENDING)

        return page(metas, request.limit)
    }

    /**
     * 搜索**只匹配标题**，与界面首屏的行为一致（见 `ClipSearch`：它明说「不触碰载荷」）。
     *
     * 界面在用户滚到底之后才会继续去正文里找一轮；CLI 不做那一轮——它意味着把整份历史的
     * 正文按批读进来，对一次命令行调用来说代价太高。标题在存储里最长 1000 字符
     * （`ClipItem.MAX_TITLE_LENGTH`），覆盖了绝大多数「找上次复制的那段东西」的场景。
     */
    private fun search(request: CliRequest): CliResponse {
        val query = request.query
        if (query.isNullOrBlank()) {
            return CliCodec.failure(CliErrorCode.BAD_REQUEST, "缺少搜索词", "用法：clipper search <query>")
        }
        val hits = ClipSearch.search(query, allMetas())
        return page(hits.map { it.meta }, request.limit)
    }

    private suspend fun get(request: CliRequest): CliResponse {
        val id = request.id ?: return missingId()
        val item = container.repository.item(id) ?: return notFound(id)

        val format = request.format
        val exported = if (format != null) {
            ClipExporter.exportAttachment(item, format) ?: return CliCodec.failure(
                code = CliErrorCode.NOT_FOUND,
                message = "这条没有「$format」表示",
                hint = "它有：${ClipExporter.availableFormats(item).joinToString("、").ifBlank { "（没有附加表示）" }}",
            )
        } else {
            ClipExporter.exportImage(item)
        }

        return CliCodec.success(CliViewMapper.detail(item, exported))
    }

    private fun stats(): CliStatsView {
        val pinned = container.repository.pinned.value
        return CliStatsView(
            // 未置顶的条数从计数流里读，而不是 `unpinned.size`：那个列表只是已加载的前缀。
            total = pinned.size + container.repository.totalUnpinned.value,
            pinned = pinned.size,
            storageBytes = container.platform.storageBytes,
            paused = container.repository.settings.value.ignoreEvents,
        )
    }

    // -------------------------------------------------------------------------------------
    // 写
    // -------------------------------------------------------------------------------------

    /**
     * 写回系统剪贴板。
     *
     * 走的是界面同一个用例，因此「写回之后把那一条记为又一次复制、并排到最前」这件事
     * 在这里同样成立——CLI 复制过的东西，用户在界面上能立刻看到它在最前面。
     */
    private suspend fun copy(request: CliRequest): CliResponse {
        val id = request.id ?: return missingId()
        container.repository.meta(id) ?: return notFound(id)

        // `onHidePanel` 传空：CLI 没有面板需要让开。`ClipAction.COPY` 不合成粘贴按键，
        // 因此也不会去动用户当前聚焦的应用。
        val result = container.useCases.selectClip(listOf(id), ClipAction.COPY, onHidePanel = {})
        return when (result) {
            SelectResult.COPIED -> CliCodec.success(CliAffectedView(listOf(id)))
            SelectResult.IGNORED -> notFound(id)
            SelectResult.UNSUPPORTED -> CliCodec.failure(
                CliErrorCode.UNSUPPORTED,
                "这条内容无法写入系统剪贴板",
                "平台表示不了它的表示集合（例如某些只在粘贴板里声明、没有载荷的类型）",
            )
            // `COPY` 不粘贴，走不到；真走到了说明用例语义变了，如实报出来。
            SelectResult.PASTING -> CliCodec.failure(
                CliErrorCode.INTERNAL,
                "写回剪贴板时意外触发了粘贴",
                null,
            )
        }
    }

    private suspend fun setPinned(request: CliRequest, pinned: Boolean): CliResponse {
        val id = request.id ?: return missingId()
        container.repository.meta(id) ?: return notFound(id)
        container.repository.setPinned(listOf(id), pinned)
        return CliCodec.success(CliAffectedView(listOf(id)))
    }

    private suspend fun delete(request: CliRequest): CliResponse {
        val ids = request.ids.orEmpty().filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) {
            return CliCodec.failure(CliErrorCode.BAD_REQUEST, "缺少要删除的 id", "用法：clipper delete <id>…")
        }

        // 先过滤掉已经不存在的：调用方给的列表里混进一两个过期的 id 是常事（比如脚本里
        // 攒了一批 id 之后先删了其中几条），整批失败不如删掉还在的那些并如实回报。
        val existing = ids.filter { container.repository.meta(it) != null }
        if (existing.isEmpty()) {
            return CliCodec.failure(CliErrorCode.NOT_FOUND, "这些 id 都不存在", ids.joinToString("、"))
        }
        container.repository.delete(existing)
        return CliCodec.success(CliAffectedView(existing))
    }

    // -------------------------------------------------------------------------------------
    // 公共部分
    // -------------------------------------------------------------------------------------

    /**
     * 当前排序下的全部元数据：置顶区在前，未置顶紧随其后。
     *
     * 置顶永远在前，即使请求里指定了别的排序——这与界面一致：置顶是一个独立的区块，
     * 不是一个排序值。`--sort` 只作用于区块内部。
     *
     * 未置顶那一份是**完整**的：仓库初始化时按 `limit = total` 一次加载（见
     * `DefaultClipboardRepository`），因此这里搜的是整份历史，不是某个窗口。
     */
    private fun allMetas(): List<ClipMeta> =
        container.repository.pinned.value + container.repository.unpinned.value

    /** 统一处理截断与总数，让 `list` / `search` 的口径不可能不一致。 */
    private fun page(metas: List<ClipMeta>, requested: Int?): CliResponse {
        val limit = (requested ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val page = metas.take(limit)
        return CliCodec.success(
            CliListView(
                items = page.map(CliViewMapper::summary),
                total = metas.size,
                truncated = metas.size > limit,
            )
        )
    }

    /**
     * 按请求的字段与方向就地重排。
     *
     * 二级键用 id 而不是保持原序：同一毫秒复制进来的几条如果顺序随机，两次同样的调用
     * 会拿到不同的结果，脚本里就没法依赖它。
     */
    private fun sortMetas(metas: List<ClipMeta>, field: SortField, order: SortOrder): List<ClipMeta> {
        val byField = when (field) {
            SortField.LAST_COPIED -> compareBy<ClipMeta> { it.lastCopiedAt }
            SortField.FIRST_COPIED -> compareBy { it.firstCopiedAt }
            SortField.COPIES -> compareBy { it.numberOfCopies }
            SortField.SIZE -> compareBy { it.payloadBytes }
        }
        val stable = byField.thenBy { it.id }
        return metas.sortedWith(if (order == SortOrder.DESCENDING) stable.reversed() else stable)
    }

    private fun parseOrder(raw: String): SortOrder? = when (raw.lowercase()) {
        "desc", "descending" -> SortOrder.DESCENDING
        "asc", "ascending" -> SortOrder.ASCENDING
        else -> null
    }

    private fun missingId(): CliResponse =
        CliCodec.failure(CliErrorCode.BAD_REQUEST, "缺少 id", "用 clipper search 或 clipper list 拿到 id")

    private fun notFound(id: String): CliResponse =
        CliCodec.failure(CliErrorCode.NOT_FOUND, "没有 id 为「$id」的条目", "它可能已经被删除或被上限淘汰")

    /** `--sort` 的取值。放在这里而不是复用 `SortBy`：后者的名字是给设置页的中文标签用的。 */
    private enum class SortField(val wireName: String) {
        LAST_COPIED("lastCopiedAt"),
        FIRST_COPIED("firstCopiedAt"),
        COPIES("copies"),
        SIZE("size"),
        ;

        companion object {
            fun from(name: String): SortField? = entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) }
        }
    }
}
