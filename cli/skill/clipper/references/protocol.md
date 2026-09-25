# clipper 输出协议速查

协议版本 `1`（`CliResponse.v`）；等于默认值时不写出。

## 信封

```json
{"ok":true,"data":{…}}
{"ok":false,"error":{"code":"NOT_FOUND","message":"没有 id 为「x」的条目","hint":"它可能已经被删除或被上限淘汰"}}
```

| 字段 | 说明 |
| --- | --- |
| `ok` | 必出 |
| `data` | 成功时的载荷，形状见下面各命令 |
| `error.code` / `error.message` / `error.hint` | 失败时；`hint` 可能没有 |

**字段省略规则**（`encodeDefaults = false` + `explicitNulls = false`）：等于默认值的字段与 `null` 一律不写出。所以「字段不存在」=「默认值 / false / 空」，不是错误。

## 错误码

| code | 含义 |
| --- | --- |
| `BAD_REQUEST` | 请求本身不合法（参数缺失、取值非法、互斥开关同给） |
| `UNKNOWN_COMMAND` | 命令名不认识（通常是旧 app 遇到新 CLI） |
| `NOT_FOUND` | 目标条目不存在 |
| `UNSUPPORTED` | 命令认识但当前状态做不到。关闭设置里的「允许访问剪贴板历史」（AI 服务 → 命令行工具）后，除 `ping` 外的每条命令都返回它 |
| `INTERNAL` | 服务端异常 |
| `DAEMON_UNAVAILABLE` | CLI 侧产生：连不上 app（没运行或 socket 权限不对） |
| `TIMEOUT` | CLI 侧产生：等待响应超时 |
| `TRANSPORT` | CLI 侧产生：响应不完整或帧头不合法 |

退出码：`0` 成功（空结果也算成功）· `1` 其他错误 · `2` 用法错误 · `3` 未找到 · `4` app 未运行 · `5` 超时。

## list / search → CliListView

```json
{"ok":true,"data":{"items":[ … ],"total":4,"truncated":true}}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `items` | CliView[] | 条目数组 |
| `total` | Int | **筛选条件下的总数**，不是返回条数 |
| `truncated` | Boolean | 是否因 `--limit` 被截断；false 时不出现 |

`--limit` 默认 20、上限 200（CLI 与服务端双向夹紧）。**没有 offset**，只能取「从头开始的连续一段」；要更早的数据就反转排序：`--sort lastCopiedAt --order asc`。

## CliView（条目）

`list` / `search` 给「摘要态」，`get` 给「详情态」——**同一个类型**，只是 `get` 会把下半部分填上。

### 列表里一定有的字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | String | 32 位十六进制随机串，不透明，别解析 |
| `kind` | String | `text` / `image` / `file` / `richtext` |
| `title` | String | 一行可读标识，**最多 200 字符**。图片是 OCR 文字、文件是路径、文本是正文开头 |
| `titleTruncated` | Boolean | 这一行标识是否不完整（只针对 `title`，不代表整个响应不完整） |
| `bytes` | Long | 近似字节数（图片是精确值，文本按 UTF-8 计） |
| `firstCopiedAt` / `lastCopiedAt` | String | ISO 8601 带时区，如 `2026-09-25T19:52:06.838+08:00` |

### 满足条件才出现

| 字段 | 类型 | 何时出现 |
| --- | --- | --- |
| `copies` | Int | 复制次数 > 1 时（默认 1 不写） |
| `pinned` | Boolean | 已置顶时（默认 false 不写） |
| `sourceApp` | String | 有来源应用时 |
| `files` | `[{path, exists?}]` | 文件类条目；`exists` **只在 `get` 时**给（列表不查磁盘） |
| `hasImage` | Boolean | 条目里有图片（`kind` 为 `file` 时也可能为 true：复制文件常带缩略图） |
| `hasOcr` | Boolean | 有图片文字识别结果 |
| `attachments` | `[{type, bytes}]` | **只在 `get` 时**填充；`type` 是 UTI（如 `public.html`） |

### 只在 get 时出现

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `text` | String | 完整正文。富文本的这一份是**从 HTML/RTF 提取出的可读文字**，不是标签源码 |
| `ocr` | `{text, chars, truncated}` | 图片识别的完整原文（`title` 只是它前面一段） |
| `path` | String | 二进制**落盘路径**，形如 `$TMPDIR/clipper/<id>.<ext>`。图片条目给图片本身；带 `--format` 的富文本条目给该附加表示。重复调用得到同一个文件 |

## stats → CliStatsView

```json
{"ok":true,"data":{"total":8,"pinned":0,"storageBytes":245760}}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `total` | Int | 历史总条数（含置顶）。精确值，不受 `list` 的 200 上限影响 |
| `pinned` | Int | 置顶条数 |
| `storageBytes` | Long | 数据库文件字节数；平台测不到或库为空时不出现。**含未回收的空闲页**，不等于数据量 |
| `paused` | Boolean | 是否暂停记录；false 时不出现 |

刻意**没有**各类型条数。要分布就自己数：`clipper list --kind image --limit 1` 读 `total`。

## ping → CliPingView

```json
{"ok":true,"data":{"appVersion":"1.1.0","protocolVersion":1,"uptimeMillis":2190352}}
```

探活用。它不碰数据层，因此仓库还没就绪时也能回答。

## copy / pin / unpin / delete → CliAffectedView

```json
{"ok":true,"data":{"ids":["6ad4733b3feb1bd7c41c4e3cd8d9a073"]}}
```

实际生效的 id 列表。

## 请求形状（诊断用）

CLI 发给 app 的请求是扁平结构：`cmd` 决定用哪些字段（`id` / `ids` / `query` / `kind` / `sort` / `order` / `pinned` / `limit` / `format`），其余留 `null`。

`--raw` / `--ocr` **不出现在请求里**：它们只改 CLI 打印什么，不改服务端返回什么——响应里本来就带完整正文。

分帧是「4 字节大端长度 + JSON 字节」，不是行分隔，因此无法用 `nc` 之类工具手工对话；需要直接诊断时用 `clipper ping`。
