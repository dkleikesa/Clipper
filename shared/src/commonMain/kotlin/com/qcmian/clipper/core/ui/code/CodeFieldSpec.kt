package com.qcmian.clipper.core.ui.code

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 代码框的**唯一一份参数契约**。
 *
 * 两个实现（[NativeCodeField] 与 [KodemirrorCodeField]）都只认它，于是：
 *  - 加一个参数只改这一处，两边同时看得见，不会「一边加了、另一边漏了」；
 *  - 业务层（各工具）看到的仍是 `DevToolCodeField(label = …, value = …)` 那套调用面，
 *    换实现不动它一行。
 *
 * **刻意不是 `data class`。** 里面装着 lambda 与 `@Composable` 插槽（[onValueChange]、
 * [actions]、[scan]、[filePaste]），而 lambda 的 `equals` 是引用相等——哪天有人拿它去当
 * `remember` 的 key，就会每帧都判成「变了」。换成普通类，从类型上断掉这个念头。
 *
 * 字段含义写在这里；**实现怎么落地**写在各自的实现文件里，不在这。字段的顺序即
 * `DevToolCodeField` 的参数顺序，两边对照着看不费劲。
 */
class CodeFieldSpec(
    /** 标题行左侧的框名；[showLabel] 为假时不用。 */
    val label: String,
    /** 真实文档。折叠与高亮只改显示，这个值始终是原样。 */
    val value: String,
    /** 用户编辑之后的正文。 */
    val onValueChange: (String) -> Unit,
    val modifier: Modifier = Modifier,
    /**
     * 这一格能不能编辑。**一个开关管到底**：焦点、插入光标、文字输入、点击落点、拖选，
     * 四件事都由它决定，不存在「只读但还有光标」这种半开状态。
     *
     * 为 `false` 时它是一个**纯展示件**（预览面板、各种结果框）：画面上没有插入点、拖不出选区、
     * 键盘输入不进去，只有滚动照常。两套引擎都必须完整遵守——KodeMirror 侧落到 `editable`
     * facet（光标 / 输入 / 手势 / 选区绘制四处都读它），原生侧落到 `BasicTextField.readOnly`
     * 与透明光标刷。
     */
    val editable: Boolean = true,
    /** 空内容时的占位提示。 */
    val placeholder: String = "",
    /**
     * [value] 本身是一条失败说明（而不是排好版的内容）：正文整篇改用错误色。
     *
     * 这样调用方可以像普通结果一样把错误交给同一个框，不必在框外另开一行提示。
     */
    val isError: Boolean = false,
    /**
     * 长行折到下一行，还是横向滚出去。默认**不折**：这是代码，一行一条记录，折行会把「一行」
     * 这个结构本身弄没，对照两份 JSON 时尤其误导。
     */
    val softWrap: Boolean = false,
    /**
     * 标题行右端那块地方，放只属于**这个框**的动作（输入框的「打开文件 / 清空」、结果框的
     * 「保存文件 / 复制」）。做成插槽而不是几个布尔开关：控件不该认识「打开文件」是什么，
     * 那是调用方与宿主之间的事（见 `DevToolFieldActions`）。
     */
    val actions: @Composable () -> Unit = {},
    /** 显示左侧行号。关掉后不占那一列；与 [folding] 同时为假时装订线整列消失。 */
    val lineNumbers: Boolean = true,
    /**
     * 允许折叠（折叠箭头与 `…` 占位符）。关掉后不折叠，高亮与括号配对照旧——给「本来就没有块
     * 可折」的输入（数学表达式、Base64 长串）用。
     */
    val folding: Boolean = true,
    /**
     * 扫描器：把正文拆成着色片段、可折叠区间与行起点。默认按 JSON 扫，XML 工具传 `::scanXml`，
     * 纯文本（数学表达式）传 `::scanPlain`。
     *
     * 只影响显示层（配色与折叠），与「合不合法 / 排成什么样」无关——那是各工具自己的解析器。
     * 两套实现都要求它在同一个调用点上**保持稳定**（各自只在建会话 / 建组合期取一次）。
     */
    val scan: (String) -> CodeStructure = ::scanJson,
    /**
     * 是否画自带的那行标题（左侧框名 + 右侧 [actions]）。
     *
     * 为 `false` 时整条标题行——连同 [actions]——都不出现：调用方把框名放到别处（例如左侧一列
     * 统一的标签），并自行安排原本挂在标题行上的动作。
     */
    val showLabel: Boolean = true,
    /**
     * 「粘贴文件」：按下粘贴键时先问它。返回文件**内容**就地插入；返回 `null` 表示剪贴板里不是
     * 文件，按系统默认粘贴。
     *
     * 拦截是必要的：系统对「复制的文件」只提供**文件名**这一种文本表示（实测见 `FinderCopyTest`），
     * 不拦的话文本框里永远只有文件名。为 `null` 时完全不插手。
     */
    val filePaste: (() -> String?)? = null,
)
