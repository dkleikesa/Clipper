---
name: clipper
description: 通过 clipper 命令行工具查询与操作 macOS 剪贴板历史（Clipper.app 的配套 CLI）。当需要读取用户最近复制的文本/图片/文件、在剪贴板历史里搜索内容、把某条历史重新写回剪贴板、或置顶与删除历史条目时使用本技能。
---

# clipper CLI

查询与操作 Clipper.app 的剪贴板历史。全部在本机完成，不联网。

本 skill 自带 `script/clipper` 二进制（与本文件同版本），**不需要预先装到 PATH**；唯一的前提是 Clipper.app 在运行。

## 怎么调用 clipper

优先用 skill 自带的同版本二进制，它不存在时再退回 PATH 上的：

```bash
# SKILL_DIR = 本 SKILL.md 所在目录
CLIPPER="$SKILL_DIR/script/clipper"
[ -x "$CLIPPER" ] || CLIPPER="$(command -v clipper)"
"$CLIPPER" list --limit 5
```

两者都没有，说明 skill 安装不完整（`script/clipper` 没跟过来）或环境里没有 clipper：让用户重新把**整个 skill 目录**装一次，不要自己去构建。

## 输出约定

- stdout 一律是 JSON 信封：成功 `{"ok":true,"data":…}`，失败 `{"ok":false,"error":{"code","message","hint"}}`。
- 例外：`get --raw` / `get --ocr` 直出纯文本，不套信封。
- 等于默认值的字段、`null` 一律不写出：**「字段不存在」=「默认值 / false / 空」**，用 `?? 默认值` 兜底，别当异常。
- 别加 `--pretty`：只多约 36% 体积，对机器没有意义。

## 命令失败时先做环境检查

**退出码 4 / `DAEMON_UNAVAILABLE` = Clipper.app 没在运行**，不是命令写错。按顺序处理：

1. 启动它：`open -a Clipper`，等 1–2 秒后重试原命令。
2. `open -a Clipper` 失败（说明没装，通常在 `/Applications/Clipper.app` 或 `~/Applications/Clipper.app`）：告诉用户「读剪贴板历史需要先装 Clipper.app」，**并先问用户要不要下载**。
3. 用户同意后才下载安装（未同意就不要下载，直接说明本次无法完成并停下）。下载地址从官方 Releases 解析，再从 GitHub 下载：

   ```bash
   url=$(curl -sL https://api.github.com/repos/dkleikesa/Clipper/releases/latest \
     | grep -o '"browser_download_url": *"[^"]*\.dmg"' | cut -d'"' -f4 | head -1)
   curl -L -o ~/Downloads/Clipper.dmg "$url"
   ```

   Releases 页面（也可以直接给用户）：https://github.com/dkleikesa/Clipper/releases
4. 装好后启动：打开 dmg → 把 Clipper.app 拖进「应用程序」→ 首次启动若被拦（未签名），`xattr -dr com.apple.quarantine /Applications/Clipper.app` → `open -a Clipper`，再重试原命令。

其余退出码与错误码见 `references/protocol.md`。

## 常用操作

### 看最近复制了什么

```bash
clipper list --limit 5                 # 默认 20，上限 200
clipper list --kind image --limit 5    # kind：text / image / file / richtext
clipper list --pinned                  # 只看置顶；--unpinned 与之互斥
```

### 按关键词找，再取全文

```bash
clipper search "发票 报销" --limit 10   # 多个词是 AND，含空格要引号
clipper get <id>                       # JSON，含完整 text
clipper get <id> --raw                 # 只输出正文，便于直接喂给别的命令
```

`search` 只匹配标题（正文开头 / 图片 OCR 文字 / 文件路径），**不搜正文深处**。找不到就换更短的词，或先 `list` 一批自己筛。

### 图片 / 文件 / 富文本

二进制内容不经过 stdout，服务端落盘后在响应里给路径：

```bash
clipper get <id>                 # 响应里的 path 指向 $TMPDIR/clipper/<id>.<ext>，直接读该文件
clipper get <id> --ocr           # 只输出图片识别出的原文
clipper get <id> --format html   # 导出附加表示（html / rtf / pdf），响应里给路径
clipper list --kind file         # files[] 给出文件条目的路径
```

对图片等没有可直出文本的条目跑 `--raw` 会以退出码 3 失败（错误里的 `hint` 带上落盘路径）——这不是 bug，改用 `path`。

### 把某条重新放回剪贴板

```bash
clipper list --limit 5   # 拿到 id
clipper copy <id>        # 写回系统剪贴板，并把这条排到最前
```

### 整理历史

```bash
clipper pin <id>                # 置顶（置顶条目永远排在 list 最前）
clipper unpin <id>
clipper delete <id> <id> <id>   # 可一次给多个
```

### 判断当前状态

```bash
clipper stats   # total / pinned / storageBytes / paused
clipper ping    # appVersion / protocolVersion / uptimeMillis
```

`stats.paused == true` 表示用户暂停了记录 —— 这时刚复制的内容不会进历史，是「明明复制了却搜不到」的常见原因。

## 容易踩的坑

| 坑 | 正确做法 |
| --- | --- |
| 解析或截断 `id` | 它是 32 位十六进制随机串，不透明；只做原样传递 |
| 把 `title` 当全文 | 最多 200 字符且已被转义；要正文用 `get` |
| 以为 `search` 会搜正文 | 只匹配标题；必要时先 `list` 拉一批再本地筛 |
| 拿 `items.size` 当总数 | 看 `total`；`items.size < total` 就是被 `--limit` 截断了 |
| 想取第 201 条之后 | 没有 offset；改排序（`--sort lastCopiedAt --order asc`）或加 `--kind` 缩小范围 |
| 排序字段写成中文或下划线 | 取值是 camelCase：`lastCopiedAt` / `firstCopiedAt` / `copies` / `size` |
| 同时给 `--format` 与 `--raw`/`--ocr` | 互斥，会报错 |

## 参考

- `references/protocol.md`：各命令 `data` 的完整字段表、错误码与退出码、字段省略规则。要逐字段确认结构或写解析代码时读它。
- 命令行内帮助：`clipper --help`、`clipper <命令> --help`。
