package com.qcmian.clipper.devtools.ui.components.code

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * 代码区的配色。
 *
 * **两套实现共用这一份**：原生实现把它交给 [CodeVisualTransformation]（`VisualTransformation`
 * 上色），KodeMirror 那一侧把它译成 `EditorTheme` 与装饰器（见 `rememberKodemirrorTheme`、
 * `codeHighlight`）。所以它放在这个独立文件里，而不是挂在任何一个实现名下——
 * 「两套实现看起来是同一个框」，靠的就是这里只有一份色值。
 */
internal data class CodeColors(
    val editorBackground: Color,
    val key: Color,
    val string: Color,
    val number: Color,
    val constant: Color,
    val punctuation: Color,
    /** 注释与 XML 的注释 / CDATA / 声明。 */
    val comment: Color,
    val foldPlaceholder: Color,
    val foldPlaceholderBackground: Color,
    val gutterDivider: Color,
    /** 光标所在那一行的整行底纹。 */
    val currentLineBackground: Color,
    /** 光标停在某个括号上时，与它配对的那两个括号的底纹。 */
    val bracketBackground: Color
)

/**
 * IntelliJ Light 的语法配色。
 *
 * 用固定色值而不是从 Material 色板派生：这套配色的意义就在于「看起来像 IDEA」，
 * 派生出来的近似色反而会四不像。取值对应 IDEA 的 role——键 = field/property、
 * 常量 = keyword、标点 = 正文色。
 *
 * 浅色这一侧**保持原样**，因为它本来就与应用的色板处得来：编辑区底色取纯白，正是应用
 * `surface` 的值，而它比 `background`（`#F5F5F7`）亮一档——「纸比桌面亮」的关系成立。
 */
private val IdeaLightCodeColors = CodeColors(
    editorBackground = Color(0xFFFFFFFF), // IDEA 的代码区就是纯白
    key = Color(0xFF7A3E9D), // field / property（截图里 parser、pretty、compact 那个紫）
    string = Color(0xFF067D17),
    number = Color(0xFF1750EB),
    constant = Color(0xFF0033B3), // true / false / null，走 keyword 蓝
    punctuation = Color(0xFF000000),
    comment = Color(0xFF8C8C8C), // IDEA 的注释灰
    foldPlaceholder = Color(0xFF8C8C8C),
    foldPlaceholderBackground = Color(0x14000000),
    gutterDivider = Color(0xFFE0E0E0),
    // 当前行用中性灰而不是浅蓝：白底上一块淡蓝会与选中态 / 主色按钮撞在一起，像第二层选区。
    currentLineBackground = Color(0x0F1B1B1F),
    // 配对括号用主色底纹：与界面里其他蓝色强调同族，一眼认得出「这两个是一对」。
    bracketBackground = Color(0x330A84FF),
)

/**
 * 深色下的语法配色，**从应用自己的色板派生**，而不是照搬 Darcula。
 *
 * 原先用的是 Darcula 原色，问题出在底色：`#2B2B2B` 是一块偏暖、偏绿的灰，而应用的面板底色是
 * `#1B1B1E`、窗口内的 chrome 是 `#26262A`——都是带一点蓝的中性灰。三种灰摆在一起，编辑区就成了
 * 整个窗口里唯一的外来物：它比周围亮，还偏绿。语法色同样打架，Darcula 的键紫 `#9876AA` 与字符串
 * 橄榄绿 `#6A8759` 跟强调色 `#0A84FF` 不是一个体系。
 *
 * 现在这套：
 *  - 底色 `#232328` 仍在应用的蓝灰族里，且**比 `background` 亮一档**——浅色下「纸比桌面亮」的
 *    关系在深色下同样成立，两套主题不会一个凹一个凸。
 *  - 键色取 `#7AA2F7`，与强调色 `#0A84FF` 同族，选中态、按钮与语法高亮因此像一套东西。
 *  - 字符串、数字、常量挑同族的低饱和色，既分得开又不与蓝色抢。
 *  - 标点直接用应用的 `onSurfaceVariant`（`#B4B4BD`），正文与界面文字同色。
 */
private val AppDarkCodeColors = CodeColors(
    editorBackground = Color(0xFF232328),
    key = Color(0xFF7AA2F7),
    string = Color(0xFF9ECE6A),
    number = Color(0xFFFF9E64),
    constant = Color(0xFFBB9AF7),
    punctuation = Color(0xFFB4B4BD),
    comment = Color(0xFF767687), // 比正文标点更暗一档的蓝灰，退到背景里去
    foldPlaceholder = Color(0xFF8E8E93),
    foldPlaceholderBackground = Color(0x33B4B4BD),
    gutterDivider = Color(0xFF35353B), // 应用的 surfaceVariant
    // 深底上抬一档白，比浅色那边更明显一点才看得出（深色下对比本来就弱）。
    currentLineBackground = Color(0x14FFFFFF),
    bracketBackground = Color(0x400A84FF),
)

/**
 * 当前主题该用哪套语法配色。
 *
 * 模块内可见：其它工具想跟编辑框用同一块底色（例如单行输入框）时，取它的 `editorBackground`
 * 即可，不必自己再挑一个「差不多的灰」。
 */
@Composable
internal fun rememberCodeColors(): CodeColors =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        AppDarkCodeColors
    } else {
        IdeaLightCodeColors
    }
