package com.qcmian.logviewer.core

/**
 * 内置示例日志（F1-6）：首次进入无文件时可加载，便于体验与演示。
 * 覆盖：logcat 新旧格式、通用时间戳+级别、JSON 行、TraceID、超长行、中文 ANSI 场景。
 */
object SampleLogs {
    val fileName = "示例日志.log"

    val text: String = buildString {
        appendLine("03-14 09:30:01.221  1982  1998 I ActivityTaskManager: Displayed com.sogou.input/.SplishActivity")
        appendLine("03-14 09:30:01.335  1982  2010 D InputMethodManagerUtil: bind service cost=32ms")
        appendLine("2026-09-02 09:30:02.101 [INFO] [Network] 网络已连接 wifi/Office-5G, ip=10.24.112.31")
        appendLine("2026-09-02 09:30:02.560 [INFO] [Login] 用户登录成功 uid=1024871 token=Bearer eyJhbGciOiJIUzI1NiJ9.sample.payload")
        appendLine("03-14 09:30:03.001  1982  2104 E libc    : Access denied finding property \"vendor.debug.config\"");
        appendLine("2026-09-02 09:30:03.402 [DEBUG] [Cache] 命中本地缓存 key=user_profile_1024871 hitRate=0.93")
        appendLine("09-14 09:30:03.998 E/BrowserFeed( 1982): Failed to load feed: HTTP/1.1 503 Service Unavailable")
        appendLine("2026-09-02 09:30:04.120 [INFO] [Request] GET /api/v1/words?query=pinyin&traceId=8f14e45fceea167a5a36dedd4bea2543 started")
        appendLine("2026-09-02 09:30:04.512 [WARN] [Request] 慢请求告警 cost=391ms threshold=300ms traceId=8f14e45fceea167a5a36dedd4bea2543")
        appendLine("2026-09-02 09:30:05.001 [ERROR] [Request] Connection timeout after 5000ms requestId=req-20260902-000731 host=api-words.sogou.local")
        appendLine("2026-09-02 09:30:05.002 [INFO] [Retry] 第 1 次重试 requestId=req-20260902-000731 backoffMs=250")
        appendLine("2026-09-02 09:30:05.280 [ERROR] [Request] Connection timeout after 5000ms requestId=req-20260902-000731 host=api-words.sogou.local")
        appendLine("2026-09-02 09:30:05.281 [ERROR] [Request] Connection timeout after 5000ms requestId=req-20260902-000731 host=api-words.sogou.local")
        appendLine("2026-09-02 09:30:05.282 [ERROR] [Request] Connection timeout after 5000ms requestId=req-20260902-000731 host=api-words.sogou.local")
        appendLine("2026-09-02 09:30:05.520 [ERROR] [Network] okhttp3.internal.http.RetryAndFollowUpInterceptor: 请求失败 java.net.SocketTimeoutException: timeout")
        appendLine("2026-09-02 09:30:05.700 [WARN] [Battery] 电量偏低 level=14% 请及时充电")
        appendLine("03-14 09:30:06.100  1982  2450 W System  : Failed to read /proc/stat: permission denied")
        appendLine("{\"ts\":\"2026-09-02T09:30:06.300+08:00\",\"level\":\"INFO\",\"module\":\"sync\",\"msg\":\"增量同步完成\",\"added\":42,\"updated\":7,\"deleted\":0,\"costMs\":128,\"traceId\":\"8f14e45fceea167a5a36dedd4bea2543\"}")
        appendLine("2026-09-02 09:30:06.521 [DEBUG] [UI] 首帧渲染完成 frameTimeMs=16.6 skippedFrames=0")
        appendLine("2026-09-02 09:30:07.000 [INFO] [CrashReport] 捕获到上次会话的崩溃堆栈，开始上传（脱敏后）")
        appendLine("FATAL EXCEPTION: main")
        appendLine("Process: com.sogou.input, PID: 1982")
        appendLine("java.lang.NullPointerException: Attempt to invoke virtual method 'int java.lang.String.length()' on a null object reference")
        appendLine("        at com.sogou.input.core.WordEngine.lookup(WordEngine.kt:188)")
        appendLine("        at com.sogou.input.ui.CandidateBar.refresh(CandidateBar.kt:76)")
        appendLine("        at com.sogou.input.ui.InputActivity.onResume(InputActivity.kt:120)")
        appendLine("        at android.app.Instrumentation.callActivityOnResume(Instrumentation.java:1456)")
        appendLine("2026-09-02 09:30:07.100 [ERROR] [Crash] FATAL EXCEPTION 已写入 tombstone: /data/tombstones/tombstone_07")
        appendLine("2026-09-02 09:30:08.000 [INFO] [ANR] 输入分发超时检测: 主线程最大阻塞 87ms，正常")
        appendLine("09-14 09:30:08.401 I/Choreographer( 1982): Skipped 32 frames!  The application may be doing too much work on its main thread.")
        appendLine("2026-09-02 09:30:09.000 [DEBUG] [Perf] 内存快照: javaHeap=231MB nativeHeap=96MB totalPss=517MB")
        appendLine("2026-09-02 09:30:10.000 [INFO] [Network] 网络切换 wifi -> cellular, reason=WiFi 信号丢失")
        appendLine("2026-09-02 09:30:10.331 [WARN] [Network] 弱网环境 rtt=2100ms loss=12%")
        appendLine("2026-09-02 09:30:11.000 [ERROR] [Network] Connection timeout after 5000ms requestId=req-20260902-000998 host=api-words.sogou.local")
        appendLine("{\"ts\":\"2026-09-02T09:30:11.500+08:00\",\"level\":\"ERROR\",\"module\":\"network\",\"msg\":\"请求失败\",\"error\":\"SocketTimeoutException\",\"retries\":3,\"requestId\":\"req-20260902-000998\",\"user\":{\"phone\":\"13812345678\",\"email\":\"zhang.wei@example.com\"}}")
        appendLine("2026-09-02 09:30:12.000 [INFO] [Session] 会话已保存 sessionId=sess-4f8a91c2b3d7 items=12")
        appendLine("2026-09-02 09:30:12.900 [DEBUG] [Test] 这是一行用于验证超长行截断显示的日志：Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. 中文部分：这一行的长度超过了普通窗口宽度，用于验证横向滚动与截断策略，搜索与复制必须基于完整文本而不是截断后的文本。")
        appendLine("2026-09-02 09:30:13.000 [INFO] [Test] GBK 场景验证：中文内容正常显示，无乱码（本行为 UTF-8）")
        appendLine("2026-09-02 09:30:13.200 [INFO] [Cleanup] 分析会话已归档到本地，未离开设备")
        appendLine("2026-09-02 09:30:13.400 [DEBUG] [End] 示例日志结束 —— 共 40 行，试试：双击行快速建规则 / F8 跳转命中 / Ctrl+G 跳行号")
    }
}
