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
拷进来时逐字节一致（当时用哈希逐模块核对过），此后只有下面 [本仓库补丁](#本仓库补丁) 里那一处改动：

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

**只有一处**，在 `view/KodeMirror.kt`，用 `// <本仓库补丁>` 标出（`grep -rn "本仓库补丁" kodemirror/src` 可一次找全）：

- **竖向滚动条**。上游只画了横向那条——纵向它交给滚轮与光标自动滚动，功能上不缺、但没有任何
  视觉提示；本仓库把 KodeMirror 当默认实现后，这就表现为「滚动条不见了」。
  补法是照它横向那条的样子**画在编辑器内部**（不往外套、也不外露内部状态）：
  - 新的私有 `VerticalScrollbar`（几何 `verticalMetrics` / `verticalThumbOf`、拖拽锚点
    `verticalDragAnchor`、落点 `scrollToContentOffset`），以及 `ScrollbarThickness` /
    `ScrollbarMinThumbLength` 两个常量（取值与横向那条一致）；
  - 正文右端让出 `ScrollbarThickness` 那一列，免得滑块压住字；
  - 另外新增了 5 行 import（`Canvas`、`fillMaxSize`、`CornerRadius`、`PointerIcon`、`pointerHoverIcon`）。

  **落位必须按帧合并**：指针事件只写一个 `pending` 目标值，由 `snapshotFlow` 每帧取最后一个值
  落一次位。原因是这条滚动条的**每次落位都要让 `LazyListState` 重测一屏**——横向那条拖的是
  `ScrollState`，落位只是改一个浮点数，所以它可以每个事件都落，这条不行。踩过的两个坑：
  `draggable` + `scrollBy` 的增量会被下一次调用取消而丢掉；在指针事件里逐次同步落位
  （`dispatchRawDelta`）会让一帧内重测好几遍，**比不写这条滚动条还卡**。滚轮之所以顺，是因为
  它的事件率本身低于帧率，一帧顶多落一次。

  这一处**不是纯新增**：`EditorContent(...)` 的调用点被改写了（套进一个 `Row`、加上滚动条那一
  列），行被重排但逻辑未变。所以同步时不能只找新增块——按 `grep -rn "本仓库补丁"` 的输出逐块重打。

## 与上游同步

升级就是「按上表把对应目录覆盖一遍」，**然后把这个补丁重新打一次**：

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
grep -rn "本仓库补丁" kodemirror/src   # 同步后按输出把这处补丁重新打上
```

**约定：尽量不在这个目录里改代码。** 集成侧的适配（主题映射、把本项目的扫描器接成高亮装饰、
参数契约）都写在 `:devTools` 的 `ui/components/code/` 下。确实必须改这里时，请在补丁处加一行
`// <本仓库补丁> 原因` 注释——它既是给下一次同步的提示，也是「这一处不是上游的代码」的凭据。
