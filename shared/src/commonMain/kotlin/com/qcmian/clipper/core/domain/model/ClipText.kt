package com.qcmian.clipper.core.domain.model

/**
 * 一条**正文候选**：它属于哪个条目，以及参与全文搜索的那一段文字。
 *
 * 为什么不合并成 `Map<String, String>`：同一个条目可能带着两段互不相干的正文——用户复制的
 * 正文与图片识别出的完整原文。它们是两次独立的匹配，各有各的分数与高亮区间；拼成一个串会
 * 同时污染位置分（后一段永远靠后）和高亮（区间跨过头尾交界处仍会命中）。
 *
 * 它只出现在全文搜索的读路径上：正文不进常驻内存，按批读出来、匹配完就丢。
 */
data class ClipText(val id: String, val text: String)
