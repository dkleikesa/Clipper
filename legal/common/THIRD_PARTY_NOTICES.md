# 第三方组件声明

本文件登记发行物中包含的第三方组件及其许可证，用于满足 Apache-2.0 §4(a)、§4(d) 的分发要求。

`legal/common/` 下的文件会被打进安装包的 resources 目录（macOS 为
`Clipper.app/Contents/app/resources/`，配置见 `desktopApp/build.gradle.kts` 的
`appResourcesRootDir`）：

| 文件 | 内容 |
| --- | --- |
| `LICENSE.txt` | 本项目自身的 MIT 许可证 |
| `THIRD_PARTY_NOTICES.md` | 本文件：第三方组件声明 |
| `APACHE-2.0.txt` | Apache-2.0 全文（kotlinx、Compose Multiplatform 等 Apache-2.0 依赖的许可证） |

另外，生成 dmg / msi / deb 安装包时，jpackage 的 `--license-file` 会把根目录的
`LICENSE` 作为安装向导的许可页（仅安装器类型，app 镜像不含）。

> 下文提到的路径都是**源码仓库内**的路径。

## 本项目自身的许可证

Clipper 自己的代码以 **MIT** 发布，全文见根目录 `LICENSE`（发行包内为 `LICENSE.txt`）。

**发行物是混合许可的**：下面列出的组件**不**受 MIT 覆盖，仍按各自的许可证分发。

## 其它依赖

构建还依赖 Compose Multiplatform、kotlinx（coroutines / serialization / io / datetime）、
Room、ksoup、xmlutil、JNA 等，许可证为 Apache-2.0 或 MIT。

**这些尚未逐项登记**，补齐之前本文件不是完整的第三方清单。
