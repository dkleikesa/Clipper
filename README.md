# Clipper

一个使用 **Compose Multiplatform** 实现的剪贴板历史管理器，参照 macOS 开源项目
[Maccy](https://github.com/p0deje/Maccy) 的功能与交互设计，同一份 UI 代码运行在
**Android / iOS / Desktop (JVM) / Web (Wasm & JS)** 上。

## 功能

* 自动记录剪贴板历史（文本、图片、文件路径），并识别 **链接**、**十六进制颜色** 等类型
* 内容去重：重复复制同一内容时合并条目、累加复制次数并置顶
* 搜索：Exact / Regex / Fuzzy / Mixed 四种模式，命中片段高亮
* 置顶（Pin）：置顶项固定在最上方或最下方，并分配字母快捷键
* 排序：按最后复制时间 / 首次复制时间 / 复制次数
* 单条删除、清空未置顶、全部清空（带二次确认）
* 历史条数上限（置顶项不受限制）
* 暂停采集 / 仅忽略下一次复制
* 忽略正则：匹配的内容不入库
* 复制回系统剪贴板；桌面端支持「粘贴到上一个应用」
* 深/浅色主题跟随系统

## 快捷键

| 操作 | 按键 |
| --- | --- |
| 搜索 | 直接输入 |
| 上/下选择 | `↑` / `↓` |
| 复制选中项（并按设置粘贴） | `Enter` |
| 快速选择第 1–9 项 | `1`…`9`（搜索框为空时） |
| 置顶 / 取消置顶 | `⌘/Ctrl + P` 或 `⌥ + P` |
| 删除选中项 | `Delete`（搜索框为空时 `Backspace`） |
| 清空未置顶 | `⌘/Ctrl + Delete` |
| 全部清空 | `⌘/Ctrl + Shift + Delete` |
| 打开偏好设置 | `⌘/Ctrl + ,` |
| 清空搜索 | `Esc` |

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
| `HistoryItem` / `HistoryItemContent` | `model/ClipItem.kt` |
| `History` | `core/ClipboardRepository.kt` |
| `Clipboard` | `core/ClipboardPlatform.kt` + 各平台实现 |
| `Search` | `core/ClipSearch.kt` |
| `Sorter` | `core/ClipSorter.kt` |
| `Storage` | `core/ClipStorage.kt` + 各平台实现 |
| `Defaults.Keys` | `settings/AppSettings.kt` |
| `ContentView` / `HistoryListView` | `ui/HistoryPanel.kt` |
| `HeaderView` / `SearchFieldView` | `ui/components/SearchField.kt` |
| `ListItemView` / `HistoryItemView` | `ui/components/ClipRow.kt` |
| `FooterView` / `FooterItem` | `ui/components/FooterView.kt` |
| 偏好设置窗口 | `ui/dialogs/PreferencesDialog.kt` |

Maccy 依赖 `NSPasteboard` 的多表示（RTF/HTML/TIFF/文件 URL）能力，跨平台无法完全等价，
因此 Clipper 只保留可移植的三种表示：**纯文本、编码后的图片、文件路径**。

## 平台差异

| 平台 | 剪贴板监听方式 | 图片 | 文件 | 自动粘贴 | 持久化位置 |
| --- | --- | --- | --- | --- | --- |
| Desktop (JVM) | 500ms 轮询 AWT 剪贴板 | 读/写 | 读/写 | `Robot` 发送 `⌘V`/`Ctrl+V` | `~/.clipper/*.json` |
| Android | `OnPrimaryClipChangedListener` | 读 | 读 | 不支持 | `SharedPreferences` |
| iOS | 500ms 轮询 `UIPasteboard.changeCount` | 读/写 | 读/写 | 不支持 | `NSUserDefaults` |
| Web | 800ms 轮询 `navigator.clipboard` | 不支持 | 不支持 | 不支持 | `localStorage` |

已知限制：

* **Android 10+** 只在前台时才能收到剪贴板变更回调，其他应用中的复制会在 Clipper 回到前台时补采；
  图片内容无法写回剪贴板（需要 `FileProvider`），复制图片条目时会给出提示。
* **浏览器** 的 `clipboard.readText()` 需要安全上下文（HTTPS/localhost）且页面处于聚焦状态。
* **自动粘贴** 依赖系统辅助功能权限：macOS 需要在「系统设置 → 隐私与安全性 → 辅助功能」中授权，
  否则合成的按键事件会被系统丢弃（与 Maccy 的行为一致）。
* 桌面端关闭窗口会隐藏到托盘图标，剪贴板监听继续运行；点击托盘图标可重新显示。

## 项目结构

```
shared/src/
  commonMain/   模型、仓库、搜索、排序、持久化接口、全部 UI
  jvmMain/      AWT 剪贴板与文件存储
  androidMain/  ClipboardManager 与 SharedPreferences
  iosMain/      UIPasteboard 与 NSUserDefaults
  webMain/      Clipboard API 与 localStorage（JS 与 Wasm 共用）
androidApp/     Android 入口
desktopApp/     Desktop 入口（窗口 + 托盘）
iosApp/         iOS 入口
webApp/         Web 入口
```
