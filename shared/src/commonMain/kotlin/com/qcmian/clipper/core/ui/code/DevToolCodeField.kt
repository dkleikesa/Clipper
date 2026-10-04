package com.qcmian.clipper.core.ui.code

/*
 * 这一包**刻意是 public**，尽管它只服务本项目内部两个调用方：预览面板（`:shared`）与开发者
 * 工具（`:devTools`）。原因是 Kotlin 的 `internal` 是**按模块**可见的：代码框搬进 `:shared`
 * 之后，`:devTools` 就成了「另一个模块」，`internal` 会把整套 API 挡在外面。
 *
 * 换句话说：这一包的边界不是「模块」，而是「本项目的界面层」。库外没有消费者，不存在 API
 * 稳定性负担；写在这里是为了让后来人不必再猜一次「为什么它没标 internal」。
 */

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 代码输入框——**业务层看到的唯一入口**。
 *
 * 它只做两件事：把参数收成 [CodeFieldSpec]，交给当前引擎去画（见 [CodeFieldEngine]）。
 *
 * 「统一抽象」在这里落地的样子，是为了让**后续换实现、加实现都不动业务层**：
 *  - 各工具（JSON / XML / Base64 / 时间戳 / 数学）只调这一个函数，从不知道背后是谁；
 *  - 参数只有 [CodeFieldSpec] 一份定义，两套实现同时看得见，加参数不会一边加一边漏；
 *  - 换实现是 `LocalCodeFieldEngine` 的事（面板状态栏那枚开关），改默认只动
 *    [DefaultCodeFieldEngine] 一行；加第三个实现只动 [CodeFieldEngine] 一处。
 *
 * 参数含义一律见 [CodeFieldSpec] 的 KDoc——**不在这里重复一遍**：重复就会有第二份说法，
 * 早晚与那边对不上。
 */
@Composable
fun DevToolCodeField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    editable: Boolean = true,
    placeholder: String = "",
    isError: Boolean = false,
    softWrap: Boolean = false,
    actions: @Composable () -> Unit = {},
    lineNumbers: Boolean = true,
    folding: Boolean = true,
    scan: (String) -> CodeStructure = ::scanJson,
    showLabel: Boolean = true,
    filePaste: (() -> String?)? = null,
) {
    LocalCodeFieldEngine.current.Content(
        CodeFieldSpec(
            label = label,
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            editable = editable,
            placeholder = placeholder,
            isError = isError,
            softWrap = softWrap,
            actions = actions,
            lineNumbers = lineNumbers,
            folding = folding,
            scan = scan,
            showLabel = showLabel,
            filePaste = filePaste,
        )
    )
}
