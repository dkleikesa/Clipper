<div align="center">

<img src="desktopApp/icons/clipper.png" width="128" alt="Clipper 图标">

# Clipper

macOS 菜单栏剪贴板历史管理器

[![CI](https://github.com/dkleikesa/Clipper/actions/workflows/ci.yml/badge.svg)](https://github.com/dkleikesa/Clipper/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-macOS-lightgrey.svg)](#环境要求)

基于 [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/) 构建，界面与交互参考 [Maccy](https://github.com/p0deje/Maccy)。

</div>

## ⭐ 核心亮点

> **轻量 · 纯本地 · 可深搜**：历史再大也不常驻内存，长文本和截图都能搜到。

| 特点 | Clipper 的做法 |
| --- | --- |
| **无条数上限，可存超长历史** | 上限只填正整数、不设最大值（默认 1 万条），且元数据与载荷分表存储，内存只保留当前分页窗口，正文与图片按批读取 |
| **全文深搜** | 五档打分匹配（精确 → 整词 → 前缀 → 子串 → 子序列），结果滚到底后继续在正文里搜 |
| **截图可搜** | 图片自动文字识别（OCR），识别结果作条目标题，预览内一键复制识别原文 |
| **机密永不落盘** | 密码管理器声明为「临时 / 机密 / 自动生成」的内容始终不记录 |
| **纯本地、无账号** | 数据仅存于 `~/.clipper/`，不联网、不登录 |
| **命令行与 AI 可用** | 附带 `clipper` 命令行与 AI skill，脚本和助手读的是同一份历史，不会与界面结果不一致 |

## 特性

**剪贴板历史**

- 自动记录文本、图片、文件与富文本（HTML / RTF），保留原始格式，粘贴不丢格式
- 历史上限可配（默认 1 万条，**不设上限**），置顶项不计入上限、也不会被淘汰
- `⌘P` 暂停记录并在面板顶部提示
- 一键清除历史：仅清未置顶项，或全部清除

**查找与整理**

- 五档打分搜索更好用：精确 → 整词 → 前缀 → 子串 → 子序列；多词之间为 AND，同档内再比位置、连续度与大小写
- 支持深度搜索：再长的文本也能搜到，正文按批读取，不常驻内存
- 类型筛选栏：支持筛选（文本 / 图片 / 文件 / 富文本）与排序（最后复制 / 首次复制 / 复制次数 / 内容大小，支持升降序）
- 置顶：固定在列表顶部或底部，顺序由**置顶时间**决定——不随排序设置变

**操作**

- 全局热键呼出面板（默认 `⇧⌘C`），可自定义或清除绑定
- 置顶 `⌥P`、删除 `⌥⌫`、预览开关 `⌃Space`
- 点击托盘图标切换面板显隐；点击面板外区域自动收起

**外观**

- 预览面板展示内容、来源应用与元信息，宽度可拖拽且开合状态持久化
- 图片自动文字识别（默认开启）：识别结果用作条目标题，预览里可一键复制识别原文
- 主题三模式：跟随系统 / 浅色 / 深色
- 弹窗位置与显示器可选、面板尺寸自动或自定义、开机自启
- 显示开关：十六进制色块、特殊符号、来源应用图标、图片最大高度；托盘图标可隐藏，不占用 Dock
- 设置页分组呈现，支持一键「恢复默认设置」

## 环境要求

| 项目 | 要求 |
| --- | --- |
| 操作系统 | macOS（应用依赖 AppKit、NSStatusItem 与辅助功能授权，暂无 Windows / Linux 支持） |
| JDK | 21（构建脚本已固定 toolchain，缺失时会自动下载） |

## 安装

### 下载安装包

从 [Releases](https://github.com/dkleikesa/Clipper/releases) 下载最新的 `.dmg`，打开后将 Clipper 拖入「应用程序」。

首次启动若提示「无法验证开发者」，右键点击图标选择「打开」，或执行：

```bash
xattr -dr com.apple.quarantine /Applications/Clipper.app
```

> 当前构建未做代码签名与公证，该提示属预期行为。

### 命令行与 AI skill（可选）

命令与 AI skill 在同一个压缩包里，从 [Releases](https://github.com/dkleikesa/Clipper/releases) 下载 `clipper-skill-<版本>.zip` 解压即可，无需另行安装：

```
clipper-skill-<版本>/
└── skill/clipper/            ← 这一整个目录就是 skill
    ├── SKILL.md
    ├── references/
    └── script/clipper        ← 命令本体（macosArm64）
```

- **给 AI 助手用**：把 `skill/clipper` 整个目录拷进助手的 skills 目录（CodeBuddy 是 `~/.codebuddy/skills/`）。skill 找命令用的是相对自身目录的 `script/clipper`，所以不必把它装进 PATH。
- **自己用**：全路径调用，或软链进 PATH：

```bash
ln -sf "$PWD/clipper-skill-<版本>/skill/clipper/script/clipper" /usr/local/bin/clipper
```

### 从源码运行

```bash
git clone https://github.com/dkleikesa/Clipper.git
cd Clipper
./gradlew :desktopApp:run
```

打包 release 产物：

```bash
./gradlew :desktopApp:packageReleaseDmg
# 安装包：desktopApp/build/compose/binaries/main-release/dmg/Clipper-<版本>.dmg

./gradlew :cli:distZip
# 命令行与 skill：cli/build/distributions/clipper-skill-<版本>.zip

./gradlew :cli:installDist
# 免打包直接用：cli/build/install/clipper-skill/skill/clipper/script/clipper
```

## 使用

### 快捷键

| 操作 | 按键 |
| --- | --- |
| 呼出面板 | `⇧⌘C` |
| 暂停 / 恢复记录 | `⌘P` |
| 选中上一条 / 下一条 | `↑` / `↓`（按住 `⇧` 连续选中） |
| 跳到第一条 / 最后一条 | `⌘↑` / `⌘↓` |
| 激活选中项（复制到剪贴板） | `⌥⏎` |
| 去格式激活（只复制纯文本） | `⌥⌘⏎` |
| 直接粘贴 | `⏎` |
| 去格式粘贴 | `⌘⏎` |
| 快速粘贴前 9 个置顶项 | `⌘1` … `⌘9` |
| 置顶 / 取消置顶 | `⌥P` |
| 删除选中项 | `⌥⌫` |
| 显示 / 隐藏预览 | `⌃Space` |
| 打开设置 | `⌘,` |
| 清空搜索 / 关闭 | `⎋` |

**以上快捷键全部可在「设置 → 快捷键」中重新录制、清除绑定或一键恢复默认**，每行还带一句说明。
只有「呼出面板」注册为系统级热键，其余都在面板获得焦点时生效。

激活的四种按法各有一条**独立**快捷键（上表前四行），按下哪条就做哪件事，互不影响：按 `⏎` 永远是粘贴，按 `⌥⏎` 永远是复制。鼠标不走这些绑定——单击做什么在「设置 → 快捷键 → 激活与选择」里单独选（默认「激活」，即复制），`⌥` 单击 = 直接粘贴、`⌥⇧` 单击 = 去格式粘贴，`⌘` / `⇧` 单击仍是多选与连续选中。

### 命令行（clipper）

需要 app 正在运行；命令本身不读数据库，而是连 `~/.clipper/clipper.sock`（目录 0700、socket 0600，只有当前用户能连）。

| 命令 | 用途 |
| --- | --- |
| `clipper list` | 列出历史；`--limit`（默认 20，上限 200）、`--kind`、`--sort`、`--order`、`--pinned` |
| `clipper search <关键词>` | 搜标题（正文开头 / 图片 OCR 文字 / 文件路径）；多词之间是 AND，含空格要加引号 |
| `clipper get <id>` | 取单条全文；`--raw` 直出文本、`--ocr` 直出识别原文、`--format html/rtf/pdf` 导出附件 |
| `clipper copy <id>` | 把它写回系统剪贴板，效果与面板里的「激活」相同 |
| `clipper pin` / `unpin <id>` | 置顶 / 取消置顶 |
| `clipper delete <id>…` | 删除若干条 |
| `clipper stats` / `ping` | 历史概况 / 探活与版本 |

```bash
clipper list --limit 5
clipper search "发票 报销" --limit 10
clipper get 6ad4733b3feb1bd7c41c4e3cd8d9a073 --raw
```

- 输出一律是 JSON：成功 `{"ok":true,"data":…}`，失败 `{"ok":false,"error":{"code","message","hint"}}`；退出码 `0` 成功 · `1` 其他错误 · `2` 用法错误 · `3` 未找到 · `4` app 未运行 · `5` 超时。
- 列表只给最长 200 字符的标题，全文用 `get`；图片等内容不经过 stdout，落盘后在响应里给出路径。
- 完整参数与示例见 `clipper --help`、`clipper <命令> --help`。

### 说明

- 自动粘贴与全局热键需要在「系统设置 → 隐私与安全性 → 辅助功能」中授权，未授权时相关功能会静默失效。
- 关闭窗口会隐藏到托盘，剪贴板监听继续运行。
- 剪贴板历史保存在 `~/.clipper/` 下。
- 想让外部程序与 AI 助手读不到历史，可在「设置 → AI 服务 → 命令行工具」关掉「允许访问剪贴板历史」——关闭后除探活外一律拒绝。

## 项目结构

```
Clipper/
├── design/          设计稿与应用图标源文件
├── desktopApp/      桌面端入口：窗口 / 托盘 / 热键接线、打包配置
├── shared/          核心业务代码
│   └── src/
│       ├── commonMain/   与平台无关的领域模型、用例、搜索与 UI
│       └── jvmMain/      macOS 平台实现（core/platform/macos/、host/cli/ CLI 服务端）
├── cli/             clipper 命令行（Kotlin/Native 原生二进制）与随包发布的 AI skill
├── protocol/        app 与 CLI 共用的线上契约（命令名 / 信封 / 错误码）
└── gradle/          版本目录（libs.versions.toml）与 wrapper
```

## 已知限制

- 仅支持 macOS，打包配置中的 Windows / Linux 目标尚未验证可用
- 构建产物未做代码签名与公证，需要用户手动放行
- 命令行与 skill 目前只随 Apple Silicon（macosArm64）产物发布
- 尚未建立自动化测试基线

各版本变更见 [更新日志](CHANGELOG.md)，后续计划见 [Roadmap](ROADMAP.md)。

## 贡献

欢迎提交 Issue 与 Pull Request。

## 许可证

[MIT](LICENSE)
