# Clipper

一个使用 **Compose Multiplatform** 实现的剪贴板历史管理器，按 macOS 开源项目
[p0deje/Maccy](https://github.com/p0deje/Maccy) 的界面与交互 **1:1 复刻**，同一份 UI 代码运行在
**Android / iOS / Desktop (JVM) / Web (Wasm & JS)** 上。

## 功能

* 自动记录剪贴板历史（文本、图片、文件路径），识别**链接**与**十六进制颜色**
* 内容去重：重复复制同一内容时合并条目、累加复制次数并置顶
* 搜索：精确 / 正则 / 模糊 / 混合四种模式，命中片段按偏好高亮，0.2s 节流
* 置顶：置顶项固定在最上方或最下方并**常驻在滚动区之外**（Maccy 的 `HistoryListView`），分配字母快捷键，可在偏好设置里改键位/别名/删除
* 排序：按最后复制时间 / 首次复制时间 / 复制次数
* 右侧预览面板：工具栏（置顶、删除、复制识别文字）+ 内容 + 元信息（应用、尺寸、首次/上次复制时间、复制次数）；超长文本截断保护，图片解码走 LRU 缓存
* **来源应用**：记录复制来源的应用名，并从 `.app` 包中提取图标显示（列表行可选、预览必显）
* **图片文字识别（OCR）**：用 Vision / ML Kit 识别图片文字并作为条目标题，预览工具栏可一键把识别出的文字复制回剪贴板
* **全局热键**：`⇧⌘C` 在任何应用里呼出面板；按住快捷键（保持修饰键不松）即逐条循环选择、松开修饰键粘贴高亮项（Maccy 的 `PopupState.cycle`），面板已稳定显示时再按则把窗口移到鼠标位置
* **快捷键可自定义**：呼出 / 置顶 / 删除 / 预览四个快捷键都能在偏好设置里重新录制并复位
* **失焦自动收起**：桌面端面板失去焦点即隐藏（弹窗打开时不收起），与 Maccy 的 `FloatingPanel.resignKey` 一致
* **托盘交互**：⌥ 点击托盘图标暂停记录、⇧⌥ 仅暂停下一次，暂停时图标置灰（Maccy 的 `performStatusItemClick`）
* **预览滑出方向**：右侧空间不足时预览自动改到左侧（Maccy 的 `SlideoutController.computePlacement`）
* **忽略规则**：忽略正则、忽略应用（应用列表 + 系统应用选择器，显示应用名与图标，支持仅白名单）、忽略剪贴板类型（可一键恢复默认）
* 单条删除、清空未置顶、全部清除（带二次确认，可勾选“不再提示”）
* 存储：历史条数上限（置顶项不受限）、可分别开关文本/图片/文件入库、显示占用大小、剪贴板检查间隔可调
* 外观：弹窗位置（光标 / 菜单栏图标 / 应用窗口中心 / 屏幕中心 / 上次位置）、**弹窗屏幕（多显示器）**、置顶位置、菜单栏图标样式（4 种）与显示开关、显示页脚/搜索框/色块/特殊符号/应用图标、图片最大高度、窗口宽度与最大高度、**预览宽度（可拖拽分隔条调整）**
* 置顶项管理：改键位、改别名，纯文本项还可直接编辑内容；选中一行后按 `Delete` 即可删除（Maccy 的 `PinsSettingsPane.onDeleteCommand`）
* 高级：退出时清空历史、同时清空系统剪贴板、开机自启（macOS `SMAppService`）
* 弹窗尺寸：**默认**宽度固定、高度随内容增长直到屏幕底部；**用户手动拖动窗口后**固定为用户
  拖出的大小，不再随条目数变化（重启恢复自动）
* 深/浅色主题跟随系统

## 快捷键

与 Maccy 的 `KeyChord` 完全一致；非 macOS 平台上 `⌘` 对应 `Ctrl`。

| 操作 | 按键 |
| --- | --- |
| 呼出面板 / 循环选择 / 移到鼠标（全局） | `⇧⌘C`（可自定义；按住修饰键逐条循环、松开粘贴，面板已稳定显示时再按则把窗口移到鼠标位置） |
| 下一个 / 上一个 | `↓` `⇧↓` `⌃N` `⌃⇧N` `⌃J` / `↑` `⇧↑` `⌃P` `⌃⇧P` `⌃K` |
| 第一个 / 最后一个 | `⌘↑` `⌥↑` `⌃⌥P` `PageUp` / `⌘↓` `⌥↓` `⌃⌥N` `PageDown` |
| 复制选中项（并按设置粘贴） | `Enter`（未选中任何项时复制搜索词本身） |
| 快速选择 | `⌘1`…`⌘9`（未置顶）/ `⌘<字母>`（置顶项） |
| 置顶 / 取消置顶 | `⌥P`（可自定义） |
| 删除选中项 | `⌥⌫`（可自定义） |
| 清除未置顶 | `⌥⌘⌫` |
| 全部清除 | `⌥⇧⌘⌫` |
| 显示 / 隐藏预览 | `⌃Space`（可自定义） |
| 清空搜索 | `⌃U` |
| 删除搜索中的一个字符 / 一个词 | `⌃H` / `⌃W` |
| 偏好设置 | `⌘,` |
| 清空搜索 / 关闭 | `Esc`（有搜索词时先清空，搜索为空时关闭） |

页脚第一行会随按下的修饰键在「清除 `⌥⌘⌫`」与「全部清除 `⌥⇧⌘⌫`」之间切换，
列表行的快捷键角标也会随修饰键在 `⌘x` / `⌥x` / `⌘⇧x` 之间切换——与 Maccy 相同。

## 运行

```bash
# 桌面端
./gradlew :desktopApp:run

# Android
./gradlew :androidApp:assembleDebug

# Web（Wasm / JS）
./gradlew :webApp:wasmJsBrowserDevelopmentRun
./gradlew :webApp:jsBrowserDevelopmentRun

# iOS：用 Xcode 打开 iosApp 目录后运行
```

## 与 Maccy 的对应关系

| Maccy | Clipper |
| --- | --- |
| `HistoryItem` / `HistoryItemContent` | `data/model/ClipItem.kt` |
| `History` | `data/repository/ClipboardRepository.kt` + `ui/ClipboardViewModel.kt` |
| `Clipboard` | `data/source/ClipboardDataSource.kt` + 各平台实现 |
| `Search` / `Throttler` | `domain/search/ClipSearch.kt` / `ClipboardViewModel.updateQuery` |
| `Sorter` | `domain/sort/ClipSorter.kt` |
| `Storage` | `data/source/ClipStorageDataSource.kt` + 各平台实现 |
| `Defaults.Keys` | `settings/AppSettings.kt` |
| `GlobalHotKey` | `macos/MacGlobalHotKey.kt`（JNA + Carbon `RegisterEventHotKey`） |
| `ApplicationImage` / `ApplicationImageCache` | `macos/MacAppIcon.kt` + `NativeDataSource` |
| `NSWorkspace.frontmostApplication` | `macos/MacWorkspace.kt` |
| `HistoryItem.performTextRecognition()` | `macos/MacTextRecognition.kt`（Vision）/ `ios/IosTextRecognition.kt` / `mlkit/MlKitTextRecognition.kt` |
| `MenuIcon` | `desktopApp/desktop/ui/MenuIcons.kt`（4 种矢量图标） |
| `Popup` / `PopupPosition` | `ui/Popup.kt` / `ui/HistoryScreen.kt` |
| `KeyShortcut` / `KeyboardShortcutView` | `ui/KeyShortcut.kt` / `ui/components/ListItemRow.kt` |
| `KeyChord` / `KeyHandlingView` | `ui/HistoryKeyboard.kt` |
| `ModifierFlags` | `ui/KeyShortcut.kt` |
| `HistoryItemAction` | `domain/action/ClipAction.kt` |
| `HighlightMatch` / `PinsPosition` / `SearchVisibility` | `settings/AppSettings.kt` |
| `ColorImage` | `ui/ClipColors.kt` + `ui/components/HistoryRow.kt` |
| `ContentView` | `ui/HistoryScreen.kt` |
| `HeaderView` / `ListHeaderView` / `SearchFieldView` | `ui/components/SearchField.kt` + `ui/components/HistoryChrome.kt` |
| `ListItemView` / `ListItemTitleView` | `ui/components/ListItemRow.kt` |
| `HistoryItemView` / `HistoryListView` / `PinsView` | `ui/components/HistoryRow.kt` + `HistoryScreen` 的固定置顶区块 + `LazyColumn` |
| `HoverSelectionModifier` | `ClipboardViewModel.hoverHistory` |
| `MultipleSelectionListView` | `LazyColumn` |
| `FooterView` / `FooterItemView` / `FooterItem` | `ui/components/FooterRows.kt` |
| `SlideoutView` / `SlideoutContentView` | `ui/HistoryScreen.kt` 的 Row 布局 + `ui/components/PreviewSlideout.kt` |
| `PreviewItemView` / `ToolbarView` | `ui/components/PreviewPane.kt` |
| `ConfirmationView` | `ui/dialogs/ConfirmDialog.kt` |
| 六个设置面板 | `ui/dialogs/PreferencesDialog.kt` |
| `AppDelegate` 的托盘 / `clearOnQuit` / ⌥ 点击暂停 | `ui/ClipperController.kt` + `desktopApp/desktop/ui/ClipperTray.kt` + `DesktopShellViewModel` |
| `KeyboardShortcuts.Name` / `KeyboardShortcuts.Recorder` | `settings/ShortcutSpec` + `PreferencesDialog.ShortcutRow` |
| `PopupState`（opening / cycle / toggle） | `desktopApp/desktop/domain/PopupMode.kt` + `DesktopShellViewModel` + `ClipperController.requestOpen/requestCycle` |
| `FloatingPanel.resignKey()` 失焦关闭 | `desktopApp/desktop/ui/ClipperWindow.kt`（`WindowFocusListener`）+ `DesktopShellViewModel.onWindowLostFocus` |
| `LaunchAtLogin` | `macos/MacLaunchAtLogin.kt`（JNA 调 `SMAppService`） |
| `SlideoutController.startResize(.slideout)` / `computePlacement` | `ui/components/PreviewSlideout.kt` 中可拖拽的分隔条 + `HistoryScreen` 的预览左右翻转 |
| `ToolbarView` 的 `text.viewfinder` | `PreviewPane` 的「复制识别文字」按钮 |
| `AppDelegate.performStatusItemClick` 的 ⌥ / ⇧⌥ | `desktopApp/desktop/ui/ClipperTray.kt` 的 `onAction` + `DesktopShellViewModel.onTrayClicked` 读 `NSEvent.modifierFlags` |
| `NSRunningApplication.windowFrame` | `macos/MacWindow.kt`（JNA 调 `CGWindowListCopyWindowInfo`） |
| `IgnoreApplicationsSettingsView` 的应用选择器 | `macos/MacApplicationPicker.kt`（JNA 调 `NSOpenPanel`）+ `NativeDataSource.applicationName` |
| `IgnorePasteboardTypesSettingsView` 的 `Defaults.reset` | 偏好设置里的「恢复默认类型」按钮 |
| `ApplicationImageCache` | `ui/components/ImageCache.kt` + `NativeDataSource.applicationIcon`（JVM 侧带 bundle id 缓存） |
| `PreviewItemView.largeTextThreshold` / `LargeTextPreviewView` | `ui/components/PreviewPane.kt` 的 `LARGE_TEXT_LIMIT` |
| `NSWorkspace.open(_:)` | `NativeDataSource.openUrl`（JVM / Android / iOS / Web 各自实现） |

Maccy 依赖 `NSPasteboard` 的多表示（RTF / HTML / TIFF / 文件 URL）能力，跨平台无法完全等价，
因此 Clipper 只保留可移植的三种表示：**纯文本、编码后的图片、文件路径**。

## 原生能力实现方式

| 能力 | 桌面端 | iOS | Android | Web |
| --- | --- | --- | --- | --- |
| 全局热键 | JNA 调 Carbon `RegisterEventHotKey` | — | — | — |
| 来源应用 | JNA 调 `NSWorkspace.frontmostApplication` | — | — | — |
| 应用图标 | 读取 `.app` 包内 `.icns` 并抽出最大 PNG 块 | — | — | — |
| 图片 OCR | JNA 调 Vision `VNRecognizeTextRequest`（同步 `performRequests:`，避开 ObjC block） | `platform.Vision` | ML Kit `text-recognition` | — |
| 剪贴板类型 | AWT `DataFlavor` mime types | `UIPasteboard.pasteboardTypes` | `ClipDescription` mime types | `text/plain` |
| 开机自启 | JNA 调 `SMAppService`（未打包运行时会静默失败） | — | — | — |
| 屏幕数量 | AWT `GraphicsEnvironment.screenDevices` | — | — | — |
| 打开链接（About） | JNA 调 `NSWorkspace.openURL:`（非 macOS 回退到 AWT `Desktop.browse`） | `UIApplication.openURL:` | `Intent.ACTION_VIEW` | `window.open` |

新增的桌面端依赖只有 `net.java.dev.jna:jna-platform`；Android 增加 `com.google.mlkit:text-recognition`。

## 尚未实现（平台专属，无跨端等价能力）

| Maccy 功能 | 原因 |
| --- | --- |
| `Notifier`：系统通知与提示音 | Compose Desktop 的 `Tray` 未暴露通知接口 |
| `PasteStack`：多选连续粘贴 | Maccy 当前 `multiSelectionEnabled = false`，该功能实际也是关闭的 |
| 多选（⌘ 点击追加、⇧ 扩展选择、`extendToNext/Last/Previous/First`） | 同上，Maccy 默认关闭；Clipper 的删除只作用于当前选中项 |
| `FloatingPanel` / `VisualEffectView` / `GlassEffectView`：无边框浮动面板 + 毛玻璃 | Compose Desktop 无法做无边框可拖拽 + 系统材质；当前使用系统标准窗口 |
| `VoiceOver`：无障碍朗读 | 平台无障碍 API。注意 Maccy 的 `Accessibility.check()` 本身是空实现（只有 `guard`，没有任何动作），所以“权限引导”并不存在 |
| `SoftwareUpdater`：Sparkle 自动更新（含「自动检查更新」开关与「立即检查」按钮） | 需要打包产物与 appcast 基础设施；跨端无等价能力 |
| `Intents`（Get / Select / Delete / Clear） | App Intents 是 Apple 平台专属 |
| `KeyboardLayout`：Dvorak / bépo 等布局特判 | macOS 输入法布局 API |
| 富文本表示（RTF / HTML）及由此派生的 `clearFormatting` 语义 | 跨端无等价的富文本剪贴板表示；只保留纯文本 / 图片 / 文件路径三种 |
| Universal Clipboard（iCloud 剪贴板）识别与 `iCloud` 应用名 | macOS 专属 |
| MS Word 链接类型过滤、Chrome Remote Desktop / NetBeans 的剪贴板同步 workaround | macOS 专属 workaround |
| `x.nspasteboard.ModifiedType`（同会话内的“修改版”关联） | macOS 专属 |
| `statusItem` 精确位置与高亮、`statusItem.behavior = .removalAllowed`、Dock 图标点击（`applicationShouldHandleReopen`） | Compose Desktop 未暴露这些窗口 / 托盘能力 |
| `AppStoreReview`（`SKStoreReviewController`） | Apple 平台专属。注意 Maccy 中 `AppStoreReview.ask()` **从未被调用**，属死代码，并非真实功能差距 |

## 已知行为差异（跨端可做但未做到逐位一致）

下面这些项在两端都能正常工作，但实现方式或边界行为与 Maccy 不完全相同，属有意保留的差异：

| 项 | Maccy | Clipper |
| --- | --- | --- |
| 模糊搜索算法 | `Fuse`（bitap，**子序列**匹配，`threshold = 0.7`） | 自实现的**连续子串**最小编辑距离，阈值同样取 `0.7`。对 `mtg` → `meeting` 这类分散匹配，命中集合与排序可能与 Fuse 不同 |
| 预览超长文本 | 超过 1000 字符改用 `NSTextView`，仍完整显示 | Compose 没有「只布局可视区域」的文本 API，超过 20000 字符直接截断并提示 |
| 弹窗高度 | 用 `GeometryReader` **实测**内容高度（`readHeight` + `extraTopHeight/extraBottomHeight`） | 按行高**静态估算**（`Popup.itemHeight` / `imageMaxHeight + 10`）再加固定内边距 |
| 图片缩略图 | 按 `thumbnailImageSize`(340×`imageMaxHeight`) 生成列表缩略图、按屏幕尺寸生成预览图 | 解码原图后由 `heightIn(max)` 缩放；有 LRU 解码缓存，但未做降采样，超大图片内存占用更高 |
| 忽略正则 / 忽略剪贴板类型 | `List` + 「+」「−」逐条编辑 | 逗号分隔的文本框（**忽略应用**已改为列表 + 系统选择器） |
| 弹窗「上次位置」 | 保存相对屏幕的**比例锚点**（`windowPosition` 0…1，锚点为顶部中点） | 保存**绝对坐标**，分辨率或屏幕排列变化后不会自动贴合 |
| 自动粘贴时机 | 写入剪贴板后**立即**发送 `⌘V`（并设置事件抑制窗口） | 先隐藏面板，**延迟 160ms** 再发送 `⌘V` |
| 空内容过滤 | 逐个 pasteboard item 判断：带 `string` 类型、内容为空且非富文本 → 整条跳过 | 所有表示合并为一个快照后再判断，因此「空文本 + 图片」的组合会保留图片 |
| 置顶项内容编辑 | 富文本也可编辑，并给出 `RichTextEditWarning` 警告图标 | 仅纯文本可编辑，其它类型显示「无法编辑内容」 |
| 快捷键徽标 | 同时叠放 3 个 `KeyboardShortcutView`，按修饰键切换透明度 | 只渲染当前可见的那一个（视觉结果等价） |
| 悬停提示（tooltip） | 页脚项、预览工具栏按钮、多个设置项都有 `.help()` 提示 | Compose Multiplatform 的公共 API 没有桌面式 tooltip，全部省略 |
| 偏好设置窗口 | 独立的设置窗口，6 个分页（General / Storage / Appearance / Pins / Ignore / Advanced）+ 工具栏图标 | 单个可滚动对话框，按同样的 6 组分区组织，全部选项保留 |
| 置顶项删除 | `Table` 原生 selection + `onDeleteCommand` | 自绘行选中 + 冒泡阶段的 `onKeyEvent`；打开对话框时根节点先取得焦点，文本框编辑时会先消费 `Delete` 而不会误删 |
| `ImageCache` 并发 | 每个 item 在 `HistoryItemDecorator` 内持有，天然隔离 | 全局有界 LRU，仅在 Compose 组合线程访问；多窗口并发解码时存在理论上的竞态（未加锁） |
| 居中定位 | `NSRect.centered` 在居中结果上再 `+1.0` 偏移 | 直接居中，无偏移 |
| 「活动屏幕」的定义 | `NSScreen.main`（当前键盘焦点所在屏） | 鼠标所在屏 |
| 修饰符渲染 | `⌃⌥⇧⌘` 之外还渲染 `🌐`（function 键） | 只渲染 `⌃⌥⇧⌘` |
| 匹配高亮的选项顺序 | 颜色 / 加粗 / 斜体 / 下划线 | 加粗 / 斜体 / 下划线 / 背景（同一组选项，顺序不同） |
| 弹窗圆角与行高 | macOS 26 用 `7` / `24`，旧系统用 `4` / `22` | 固定 `4` / `22` |
| 应用图标缓存 | 监听 `.app` 的增删改，删除后 1 小时重试 | 永久缓存，不监听文件变化 |
| 自动化测试 | 有 `MaccyTests`（10 个用例）与 `MaccyUITests` | 暂无测试源集 |

## 平台差异

| 平台 | 剪贴板监听方式 | 图片 | 文件 | 自动粘贴 | 持久化位置 |
| --- | --- | --- | --- | --- | --- |
| Desktop (JVM) | 轮询 AWT 剪贴板（默认 500ms，可调） | 读/写 | 读/写 | `Robot` 发送 `⌘V`/`Ctrl+V` | Room（`~/.clipper/clipper.db`） |
| Android | `OnPrimaryClipChangedListener` | 读 | 读 | 不支持 | Room（`clipper.db`） |
| iOS | 轮询 `UIPasteboard.changeCount`（默认 500ms） | 读/写 | 读/写 | 不支持 | Room（`clipper.db`） |
| Web | 轮询 `navigator.clipboard`（默认 800ms） | 不支持 | 不支持 | 不支持 | `localStorage` |

已知限制：

* **Android 10+** 只在前台时才能收到剪贴板变更回调，其他应用中的复制会在 Clipper 回到前台时补采；
  图片内容无法写回剪贴板（需要 `FileProvider`），复制图片条目时会给出提示。
* **浏览器** 的 `clipboard.readText()` 需要安全上下文（HTTPS/localhost）且页面处于聚焦状态。
* **自动粘贴 / 全局热键** 依赖系统辅助功能权限：macOS 需要在「系统设置 → 隐私与安全性 → 辅助功能」中
  授权，否则合成的按键事件会被系统丢弃（与 Maccy 的行为一致）。
* **桌面端失焦即隐藏**：面板失去焦点会自动收起（有弹窗时不收起）。用托盘菜单或全局热键重新呼出。
* **桌面端关闭窗口**会隐藏到托盘图标，剪贴板监听继续运行；托盘菜单可重新显示、暂停/恢复记录。
* **开机自启**基于 `SMAppService`，需要以打包后的 `.app` 运行；用 `./gradlew :desktopApp:run` 直接运行时该开关不生效。

## 架构

按 Android 官方推荐的分层架构组织：**UI 层 / Domain 层 / Data 层**，依赖单向向下，
业务逻辑与界面彻底分离。

```
        ┌──────────────────────── UI 层 ────────────────────────┐
        │ HistoryScreen（纯渲染）  ←  ClipboardUiState           │
        │        │                                              │
        │        └── ClipboardUiAction ──►  ClipboardViewModel   │
        └───────────────────────────┬───────────────────────────┘
                                    │ 调用 UseCase
        ┌───────────────────────────▼───────────────────────────┐
        │ Domain 层：ClipSearch / ClipSorter / ClipAction        │
        │            CaptureClipboard / SelectClip / …UseCase    │
        └───────────────────────────┬───────────────────────────┘
                                    │ 依赖接口
        ┌───────────────────────────▼───────────────────────────┐
        │ Data 层：ClipboardRepository（接口 + 实现）            │
        │   ClipboardDataSource / ClipStorageDataSource /        │
        │   NativeDataSource（各平台 expect/actual 实现）        │
        └───────────────────────────────────────────────────────┘
```

* **单向数据流**：`HistoryScreen` 只渲染 `ClipboardUiState`，任何交互都只发
  `ClipboardUiAction`；`ClipboardViewModel` 是唯一拥有 `ClipboardUiState` 的地方，并把两块
  自洽的行为分别交给 `HistorySearchController`（搜索节流）与 `HistoryNavigationController`
  （列表 / 页脚导航）——它们直接读写 ViewModel 持有的同一份状态。
* **UI 层**：`ui/state/`（UiState / UiAction）、`ClipboardViewModel` 及其控制器、
  `HistoryScreen` 与无状态的 `components/`、`dialogs/`。
* **Domain 层**：纯 Kotlin 的搜索/排序/动作解析，以及承载业务规则的 UseCase
  （捕获去重、选中粘贴、置顶、清空、改设置、退出清理）。不依赖 Compose。
* **Data 层**：`ClipboardRepository` 接口 + `DefaultClipboardRepository`（用
  `StateFlow` 暴露状态，不再持有 Compose 状态），底层是三个数据源接口。
* **持久化**：Android / iOS / Desktop 统一使用 **Room（SQLite）**：`clip_history` 存历史，
  `app_settings` 存设置（单行 JSON）。驱动分别为 `BundledSQLiteDriver`，数据库文件位置由各
  平台源集提供；`ClipStorageDataSource` 因此改为挂起接口。Web 端暂用 `localStorage`
  （`androidx.sqlite:sqlite-web` 的 `WebWorkerSQLiteDriver` 需要调用方自行编译一个 worker
  入口，官方尚未发布可直接使用的 worker 产物）。
* **依赖注入**：`di/AppContainer` 手动装配，无需 DI 框架。
* **桌面宿主同样分层**：`desktopApp/desktop/domain`（`WindowPlacement.kt`、`WindowSizing.kt`）
  是窗口定位 / 尺寸的纯函数；`desktopApp/desktop/viewmodel`（`DesktopShellViewModel.kt`，官方
  `androidx.lifecycle.ViewModel` + `viewModelScope`）持有窗口可见性、位置、尺寸、全局热键状态机
  与焦点恢复。它是应用级单例，在组合根 `remember` 一次，供 `desktop/ui`（`ClipperWindow.kt`、
  `ClipperTray.kt`、`MenuIcons.kt`）共享——窗口与托盘只渲染并把平台事件转发给 ViewModel，
  它们之间的数据本就通过 `ClipperController.hostUiState` 这份宿主投影同步；`main.kt` 仅作组合根。

## 项目结构

```
shared/src/
  commonMain/
    data/
      local/       Room 数据库 / 实体 / DAO（clip_history、app_settings）
      model/       ClipItem、SourceApplication、ClipboardSnapshot
      source/      ClipboardDataSource / ClipStorageDataSource / NativeDataSource
      repository/  ClipboardRepository 接口 + 默认实现（StateFlow）
    domain/
      search/      ClipSearch（精确 / 正则 / 模糊 / 混合）
      sort/        ClipSorter
      action/      ClipAction 与修饰键映射
      usecase/     捕获、选中粘贴、置顶、删除、清空、改设置、退出 等用例
    ui/
      state/       ClipboardUiState / ClipboardUiAction
      ClipboardViewModel   状态所有者（协调下列控制器）
      HistorySearchController / HistoryNavigationController
      HistoryScreen        纯渲染的界面
      HistoryKeyboard      按键 → 动作映射
      components/ dialogs/ icons/ theme/
    di/            AppContainer（手动依赖注入）
    settings/      AppSettings
    util/          平台无关工具（base64、时间、JSON）
  jvmMain/         AWT 剪贴板、文件存储、macOS 原生层（JNA）
  androidMain/     ClipboardManager、SharedPreferences、ML Kit OCR
  iosMain/         UIPasteboard、NSUserDefaults、Vision OCR
  webMain/         Clipboard API 与 localStorage（JS 与 Wasm 共用）
androidApp/     Android 入口
desktopApp/     Desktop 入口：`main.kt` 组合根 + `desktop/{ui,viewmodel,domain}`
iosApp/         iOS 入口
webApp/         Web 入口
```
