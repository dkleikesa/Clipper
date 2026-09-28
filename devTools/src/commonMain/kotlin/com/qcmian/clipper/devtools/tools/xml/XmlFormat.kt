package com.qcmian.clipper.devtools.tools.xml

/**
 * XML 的校验与排版。
 *
 * 解析器带平台属性——本工程只有一个 JVM 目标，用的是 JDK 自带的 `javax.xml`——因此和
 * `extractReadableText` 一样做 `expect`/`actual`：commonMain 里引用不到这些 API。
 *
 * 三个函数都是**纯函数**：给定同样的输入返回同样的结果，不抛异常（失败通过 `false` 或
 * [Result] 表达），因此探测与工具两条路径可以放心共用一个实现。
 */

/** [text] 是否是一份良构的 XML 文档。 */
internal expect fun isWellFormedXml(text: String): Boolean

/**
 * 美化：[indentWidth] 个空格一级缩进。
 *
 * 元素之间的空白文本节点会先被丢掉再重新缩进——否则源文件里已有的换行会被原样保留，
 * 结果就是「缩进叠缩进」。代价是混合内容里的空白不再原样保留，对开发者工具而言可以接受。
 */
internal expect fun formatXml(text: String, indentWidth: Int): Result<String>

/** 压缩：丢掉元素之间的空白与注释，压成一行。 */
internal expect fun minifyXml(text: String): Result<String>
