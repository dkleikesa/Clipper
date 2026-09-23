<div align="center">

<img src="desktopApp/icons/clipper.png" width="128" alt="Clipper 图标">

# Clipper

macOS 菜单栏剪贴板历史管理器

[![CI](https://github.com/dkleikesa/Clipper/actions/workflows/ci.yml/badge.svg)](https://github.com/dkleikesa/Clipper/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-macOS-lightgrey.svg)](#环境要求)

基于 [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/) 构建，界面与交互参考 [Maccy](https://github.com/p0deje/Maccy)。

</div>

<!-- 建议在此处补一张主界面 + 预览面板的截图，这是开源项目最影响使用意愿的部分 -->

## ⭐ 核心亮点

> **轻量 · 纯本地 · 可深搜**：历史再大也不常驻内存，长文本和截图都能搜到。

| 特点 | Clipper 的做法 |
| --- | --- |
| **无条数上限，可存超长历史** | 上限只填正整数、不设最大值（默认 1 万条），且元数据与载荷分表存储，内存只保留当前分页窗口，正文与图片按批读取 |
| **全文深搜** | 五档打分匹配（精确 → 整词 → 前缀 → 子串 → 子序列），结果滚到底后继续在正文里搜 |
| **截图可搜** | 图片自动文字识别（OCR），识别结果作条目标题，预览内一键复制识别原文 |
| **粘贴不丢格式** | 完整保留 HTML / RTF 等富文本表示，粘贴后样式不变 |
| **机密永不落盘** | 密码管理器声明为「临时 / 机密 / 自动生成」的内容始终不记录 |
| **纯本地、无账号** | 数据仅存于 `~/.clipper/`，不联网、不登录 |

**顺带一提**：五档搜索命中高亮样式可选、类型筛选与四种排序、`⌥` 粘贴 / `⌥⇧` 去格式粘贴、主题三模式、面板宽度与预览开合状态持久化——这些细节都有。

## 特性

**剪贴板历史**

- 自动记录文本、图片、文件与富文本（HTML / RTF），保留原始格式，粘贴不丢格式
- 密码管理器等声明为「临时 / 机密 / 自动生成」的内容始终不记录
- `⌘P` 暂停记录并在面板顶部提示；可选择退出时自动清空历史
- 历史上限可配（默认 1 万条，**不设上限**，想留多少条就填多少条），置顶项不计入上限、也不会被淘汰
- 一键清除历史：仅清未置顶项，或全部清除

**查找与整理**

- 五档打分搜索：精确 → 整词 → 前缀 → 子串 → 子序列；多词之间为 AND，同档内再比位置、连续度与大小写
- 命中片段高亮，样式可选（加粗 / 斜体 / 下划线 / 背景）
- 结果滚到底后可继续从正文内容里查找（正文按批读取，不常驻内存）
- 类型筛选栏（文本 / 图片 / 文件 / 富文本）与排序（最后复制 / 首次复制 / 复制次数 / 内容大小，支持升降序）
- 置顶：固定在列表顶部或底部

**操作**

- 全局热键呼出面板（默认 `⇧⌘C`），可自定义或清除绑定
- 回车激活选中项，默认仅复制；`⌥` 粘贴、`⌥⇧` 粘贴并去掉格式、`⌘` 复制，也可改为默认粘贴
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

### 从源码运行

```bash
git clone https://github.com/dkleikesa/Clipper.git
cd Clipper
./gradlew :desktopApp:run
```

打包 release 安装包：

```bash
./gradlew :desktopApp:packageReleaseDmg
# 产物：desktopApp/build/compose/binaries/main-release/dmg/Clipper-<version>.dmg
```

## 使用

### 快捷键

| 操作 | 按键 |
| --- | --- |
| 呼出面板 | `⇧⌘C`（可自定义） |
| 暂停 / 恢复记录 | `⌘P`（可自定义） |
| 下一个 / 上一个 | `↓` / `↑` |
| 激活选中项（默认复制） | `Enter` |
| 置顶 / 取消置顶 | `⌥P`（可自定义） |
| 删除选中项 | `⌥⌫`（可自定义） |
| 显示 / 隐藏预览 | `⌃Space`（可自定义） |
| 设置 | `⌘,` |
| 清空搜索 / 关闭 | `Esc` |

回车的行为随修饰键变化：`⌘` 复制、`⌥` 粘贴、`⌥⇧` 粘贴并去掉格式。在设置中开启「默认粘贴」后，回车本身即粘贴，修饰键组合的含义随之改变（设置页会实时显示当前映射）。

### 说明

- 自动粘贴与全局热键需要在「系统设置 → 隐私与安全性 → 辅助功能」中授权，未授权时相关功能会静默失效。
- 关闭窗口会隐藏到托盘，剪贴板监听继续运行。
- 剪贴板历史保存在 `~/.clipper/` 下。

## 项目结构

```
Clipper/
├── design/          设计稿与应用图标源文件
├── desktopApp/      桌面端入口：窗口 / 托盘 / 热键接线、打包配置
├── shared/          核心业务代码
│   └── src/
│       ├── commonMain/   与平台无关的领域模型、用例、搜索与 UI
│       └── jvmMain/      macOS 平台实现（core/platform/macos/）
└── gradle/          版本目录（libs.versions.toml）与 wrapper
```

## 已知限制

- 仅支持 macOS，打包配置中的 Windows / Linux 目标尚未验证可用
- 构建产物未做代码签名与公证，需要用户手动放行
- 尚未建立自动化测试基线

后续计划见 [Roadmap](ROADMAP.md)。

## 贡献

欢迎提交 Issue 与 Pull Request。

## 许可证

[MIT](LICENSE)
