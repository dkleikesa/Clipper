package com.qcmian.clipper.devtools.tools.timestamp

import kotlinx.datetime.TimeZone

/**
 * 时区选择的数据：本机时区、常用项，以及 IANA 全量。
 *
 * 界面存的是时区 **ID 字符串**而不是 [TimeZone] 对象：ID 可比较、可显示，也便于以后存进设置。
 */
internal object TimestampZones {

    /** 置顶的常用项，列表很长时的快捷入口。本机时区另行排在最前。 */
    private val common = listOf(
        "UTC",
        "Asia/Shanghai",
        "Asia/Tokyo",
        "Asia/Kolkata",
        "Europe/London",
        "Europe/Paris",
        "America/New_York",
        "America/Los_Angeles",
        "Australia/Sydney",
    )

    /**
     * IANA 全量时区（几百个），按字典序。
     *
     * 来自 `TimeZone.availableZoneIds`——它在 JVM 上有完整表；万一某个平台拿不到，就退回常用项，
     * 而不是让整个列表空掉。初始化时算一次即可。
     */
    private val available: List<String> =
        runCatching { TimeZone.availableZoneIds.sorted() }.getOrDefault(emptyList())

    /** 本机时区的 ID。 */
    fun systemId(): String = TimeZone.currentSystemDefault().id

    /** 全部可选时区：本机在最前，其后是常用项，再后是全量（按字典序），去重。 */
    fun all(): List<String> = (listOf(systemId()) + common + available).distinct()

    /** 解析过的时区；列表要给几百个时区逐个算偏移，缓存一下免得同一个 ID 反复解析。 */
    private val cache = mutableMapOf<String, TimeZone>()

    /**
     * ID → [TimeZone]。
     *
     * 认不出的 ID 退回 UTC 而不是抛异常：ID 来自选择器（一定合法）、或未来的设置项，让界面因为
     * 一个存坏的字符串就崩掉不划算。
     */
    fun of(id: String): TimeZone =
        cache.getOrPut(id) { runCatching { TimeZone.of(id) }.getOrDefault(TimeZone.UTC) }
}
