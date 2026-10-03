package com.qcmian.clipper.devtools.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.core.ui.theme.hintColor
import com.qcmian.clipper.devtools.api.DevToolHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/*
 * 「格式化」这一类工具共用的一块：顶部的操作栏，以及「输入停下就重排」的那条管线。
 *
 * 存在的理由是两个工具的这两块**逐行一样**——JSON 与 XML 各抄了一份操作栏、一份防抖重排、
 * 一份「结果过期」判断、一份状态栏文案。第一份写着方便，第二份抄着也快，到第三个格式化工具
 * （SQL / YAML / CSV，见 `ROADMAP.md`）时就会开始漂：有的判过期、有的不判，有的按模式禁用
 * 缩进、有的不禁用。收进这里之后，「怎么重排」「坏了怎么报」只有一处，工具只管自己怎么排。
 *
 * 不共用的部分刻意留着：输入 / 结果两个框怎么摆、占位文案、保存的文件名、以及各工具自己那
 * 几个开关——那些正是工具之间**该**不一样的地方。
 */

/**
 * 正文停下来多久才重新排版。
 *
 * 这个值只干一件事：让安静窗口比**连打时的击键间隔**长，中间那些多半非法的状态就来不及渲染出来。
 * 排版本身已经挪到后台调度器上，所以它不再是性能参数——拿它跟人打字的节奏比，而不是跟「算得多慢」比。
 *
 * 取 150ms 是往快的那头靠：正文多半是整块进来的（从剪贴板条目打开、或直接粘贴），那种情况没有
 * 中间态要压，延迟纯粹在拖后腿，而这正是主路径。手敲是次要路径，其击键间隔一般也在 150ms 以上。
 * 再往下调（100ms 上下）会开始落进快速连打的间隔里，于是每停一下就闪一次报错。
 */
internal const val DevToolFormatDebounceMillis = 150L

/**
 * 结果面板的排版方式：美化 / 压缩。
 *
 * 两个选项在各格式化工具里语义完全一样，因此共用一份，而不是每个工具各定义一个同形枚举——
 * 各写各的，选项文字迟早会分叉（一处「美化」、一处「格式化」）。
 */
internal enum class FormatMode(val title: String) {
    Pretty("美化"),
    Compact("压缩"),
}

/**
 * 一次实时排版的产物。
 *
 * [output] 与 [error] 互斥：成功时 [error] 为 `null`，失败时 [output] 为空——两者占结果框的
 * 同一块地方，同时留着只会让人以为结果对得上眼前内容。
 *
 * [isStale] 表示这一份还对不上当前输入（防抖的安静窗口里，或后台正在算）。它不只是一句提示：
 * 「复制 / 保存」必须跟着它一起禁用，否则那 150ms 里拷出去的是**上一份**内容的结果。
 */
internal class FormattedText(
    val output: String,
    val error: String?,
    val isStale: Boolean,
    /**
     * 丢弃眼前这一份结果，并让管线把当前正文重排一遍。
     *
     * 换了一份输入时调用（见 [rememberFormattedText]）：旧结果属于上一份内容，留着会让人以为
     * 它是对新内容算的。**先清空**是为了让防抖的安静窗口里不露出旧值，**再重排**是为了兜住
     * 「换了一条内容一字未改的记录」——那种情况下 `source` 没变，光清空会让结果框一直空着。
     */
    val reset: () -> Unit,
)

/**
 * 「输入一变就重排」的那条管线：防抖、后台计算、过期判断、状态栏上报，都在这里。
 *
 * 调用方只提供**怎么排**（[transform]）与**错了怎么说人话**（[errorMessage]），排版本身的调度、
 * 「上一次排的是哪份正文」这些记账都在内部完成。
 *
 * @param source 正文。
 * @param options 影响结果的其它状态（模式 / 缩进 / 开关）打包成一个值。它一变就**立刻**重算、
 *   不等防抖——换缩进、换模式都是点一下就定的事，让它们也等安静窗口，按钮就会显得发木。
 * @param transform 真正排版的那一步，在后台调度器上执行。返回 [Result] 而不是抛异常。
 * @param errorMessage 失败时把异常翻成人话；参数是这次的正文与那个异常（JSON 要按正文把偏移换成
 *   行列，XML 只要异常本身）。
 * @param debounceMillis 正文停下多久才重排；默认值见 [DevToolFormatDebounceMillis]。
 *
 * 换了一份输入时，由工具在灌入新正文的同一个副作用里调用返回值的 `reset()`。
 */
@Composable
internal fun rememberFormattedText(
    source: String,
    options: Any?,
    host: DevToolHost,
    transform: (String) -> Result<String>,
    errorMessage: (String, Throwable) -> String,
    debounceMillis: Long = DevToolFormatDebounceMillis,
): FormattedText {
    var output by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // 上一次真正排版用的正文。只有它变了才值得等防抖：换缩进、换模式都是点一下就定的事。
    var laidOutSource by remember { mutableStateOf<String?>(null) }
    // `reset()` 递增它：即便 `source` 一字未改（换了一条内容相同的记录），下面的 effect 也会重算一次。
    var revision by remember { mutableStateOf(0) }

    LaunchedEffect(source, options, revision) {
        // 本次要排的正文先落到局部：下面要跨一次挂起，回来之后再读状态可能已经是新值了。
        val text = source
        if (text.isBlank()) {
            output = ""
            error = null
            laidOutSource = null
            return@LaunchedEffect
        }
        if (text != laidOutSource) delay(debounceMillis)
        val result = withContext(Dispatchers.Default) { transform(text) }
        // 先记下「这次排的是哪份正文」，再落结果：记住的是已经算过的正文，不是刚拿到的那份。
        laidOutSource = text
        result.fold(
            onSuccess = {
                output = it
                error = null
            },
            onFailure = {
                // 失败时把结果清空、改成显示错误本身：两者占结果框的同一块地方，留着上一次的结果
                // 也看不见，却会让「复制结果」还能拷出一份与眼前内容不符的东西。
                output = ""
                error = errorMessage(text, it)
            },
        )
    }

    val isStale = source.isNotBlank() && source != laidOutSource

    // 行数 / 字符数与「排版中…」报到窗口底部的状态栏，不再占编辑区上方那一行。
    //
    // 放在 `LaunchedEffect` 里而不是直接调：`reportStatus` 写的是面板的状态，在组合期间写
    // 等于边读边写。键里带上 `output` 与 `error`，结果一落地就把数字换成结果那一侧的量。
    LaunchedEffect(source, output, error, isStale) {
        host.reportStatus(
            when {
                source.isBlank() -> null
                isStale -> "排版中…"
                output.isNotEmpty() -> "结果 ${output.lineCountOf()} 行 · ${output.length} 字符"
                error != null -> "输入 ${source.lineCountOf()} 行 · ${source.length} 字符"
                else -> null
            }
        )
    }

    return FormattedText(
        output = output,
        error = error,
        isStale = isStale,
        // 先清空再递增：清空让防抖窗口里不露出旧值，递增保证「正文没变」时也会重排一次。
        reset = {
            output = ""
            error = null
            laidOutSource = null
            revision++
        },
    )
}

/**
 * 格式化工具顶部的那条操作栏：左起「美化 / 压缩」分段控件、一道竖线、「缩进」取值格，再往右是
 * 工具自己的开关（键排序 / 属性排序…）。
 *
 * 之所以抽出来：这一段的**次序本身就是规则**——「多选一」在左、「取一个值」在中、「开关」在右，
 * 组与组之间用一道竖线断开（见 `DevToolWidgets` 顶部那段）。原先这条规则只写在注释里、两个工具
 * 各排一遍，加第三个格式化工具时必然分叉。收进这里之后，次序由代码保证，工具只往里放自己的开关。
 *
 * @param indent 缩进的取值。各工具用各自的类型（`JsonIndent` / `XmlIndent`），这里按 [T] 泛型接。
 * @param toggles 工具特有的开关，排在缩进之后。为 `null`（默认）时整组不出现，也就不会留下一条
 *   悬空的分隔线；开关之间用 [DevToolActionSpacer] 分隔。
 */
@Composable
internal fun <T> DevToolFormatBar(
    mode: FormatMode,
    onModeChange: (FormatMode) -> Unit,
    indent: T,
    indentOptions: List<T>,
    indentLabel: (T) -> String,
    onIndentChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    toggles: (@Composable () -> Unit)? = null,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        // 一、多选一：结果面板按哪种方式排。分段控件自带「这两项互斥、现在选的是它」。
        DevToolSegmentedControl(
            options = FormatMode.entries,
            selected = mode,
            optionLabel = { it.title },
            onSelect = onModeChange,
        )

        DevToolGroupDivider()

        // 二、取一个值：缩进。描边格子而不是实心按钮——它是「一个字段当前的取值」，点开才是一列选项。
        //
        // 缩进只影响「美化」（压缩根本没有换行），但这里**不**按模式禁用：它是用户的口味设置，
        // 想先设好再切回美化，没道理拦着；格子上一直显示着当前取值，点了也不会「没反应」。
        Text("缩进", fontSize = 12.sp, color = MaterialTheme.hintColor)
        DevToolActionSpacer()
        DevToolMenuButton(
            label = indentLabel(indent),
            options = indentOptions,
            selected = indent,
            optionLabel = indentLabel,
            onSelect = onIndentChange,
        )

        val extra = toggles
        if (extra != null) {
            // 三、开关：跟「美化 / 压缩」不是一个维度——那两个互斥，这些只是叠在上面的一层修饰。
            DevToolGroupDivider()
            extra()
        }
    }
}

/**
 * 这段文字占几行。
 *
 * 用 `count { it == '\n' } + 1` 而不是 `lines().size`：后者会为一份 1MB 的文档切出一整个字符串列表，
 * 而状态栏每敲一个键就要问一次。空串算 0 行——「0 行」比「1 行」诚实。
 */
internal fun String.lineCountOf(): Int = if (isEmpty()) 0 else count { it == '\n' } + 1
