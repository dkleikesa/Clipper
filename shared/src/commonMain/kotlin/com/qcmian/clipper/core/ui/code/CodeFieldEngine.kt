package com.qcmian.clipper.core.ui.code

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * 代码框的实现清单。**换实现、加实现都只动这个文件**——业务层与 [CodeFieldSpec] 都不用碰。
 *
 * 每个实现提供一个 [Content]：把同一份契约画出来。把「有哪些实现」与「怎么画」一起绑在枚举上，
 * 于是 [DevToolCodeField] 里那个 `when` 就不需要了——多一个实现只是多一个枚举项，而编译器会强制
 * 它把 [Content] 实现出来（漏了编不过，不是运行时才发现）。
 */
enum class CodeFieldEngine(val label: String) {
    /**
     * 原生 `BasicTextField` + 自绘装订线 / 高亮 / 折叠。光标、选区、输入法全在平台控件那一层，
     * 行为最稳；本项目的自绘滚动条挂在它的滚动状态上。
     */
    Native("原生") {
        @Composable
        override fun Content(spec: CodeFieldSpec) = NativeCodeField(spec)
    },

    /**
     * 内联进来的 CodeMirror 6 移植（见 `:kodemirror` 模块）。按行虚拟化，行号、折叠箭头、
     * 括号配对由它自己画与管；代价是自带一整套主题与滚动，本项目的自绘滚动条接不上去。
     */
    Kodemirror("KodeMirror") {
        @Composable
        override fun Content(spec: CodeFieldSpec) = KodemirrorCodeField(spec)
    };

    /**
     * 把 [spec] 画出来。
     *
     * 这是两套实现之间**唯一的约定**：谁实现了它，谁就得把 [CodeFieldSpec] 里那份契约完整地
     * 兑现（哪一项在自己的技术路线下确实做不到，要写在实现文件的注释里，不能默默吞掉）。
     */
    @Composable
    abstract fun Content(spec: CodeFieldSpec)
}

/** 下一种实现（循环）。与侧边栏那个开关同一个手感：点一下就换一档。 */
fun CodeFieldEngine.next(): CodeFieldEngine =
    CodeFieldEngine.entries[(ordinal + 1) % CodeFieldEngine.entries.size]

/**
 * 默认用哪一套实现——**唯一的出处**：「没人提供局部值」时的兜底与面板开关的初值都取它，
 * 想整体换默认只改这一行。
 *
 * 取 [Kodemirror]：所有工具的输入框与结果框一起跑在新实现上。它是被内联进本仓库的第三方源码
 * （见 `:kodemirror` 的 README），几处已知差别都写在 `KodemirrorCodeField` 的注释里；验收期间
 * 想回到原实现对照，点面板状态栏右下角那枚开关即可，不必改代码。
 */
val DefaultCodeFieldEngine = CodeFieldEngine.Kodemirror

/**
 * 当前生效的实现。由 `DevToolsPanel` 在面板顶层提供，面板里那枚引擎开关改的就是它。
 *
 * 默认取 [DefaultCodeFieldEngine]：没人提供时也渲染出与面板一致的实现，测试与预览因此不会
 * 「面板里看到的是一套、单独渲染时是另一套」。
 */
val LocalCodeFieldEngine = compositionLocalOf { DefaultCodeFieldEngine }
