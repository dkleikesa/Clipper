package com.qcmian.clipper.devtools.tools.hash

/**
 * 摘要字节的**呈现方式**。
 *
 * 摘要是同一串字节，写成什么字符串取决于要喂给谁：
 *  - [Hex]：通用形态，日志、对比表、`sha256sum`、Docker digest 都用它；
 *  - [Base64]：SRI（`sha384-…`）、JWK、Java 里 `Base64.getEncoder()` 的那一套；
 *  - [Base64Url]：JWT、URL 里用的形态。
 *
 * [title] 是分段控件上的显示名。
 */
internal enum class HashEncoding(val title: String) {
    Hex("十六进制"),
    Base64("Base64"),
    Base64Url("Base64 URL"),
}
