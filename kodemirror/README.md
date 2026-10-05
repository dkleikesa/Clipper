# :kodemirror（内联的第三方源码）

CodeMirror 6 的 Compose Multiplatform 移植，**按源码内联**进本仓库，不作为 Maven 依赖引入。

- 上游：<https://github.com/Monkopedia/kodemirror>
- 提交：`a85a5c10fdd89e2f3f762cbd07c4ce2971e15550`（`Bump version to 0.3.7 for release (#379)`，2026-09-20）
- 版本：`0.3.7`
- 许可：Apache-2.0（见本目录 `LICENSE` / `NOTICE`；其本身是 CodeMirror 6（MIT）的 Kotlin 移植）

## 为什么要内联而不是依赖

上游只发布到 Maven Central，桌面端被标注为「单测通过、视觉渲染轻度验证」。一旦在集成里撞到问题，
改依赖只能等上游发版或自己打 fork——内联之后直接改这里就能编、能跑、能验。

## 拷了什么

只拷了编辑框真正需要的 4 个模块（外加它们依赖的 2 个 lezer 模块），且**只拷 commonMain + jvmMain**。
拷进来时逐字节一致（当时用哈希逐模块核对过），此后只有下面 [本仓库补丁](#本仓库补丁) 里那几处改动：

| 上游模块 | 内联后的包 | 文件 | 说明 |
|---|---|---|---|
| `state` | `com.monkopedia.kodemirror.state` | 18 | 文档模型、事务、选区、Facet |
| `view` | `com.monkopedia.kodemirror.view` | 33 | `KodeMirror` 组合项、装饰、装订线、按键管线 |
| `language` | `com.monkopedia.kodemirror.language` | 9 | 折叠、括号配对、缩进 |
| `commands` | `com.monkopedia.kodemirror.commands` | 6 | `history()`、默认键位 |
| `lezer-common` | `com.monkopedia.kodemirror.lezer.common` | 6 | `syntaxTree` 等语法树基础 |
| `lezer-highlight` | `com.monkopedia.kodemirror.lezer.highlight` | 3 | 高亮 Tag（未用到，随 `language` 一起编译） |

**没拷**：`search` / `autocomplete` / `lint` / `lsp-client` / `collab` / `merge` / `vim` / `legacy-modes` /
20+ 个 `lang-*` / 17 个 `theme-*` / `samples` / `basic-setup`，以及全部测试与 `native`、`ios`、`macos`、
`wasmJs` 源集。

这 6 个包彼此自洽：`commonMain` 里没有任何指向未拷贝模块的 `import`；唯一的平台实现是
`view/Platform.jvm.kt`（剪贴板与按键），已放在 `src/jvmMain`。

## 本仓库补丁

**十处**，都用 `// <本仓库补丁>` 标出（`grep -rn "本仓库补丁" kodemirror/src` 可一次找全）。
除了源码，本仓库还在这个目录里加了**自己的五个测试**（`src/jvmTest`，见最后一节）——上游的测试
没拷进来，这五个不是上游的。

### 1. 竖向滚动条（`view/KodeMirror.kt`）

上游只画了横向那条——纵向它交给滚轮与光标自动滚动，功能上不缺、但没有任何
视觉提示；本仓库把 KodeMirror 当默认实现后，这就表现为「滚动条不见了」。
补法是照它横向那条的样子**画在编辑器内部**（不往外套、也不外露内部状态）：

  - 新的私有 `VerticalScrollbar`（几何 `verticalMetrics` / `verticalThumbOf`、拖拽锚点
    `verticalDragAnchor`、落点 `scrollToContentOffset`），以及 `ScrollbarThickness` /
    `ScrollbarMinThumbLength` / `ScrollbarThumbInset` / `ScrollbarCornerRadius` 四个常量
    （横竖两条共用，改一处两条一起变）；
  - 正文右端让出 `ScrollbarThickness` 那一列，免得滑块压住字；
  - 另外新增了 5 行 import（`Canvas`、`fillMaxSize`、`CornerRadius`、`PointerIcon`、`pointerHoverIcon`）。

  **落位必须按帧合并**：指针事件只写一个 `pending` 目标值，由 `snapshotFlow` 每帧取最后一个值
  落一次位。原因是这条滚动条的**每次落位都要让 `LazyListState` 重测一屏**——横向那条拖的是
  `ScrollState`，落位只是改一个浮点数，所以它可以每个事件都落，这条不行。踩过的两个坑：
  `draggable` + `scrollBy` 的增量会被下一次调用取消而丢掉；在指针事件里逐次同步落位
  （`dispatchRawDelta`）会让一帧内重测好几遍，**比不写这条滚动条还卡**。滚轮之所以顺，是因为
  它的事件率本身低于帧率，一帧顶多落一次。

  横向那条的滑块宽度也一并改过（原来画出来只有 6dp 且贴顶，现在与竖向同为 8dp 居中）。

  这一处**不是纯新增**：`EditorContent(...)` 的调用点被改写了（套进一个 `Row`、加上滚动条那一
  列），行被重排但逻辑未变。所以同步时不能只找新增块——按 `grep -rn "本仓库补丁"` 的输出逐块重打。

### 2. 光标闪烁（`view/KodeMirror.kt` + `view/SelectionDrawing.kt`）

上游画的是静态光标，编辑时看不出「光标在哪、是不是活的」。补法是给它一个明灭节奏：

- `KodeMirror.kt`：`caretOn` 加一个 `LaunchedEffect(session, state.selection)` 每 500ms 翻转；
  键里带 `state.selection`，所以光标一动就重新计时（先亮满半周期再开始闪，与系统输入框一致）。
  往下传的是**取值函数** `caretVisible: () -> Boolean`，不是 Boolean——传 Boolean 会让每半秒
  重组所有可见行，取值函数在绘制里才被调用，翻转只让这些行重绘。
- `SelectionDrawing.kt`：`drawSelectionOverlay` / `drawLineSelection` 多一个 `cursorVisible`
  参数（默认 `{ true }`，因此对既有调用方源码兼容），只在它说该亮时才画光标。

### 3. 双击选词 / 三击选行（`view/KodeMirror.kt`）

上游的点击只落光标，没有多击语义（`selectWord` / `selectLine` 是键位命令，要先落光标再扩展，
**两步**）。补法分两层：

- **判定**（与系统同一套）：间隔量的是「上一击**抬手** → 这一击**按下**」（`ViewConfiguration`
  `.doubleTapTimeoutMillis` 的注释就是这么定的），并带一个最小间隔防「极快两下」（取系统的
  `doubleTapMinTimeMillis`，40ms），落点相距不超过 slop。窗口是自定常量
  `TapRepeatIntervalMillis` = **500ms**（系统默认 300ms，本项目按需求放宽）。
- **选区**：`dispatchTapSelection` 在**一个事务**里直接算出最终选区——分两步会占掉两次撤销。

抬手那个事件在 `awaitTouchSlopOrCancellation` 里被丢掉了（未超过 slop 时它直接 return null），
所以另起一路 `pointerInput` **旁听**抬手（只看不消费，不影响任何别的手势），并且只把「没挪窝」
的那次抬手算作一次点击——拖选、长按选完的抬手都不该成为双击窗口的起点。到三击为止，第四下
重新从单击开始。

**触摸那条路不数连击**：手指「在长按判定之前就抬起」走的是另一个分支（那里拿不到抬手时刻）。
桌面端的点击都走鼠标那条路，触控板点击同样是鼠标事件，所以这不影响本项目；真要支持触摸，把
那个分支也接进 `dispatchTapSelection` 即可。

取词用上游 `EditorState.wordAt`；取行到**换行之前**为止（与平台一致：`findParagraphEnd` 给的就是
换行符的下标），光标因此落在**本行行尾**而不是甩到下一行。注意
`wordAt` 有一处不对称（上游如此）：它按 `pos` 左侧字符向前扩、按 `pos` 处字符向后扩，所以双击
落在词的右侧空白上会选中**左边那个词**——`TapSelectionTest` 把这个行为钉住了，别当成 bug 顺手改。

### 4. 鼠标指针形状（`view/KodeMirror.kt` + `view/Gutter.kt`）

上游一处都没设：鼠标移到正文上仍是箭头，看不出「这里能点字」。补法是**只在正文那个 Box** 上加
`PointerIcon.Text`——两条滚动条是它的兄弟节点，各自的 `PointerIcon.Default`（箭头）不受影响。
不要图省事设在编辑器根节点上：那会把两条滚动条也一起变成 I 形。

装订线是那个 Box 的**一部分**，于是行号与折叠箭头上也跟着显示 I 形——那里既不能落光标也不能
选中文本（`view/Gutter.kt` 给整列补了 `PointerIcon.Default`）。折叠那一列再由各自的
`clickable` 改成手型（子节点优先），成了「看得出与点得到一致」：鼠标进了折叠列就是手型。

### 5. 点折叠箭头 / 点占位符都展开（`language/Fold.kt` + `view/Gutter.kt` + `view/KodeMirror.kt`）

上游的箭头点击是「按这一行现查当前折叠状态」（`lineMarkerClick`），而 marker 与它所在的组合
比会话活得久：用户在箭头那一行上改一个字，会话就是新的了，旧 marker 指着的区间早已不存在——
点下去自然什么也不会发生。补法是把**区间随 marker 一起带下去**（`FoldGutterMarker(range)`），
点击时照着它派发 `unfoldEffect`。

这一项**整个删掉**了，而不是修一修继续用：`clickable` 是按 gutter 配置挂到**每一格**上的
（`GutterView`），与这一行有没有箭头无关——留着它，没有箭头的空格也会变成可点区域、悬停还亮一块
底色，看着就是平白多出来一个按钮。折叠 / 展开改由箭头自己接，网格里只有有箭头的那几格可点，
「看得出来」与「点得到」才对得上。

箭头那一格同时铺满整格（`fillMaxSize` + 居中的 chevron），不再只有 8×4dp 的箭头能点。

正文里那个 `…` 是另一条路：它是 `DecorationApplication` 注进正文的一个**字符**，没有节点可以挂
`clickable`（折叠的替换装饰带的 widget 从来没被画出来，见 `FoldWidget` 的说明——上游那套
`inlineWidgets` 只收 `WidgetDecoration`）。所以：

- `DecorationApplication` 把 `…` 在**渲染文本**里的偏移随行带出去（`TextLine.foldPlaceholderOffset`，
  从行尾往回数，因此不必关心它前面被制表符展开宽了多少）；
- `KodeMirror` 在正文那一层挂一层手势（`foldPlaceholderModifier`）：按
  `TextLayoutResult.getBoundingBox` 判断指针在不在它身上（左右各让 4dp，`FoldPlaceholderTapSlop`），
  在就**把光标设成手型**、点下去就换成文档偏移交给 `unfoldFoldAt`。

光标是**自己算出来再设**的，不是靠 `clickable` 那种「有节点的地方自动变手型」：`…` 只是正文里的一个
字符，没有节点可以挂 `clickable` 或 `pointerHoverIcon`。看与点**共用同一个命中判定**
（`foldPlaceholderHit`）——各写一份迟早会漂成「显示手型却点不动」。那一路只读事件不消费
（`PointerEventPass.Initial`），正文落光标、拖选照旧。

**先偏移再查区间**：区间会随编辑平移，而行里带的 `from/to` 是排版那一刻的旧值。

与编辑器根节点那套「按下即落光标」的手势不冲突：那一套在 Main 阶段**子节点优先**，这一个挂在正文
这一层、是它的子节点，命中就消费掉（这一次点击不当成「在正文上点了一下」），没命中就放它过去。

### 6. 折叠收成一行（`view/DecorationApplication.kt`）

被替换掉的那一段里**含换行**，所以它后面的正文在文档意义上已经与本行同属一行了。上游只把替换
起点那一行截断，随后跳到结束那一行**整行**渲染——于是 `{...}` 摊成两行（`{…` 一行、闭括号一行），
嵌套时连行尾的逗号也被甩到第二行。

补法按 CM6 的模型把结尾接上：把结束那一行的剩余部分（`replace.to` → 行尾）拼进同一行，并
`lineNum = endLine.number + 1` 跳过它。折叠起来的块因此显示成 `{…}`、`"a": {…},` 一行——
与原生实现一致（它用显示变换删掉换行，走的是同一个道理）。`FoldRowCollapseTest` 钉住这两条。

### 7. 折叠箭头改画 90° chevron（`language/Fold.kt`）

上游用两个**字符**当箭头（U+2304 展开 / U+203A 折叠），角度全看字体，实际渲染又窄又尖。
改成 `Canvas` 画：长边 8dp、短边 4dp、线宽 1.5dp（与原生实现的 `FoldChevron` 同一套尺寸），
两条边等长所以尖端是 90°，展开指下、折叠指右。列宽不受影响——折叠列宽度来自主题的
`customGutterWidth`，不是量 marker 量出来的。

### 8. 折行行的选区高亮按视觉行铺开（`view/SelectionDrawing.kt`）

上游把「一个文档行 = 一块矩形」写死了：左端取 `from` 的横坐标、右端取 `to` 的横坐标。不开折行
时两者同处一个视觉行，看不出问题；开了折行（Base64 结果框那类带折行开关的框）之后，一条超长行
在排版里是好几行，而 `getHorizontalPosition` 给的是**那个字符所在视觉行**的行内横坐标——夹在
中间的整行整行因此被漏掉。全选一条超长行时，右端取到的是**最后一个视觉行**的行内横坐标：屏幕上
只剩左侧一条窄带，看着像没选上，其实选区是全的（复制出来是全的）。

补法是把几何抽成一个纯函数 `lineSelectionRowRects`（`internal`，测试直接喂排版结果），按视觉行
逐行出块：起点行从 `from` 起、终点行到 `to` 止，中间那些行（以及选区延续到下一个文档行的末行）
铺到容器右边缘；纵向取排版结果给的行顶 / 行底，于是「只落在中间某个视觉行上」的选区也不会再按
整条文档行的高度画成一大块。`drawLineSelectionRange` 退化成把这份矩形画出来。

两个细节：终点行按**最后一个被选中的字符**（`to - 1`）取——`to` 是开区间端点，正好停在折行处时
它已经属于下一个视觉行了；不折行（`lineCount == 1`）时纵向仍按**这一层的高度**画，与上游逐像素
一致（层高可能大于排版行高）。

### 9. 只读会话改不动正文（`view/EditorSessionImpl.kt` + `view/EditorSession.kt`）

`editable` facet 的本意是「只读框改不动，但照样有光标、点得动、拖得出选区、复制得走」（见
`CodeFieldSpec.editable`）。上游只把**打字**挡在输入法那一层（`InputHandling` 里的插入兜底 +
`onValueChange`），而键位命令是直接派发事务的：只读框里按一下退格 / 删除 / ⌘X / ⌘V / ⌘Z，
正文真会被改掉——光标与选区一旦恢复可用，这条洞就是用户随手按得出来的。

补法是在 `EditorSessionImpl.dispatchTransaction` 这个**唯一落点**上加一条闸门：`editable` 为假
时，**改文档**的事务直接丢掉；选区事务与效果事务照常放行。不去给每条命令打补丁，是因为命令有
几十条（`commands/`），漏一条就是一个用户按得出来的洞，而这个落点漏不掉——`EditorSessionImpl.state`
也只在这里被写。

宿主换内容走 `EditorSession.setDoc`：那是**喂数据**，不是用户在改字，所以由它临时把
`programmaticDocChange` 打开（`try/finally` 恢复），只读框因此照样显示得出内容。`ReadOnlySessionTest`
钉住这几条：退格 / 剪切 / 插入改不动、选区照常、`setDoc` 照常、可编辑会话不受影响。

### 10. 输入法组字（`view/KodeMirror.kt`）

隐藏输入框每收到一次 `onValueChange` 就「把文本插进文档 + 清空自己」，这在**输入法组字**时是错的：
拼音（或假名）每敲一个字母都会触发一次 `onValueChange`，于是字母被当成已确认的正文逐字插进文档，
同时清空又把平台那一侧的组字状态一并抹掉——候选窗根本走不完，中文 / 日文打不出来。拉丁字母没有
组字过程，所以从前只在这类输入法上暴露。

补法两处，都落在 `EditorContent` 那个隐藏 `BasicTextField` 上：

- `onValueChange`：`TextFieldValue.composition` 非空（＝正在组字）时**原样保留**隐藏框的值，
  既不插入、也不清空，让候选窗继续；等组字结束（`composition` 为空）那一次再一次性插入。
  这一条先判，排在回灌抑制（`pendingEcho`）之前——组字来的永远是真实输入，不该被当成回声丢掉。
- `onPreviewKeyEvent`：组字期间**整个让开**（返回 `false`）。回车 / 空格 / 退格在默认键位里都有
  绑定（换行 / 删除），不让开就会被抢去改文档（回车会既确认候选、又插一个换行）。让开而不是
  吞掉，是因为这个隐藏框本身就是 `BasicTextField`——交给它按普通文本框那套组字逻辑走即可。

`NativeCodeField` 一侧不受影响：它的 `TextFieldValue` 一直带着 `composition`，也没有「清空」这个
动作——这正是不换实现、只用原生框时看不出这个 bug 的原因。

### 本仓库自己的测试

`src/jvmTest`（配 `build.gradle.kts` 里的 `jvmTest` 依赖）里有五个：

- `TapSelectionTest` 覆盖上面第 3 处：喂位置与连击数、读最终选区；
- `FoldClickTest` 覆盖第 5 处：折一段再点它，读「还剩没剩折叠区间」（箭头那一路给区间、正文那一路
  只给偏移，两条各有用例）；
- `FoldRowCollapseTest` 覆盖第 6 处：喂一条替换装饰、读渲染出来的行（行数 + 每行文本）；
- `SelectionRowRectsTest` 覆盖第 8 处：喂 `TextMeasurer` 真量出来的折行排版，读每个视觉行的高亮
  矩形。测试源集因此要带 `compose.desktop.currentOs`——`TextMeasurer` 背后是 skiko 的
  `FontCollection`，缺了它取字体解析器时抛 `LibraryLoadException`，报错完全不提「依赖缺失」；
- `ReadOnlySessionTest` 覆盖第 9 处：喂**真的命令**（`deleteCharBackward` 就是 Backspace 那一条、
  `clipboardCut` 就是 ⌘X 那一条）与事务，读「正文动没动、选区进没进状态」。

**光标闪烁、指针形状、箭头角度、手势判定都测不了**——前三个是画出来的（`FoldRowCollapseTest`
只验到「收成一行」，验不到画成什么样；`FoldClickTest` 只验到「点下去展开了」，验不到点得到多大），
最后一个要真实事件时钟，只能手点。第 8 处是个例外：它**错在几何**而不是错在画法，把几何抽成
纯函数之后就能拿真排版结果喂进去量了（画出来的那一步仍只有肉眼验——这次是临时组合一次、把
`captureToImage` 的 PNG 看一眼，没有留成用例）。

**唯一一条对付「画出来」的自动化用例**在 `:shared` 的 `ReadOnlyCodeFieldTest`：「只读框里拖一下
看得见选区」，两个引擎各跑一遍，判据是同一份组合拖动前后的**像素差**（选区不进语义树，只有画出来
才算数；那一版回归正是状态里选上了、屏幕上什么都没有）。只读框里的光标同样只有肉眼验过——它的
明灭半周期是 500ms，拿它当判据的用例会随时钟漂。

**点正文里那个 `…`** 这条端到端路径在 `:devTools` 的 `KodemirrorCodeFieldTest` 里（那里有真的
组合与手势注入）：折起来、横扫正文最左端、找到能展开的那一点。命中区域的**大小**它同样验不了
（那是几个像素的容差），但「画出来了、点得着、只展开一次」三条都钉住了。

## 与上游同步

升级就是「按上表把对应目录覆盖一遍」，**然后把这几处补丁重新打一次**（覆盖 `view` 会连
`SelectionDrawing.kt` 里那一处一起冲掉）：

```sh
SRC=/path/to/kodemirror   # 上游检出
cd $SRC && git checkout <tag>
for m in state view language commands; do
  cp -R $SRC/$m/src/commonMain/kotlin/com/monkopedia/kodemirror/$m \
        kodemirror/src/commonMain/kotlin/com/monkopedia/kodemirror/
done
cp -R $SRC/lezer-common/src/commonMain/kotlin/com/monkopedia/kodemirror/lezer/common \
      kodemirror/src/commonMain/kotlin/com/monkopedia/kodemirror/lezer/
cp -R $SRC/lezer-highlight/src/commonMain/kotlin/com/monkopedia/kodemirror/lezer/highlight \
      kodemirror/src/commonMain/kotlin/com/monkopedia/kodemirror/lezer/
cp $SRC/view/src/jvmMain/kotlin/com/monkopedia/kodemirror/view/Platform.jvm.kt \
   kodemirror/src/jvmMain/kotlin/com/monkopedia/kodemirror/view/
```

```sh
grep -rn "本仓库补丁" kodemirror/src   # 同步后按输出把这几处补丁重新打上
```

**约定：尽量不在这个目录里改代码。** 集成侧的适配（主题映射、把本项目的扫描器接成高亮装饰、
参数契约）都写在 `:devTools` 的 `ui/components/code/` 下。确实必须改这里时，请在补丁处加一行
`// <本仓库补丁> 原因` 注释——它既是给下一次同步的提示，也是「这一处不是上游的代码」的凭据。
