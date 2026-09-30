# 测试计划

分两类：**功能验收测试**（黑盒，验可观察行为）与**单测**（只留算法 / 隐性不变量 / 字节契约这类没有行为面的）。

总原则：**跟随实现写的测试不做**——改布局、改重构就红，却抓不到真 bug。

---

## 进度

| 项 | 状态 | 例数 |
| --- | --- | --- |
| S1 窗口几何行为测试 | ✅ 完成 | 5 |
| 单测 U1–U4（算法与契约） | ✅ 完成 | 36 |
| 单测 U5–U10（shared 隐性不变量） | ⬜ 未开始 | — |
| 功能验收 F1–F8 | 🚧 F1 完成 | 10 |

已完成明细见 §四。

---

## 一、功能验收测试　`1 / 8`

四个层级，按 CI 可行性排序：

| 层 | 覆盖 | 怎么搭 | CI |
| --- | --- | --- | --- |
| **A. In-process 全链路** | 真 Room + 真 SqlCipher 驱动（临时目录）；捕获 / 搜索 / 置顶 / 淘汰 / 预览 | 复用 `TestClipperDatabase`、`DataSourceFixtures`；用假「系统输入」喂快照 | ✅ |
| **B. 真 socket CLI 端到端** | `CliServer` + `CliRequestHandler` ↔ `:protocol` 分帧 ↔ CLI 请求 | 临时 socket + 真存储；断言 JSON 与退出码 | ✅ |
| **C. Compose UI 交互** | 面板渲染、键盘导航、预览开合、多选 | `compose-ui-test`（已在依赖里，`HistoryRowTest` 是例子） | ✅ |
| **D. 真启动 app** | 托盘、全局热键、辅助功能、粘贴 | `:desktopApp:run` + CLI 驱动 | ⚠️ 需 GUI 会话，**默认跳过**（同 `CLIPPER_PASTEBOARD_TESTS` 的门控） |

功能清单（验收标准取自 README / ROADMAP 里对用户承诺的行为）：

| # | 状态 | 功能 | 层 | 验收标准（可观察） |
| --- | --- | --- | --- | --- |
| F1 | ✅ | CLI 端到端 | B | 输出恒为 `{"ok":true,"data":…}` / `{"ok":false,"error":{code,message,hint}}`；退出码 0/1/2/3/4/5；`list` 的 `--kind/--sort/--order/--pinned/--limit`（默认 20、上限 200）；`search` 多词 AND、`--deep` 回 `deepSearchTruncated`；`get --raw` 直出全文**不补换行**、`--ocr` 直出识别原文、`--format html\|rtf\|pdf` 落盘给路径；`pin/unpin/delete/copy/stats/ping` 均生效；关掉「允许访问剪贴板历史」后除 `ping` 一律 `UNSUPPORTED` |
| F2 | ⬜ | 捕获与历史 | A | 文本/图片/文件/富文本都进历史；机密 / 临时 / 自动生成类型**永不**记录；暂停期间不记录；自己写回的不重复记录；重复复制只更新计数与时间戳；置顶不计入上限、也不被淘汰；超限按最后复制时间淘汰；清除只清未置顶 / 全部；退出清空（开启时） |
| F3 | ⬜ | 搜索 | A | 五档顺序（精确 > 整词 > 前缀 > 子串 > 子序列）在同一次查询里成立；多词 AND；类型筛选与排序字段/方向生效；深搜结果**追加**在标题命中之后，删除/筛选变化后收敛；高亮区间落在正文对应位置 |
| F4 | ⬜ | 激活与写回 | A | 单条原样写回（保 HTML / RTF）；去格式只写纯文本；多条复制合成一段纯文本（有一条拿不出文本则退回最后一条）；多条粘贴逐条进行；激活后计数 +1 且按最后复制时间重排 |
| F5 | ⬜ | 预览与展示 | A/C | 图片 OCR 结果作标题、可复制识别原文；富文本提取文字作标题；预览显示**全文**（不被 1000 字符截断） |
| F6 | ⬜ | 面板交互 | C | `↑`/`↓` 导航、`⇧` 连续选、`⌃` 多选、`⌥⏎` 复制、`⏎` 粘贴、`⌥P` 置顶、`⌥⌫` 删除、`⌃Space` 预览、`⎋` 逐级退出（设置 → 多选 → 搜索 → 关窗）、`⌘1…9` 快速粘贴置顶项、`⌘P` 暂停 |
| F7 | ⬜ | 设置与持久化 | A | 改设置落盘并即时生效；重启后保持；「恢复默认设置」回到出厂值；槽位被清除/改名后旧偏好仍读得出 |
| F8 | ⬜ | 数据库加密 | A | 开启后重启需口令；错误口令被拒；关闭前必须核对当前口令 |

---

## 二、值得做的单测　`4 / 10`

判据：**没有可观察的用户行为面**，只能对着算法契约或隐性不变量测；或者错了会**静默**。

| # | 状态 | 目标 | 为什么功能测试覆盖不到 | 断言 |
| --- | --- | --- | --- | --- |
| U1 | ✅ | `ClipSearch` 五档打分 | 算法复杂；行为面只有「顺序」，边界要大量构造 | 档位主导细节分；同档按位置/连续度/大小写；多词取最差档 + 平均细节；子序列向左收缩；全角折叠后索引仍对齐；取消生效 |
| U2 | ✅ | `ClipDeepSearch` 预算 | 只在高负载下显形，功能测试难稳定复现 | `BATCH_SIZE` 分批；`MAX_HITS`/`MAX_CHARS` 截断与 `truncated` 语义；同 id 去重 |
| U3 | ✅ | `CliFraming` 分帧 | 无行为面；字节序写歪两侧都「能跑」，只静默读歪 | 大端 golden 字节；编解码往返；越界 / 短头 / 超大抛错 |
| U4 | ✅ | `CliCodec` + `CliExitCode` | 契约型：缺省省略 / null 省略 / 未知字段忽略，跨版本兼容 | 信封形状；`dataAs` 结构不符返回 `null`；错误码 → 退出码全映射 |
| U5 | ⬜ | `ClipItem` 等值替换与标题截断 | 隐性不变量（**必须等长**），破坏后只表现为高亮错位 | `toStoredTitle` 截断 + `\uFFFC` 过滤；`titleForDisplay` 等长；`deriveTitle` 与 `previewableText` 的分叉 |
| U6 | ⬜ | `ClipboardContent` 内容语义 | `ByteArray` 默认按引用比；错了只表现为去重 / diff 静默失效 | `equals/hashCode` 按内容；`contentKeyOf` 与顺序无关、item 序号参与 |
| U7 | ⬜ | `ClipEntityMappings` 往返 | 持久化兼容，只在读旧数据时暴露 | `toEntity/toModel` 往返；枚举越界兜底；NUL 分隔文件；pin 标记；CBOR / legacy 列 |
| U8 | ⬜ | `AppSettings` 序列化 + 槽位迁移 | 改名 / 增删字段只静默让旧偏好对不上号 | 默认值 == `ShortcutSlot.default`；`shortcut/withShortcut` 全槽位覆盖；未知 / 缺失字段兼容 |
| U9 | ⬜ | `shortcutProblem` / `occupiedSpecs` | 规则表密集的纯判定；走 UI 才能触达成功能测试，成本高 | 六种返回；派生组合查重；数字键 `RESERVED`；裸键需修饰键 |
| U10 | ⬜ | `HistoryNavigation` | 纯状态转移但边界密集 | 各转移 × 空列表 / 单元素 / 越界 / 锚点伸缩 |

---

## 三、明确不做

- **窗口几何公式**（`autoWindowSize` / `constrained` / `Popup` 宽度算式）：公式复述型已删，只留行为测试（见 §四）。
- **ViewModel / 用例 / 仓库的业务规则**：走 **A 层功能测试**，不再用 mock 单测——那是镜像实现的重灾区。
- **CLI 解析器**（`ArgParser` / `toRequest`）：走 **B 层端到端**，解析 + 映射 + 信封一起验更真。
- `devTools` 的 Json/Xml/Detector/Registry、`Fnv1a`、`formatDateTime`、`Help`、纯 getter / 数据类：一眼见底。

---

## 四、已完成明细

- **S1 窗口几何行为测试**（5 例，`WindowGeometryControllerTest`）：同一窗口更新里下限先于尺寸、几何去重、隐藏不动窗、预览开合时序、拖动只落盘一次。配套 `WindowEnvironment` 测试缝（屏幕 / 锚点 / 时钟注入）与 `WindowPlacement` 的 bounds 重载。
- **U1 `ClipSearch`**（13 例，`ClipSearchTest`）：档位主导、同档位置 / 大小写、多词 AND 与最差档、子序列向左收缩（`clp` → `clipper`）、同一词取最高档、全角折叠后索引对齐、取消、正文批次的下标与排序。
- **U2 `ClipDeepSearch`**（5 例，`ClipDeepSearchTest`）：空查询 / 无候选、`BATCH_SIZE` 分批（200/200/50）、同 id 去重、命中预算与字符预算的 `truncated` 语义。
- **U3 分帧**（7 例）：`CliFramingTest` 5 例——commonTest 的大端 golden 字节与往返、越界 / 短头拒绝；`FramingStreamTest` 2 例——jvmTest 的流上多条往返、空载荷、截断读作 `null`。
- **U4 编解码与退出码**（11 例）：`CliCodecTest` 8 例——缺省与 `null` 省略、未知字段宽容、`dataAs` 结构不符返回 `null`；`CliExitCodeTest` 3 例——错误码 → 退出码全映射与数值契约。
- **F1 CLI 端到端**（10 例，`:cli` 的 `CliEndToEndTest`）：真 socket（`CliRunner` ↔ `:protocol` 分帧 ↔ `CliServer`）+ 真 Room / SQLCipher 存储（临时 `user.home`）。覆盖信封形状、退出码 0/1/2/3/4/5、`list` 的 `--kind/--sort/--order/--pinned/--limit`，`search` 多词 AND 与 `--deep`，`get` 的 `--raw`/`--ocr`/`--format`，`pin/unpin/delete/copy/stats`，以及关掉授权后除 `ping` 一律 `UNSUPPORTED`。配套两处缝：`:cli` 增加**仅测试用**的 JVM 目标（原生 `DaemonClient` / 入口移入 `macosMain`），`AppContainer` 可注入剪贴板与原生数据源（存储仍是真实现）。
- `:protocol` 已加 `commonTest` 源集（`kotlin-test`）；JVM 与原生**各跑一遍**，字节契约两侧一致（原生 16 例通过）。

**环境要点（踩过的坑）**
- `backgroundScope` 的协程靠 `runCurrent()` 推进；快照状态变更用 `Snapshot.withMutableSnapshot` 才派发 apply 通知（S1）。
- commonTest 的测试名**不能含逗号**：Kotlin/Native 报 `illegal characters`，JVM 不报错——必须跑一次 `macosArm64Test` 才会暴露（U4）。
- `./gradlew build` 现在会多跑 `:protocol:macosArm64Test`（原生测试链接，本机约 7s）。
- B 层要起真 socket：路径受 `sockaddr_un.sun_path` 的 **104 字节**上限约束，临时目录必须建在 `/tmp` 下（macOS 的 `java.io.tmpdir` 是 `/var/folders/<长哈希>/T/`，加上 `/.clipper/clipper.sock` 正好越界，`bind` 报 `Unix domain path too long`）。

---

## 五、推进顺序

1. ~~**U1–U4**（算法与契约，零依赖，立刻能写）~~ ✅ 已完成
2. ~~**F1（B 层 CLI 端到端）**——收益最大的一条~~ ✅ 已完成
3. **F2 / F3 / F4（A 层）**
4. **U5–U10**（shared 隐性不变量）
5. **F6（C 层面板交互）**、**F5 / F7 / F8**

## 待确认

1. ~~B 层要起真 socket：`clipperSocketPath(home)` 已参数化，需确认 `CliServer` 是否已能接受自定义路径。~~ 已确认：`CliServer(socketPath = …)` 本就可注入，测试直接给临时路径。
2. D 层（真启动 app）是否要做成本地可选脚本？
