package com.qcmian.clipper.devtools.tools.apksign

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.ui.code.DevToolCodeField
import com.qcmian.clipper.core.ui.code.rememberCodeColors
import com.qcmian.clipper.core.ui.code.scanPlain
import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import com.qcmian.clipper.core.ui.theme.hintColor
import com.qcmian.clipper.devtools.api.DevTool
import com.qcmian.clipper.devtools.api.DevToolGroup
import com.qcmian.clipper.devtools.api.DevToolHost
import com.qcmian.clipper.devtools.api.DevToolMetadata
import com.qcmian.clipper.devtools.api.LocalDevToolPasteKey
import com.qcmian.clipper.devtools.api.writeBytesFile
import com.qcmian.clipper.devtools.ui.components.DevToolActionSpacer
import com.qcmian.clipper.devtools.ui.components.DevToolButton
import com.qcmian.clipper.devtools.ui.components.DevToolFileField
import com.qcmian.clipper.devtools.ui.components.DevToolMenuButton
import com.qcmian.clipper.devtools.ui.components.DevToolReportSource
import com.qcmian.clipper.devtools.ui.components.DevToolResultActions
import com.qcmian.clipper.devtools.ui.components.DevToolResultRow
import com.qcmian.clipper.devtools.ui.components.DevToolSectionDivider
import com.qcmian.clipper.devtools.ui.components.DevToolSegmentedControl
import com.qcmian.clipper.devtools.ui.components.DevToolSingleLineField
import com.qcmian.clipper.devtools.ui.components.DevToolTabBar
import com.qcmian.clipper.devtools.ui.components.DevToolToggle
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 有效期允许填的年数。上限只是一个护栏：`Date` 撑得住更远的日期，但再长没有意义，而填错一位数
 * （比如 `300`）会让到期日跨到看不见的年份上去。
 */
private const val MinValidityYears = 1
private const val MaxValidityYears = 100

/**
 * 路径或口令停下来多久才真去开密钥库。
 *
 * 两份输入都要防抖：**每敲一个字就重开一次密钥库**（读一次磁盘 + 解一遍），而且在还没敲完的时候
 * 就已经闪了几轮「无法读取」。250ms 足够盖住盲打。
 *
 * 打开 / 拖入 / 粘贴那种**完整的**选择不走防抖，见 `KeyStoreSession.choosePath`。
 */
private const val KeyStoreOpenDebounceMillis = 250L

/**
 * 这个工具的两页。
 *
 * 分页的判据是**手上有什么**，不是「编码 / 解码」那种方向：
 *  - [KeyStore]：我有一把钥匙，看它是什么（别名、证书、指纹），顺便把口令填上；
 *  - [Apk]：我有一个包，看它签没签；要重签就在这一页做。
 *
 * 不做成工具栏里的一枚分段控件（那是「同一页里改一个取值」）：换页换掉的是整块输入与整块结果，
 * 与 Base64 / URL 的两个方向是同一件事，所以用同一套页签。
 */
private enum class ApkSignTab(val title: String) {
    KeyStore("KeyStore信息"),
    Apk("APK"),
    New("新建KeyStore"),
}

/**
 * 一页上**一个**密钥库的全套状态：路径、口令（以及防抖之后那一份）、读出来的结果、选中的别名。
 *
 * 收成一个类，是因为它需要两份：`KeyStore信息` 页查看一个文件，签名页查看**它自己**那一份（见
 * [Content] 里那两个实例）。原先这些是几个散落的 `var`、两页共用，后果是签名页没有口令可填——
 * 只能先切到第一页输入口令，而「本次签名用哪把钥匙」与「当前查看的密钥库里是什么」本是两件事；
 * 共用还会让一个页面上的操作连带改变另一个页面的内容。
 */
@Stable
private class KeyStoreSession {
    var path by mutableStateOf<String?>(null)

    /**
     * **防抖之后**的那一份路径；读盘、判「能不能签」、真签名用的都是它。
     *
     * 用户可以在框里直接敲路径（见 `KeyStoreReadEffect`），敲一下就读一次磁盘是不行的：每敲一个
     * 字都去开一次文件，路径还没敲完就已经闪了几轮「无法读取」。所以敲出来的路径先停在 [path]，过
     * 一小会儿没再动才落到这里。
     *
     * 打开 / 拖入 / 粘贴 / 清除走 [choosePath]，**立刻**生效——那不是「正在敲字」。
     */
    var appliedPath by mutableStateOf<String?>(null)

    var password by mutableStateOf("")

    /** 防抖之后的那一份口令；真正拿去开密钥库的是它（见 `KeyStoreOpenDebounceMillis`）。 */
    var appliedPassword by mutableStateOf("")
    var outcome by mutableStateOf<KeyStoreOutcome?>(null)
    var selectedAlias by mutableStateOf<String?>(null)
    var reading by mutableStateOf(false)

    /**
     * 明确选定一个路径：打开 / 拖入 / 粘贴 / 清除都走这里，**不经过防抖**。
     *
     * 与用户手敲的区别就在这儿：这几种动作是一个完整的决定，没有「还没敲完」这回事，等 250ms 只会
     * 显得迟钝（尤其粘贴）。
     */
    fun choosePath(path: String?) {
        this.path = path
        appliedPath = path
    }

    /** 读出来的条目；没读出来时是空的。 */
    val entries: List<KeyStoreEntry> get() = (outcome as? KeyStoreOutcome.Ready)?.entries.orEmpty()

    /** 选中的那一条；没选中（或读不出来）时 `null`。 */
    val selectedEntry: KeyStoreEntry? get() = entries.firstOrNull { it.alias == selectedAlias }

    /** 读出来那个文件的容器类型（签名时要交给 `apksig`）。 */
    val storeType: String? get() = (outcome as? KeyStoreOutcome.Ready)?.storeType

    /** 这一次是没给口令就读的；决定「读不到证书」怎么解释，见 `CertificateAvailability`。 */
    val readWithoutPassword: Boolean get() = (outcome as? KeyStoreOutcome.Ready)?.readWithoutPassword ?: false
}

/**
 * Android 签名工具：看密钥库与 APK 的证书 / 指纹 / 签名方案，也能给 APK 重新签名。
 *
 * **只读优先**：打开一个密钥库或 APK **不会**改动任何东西；唯一会写盘的动作是「签名并另存为」，
 * 它走保存对话框、且强制换一个输出名（见 [signedFileNameOf]），永远不会盖掉输入的那个包。
 *
 * 口令这一路刻意收着走：
 *  - 口令框是个只打点、**没有任何复制入口**的输入框（见 [SecretField]）。这个应用本身就是剪贴板
 *    管理器，口令一旦进历史，就等于把私钥的口子留在常驻列表里；
 *  - 传给平台那一层的口令是 `CharArray`，签名一结束就地填零（见 `sign()`），不留一份等 GC 的
 *    `String` 副本。
 *
 * 指纹是本工具真正的高频用途（拿去各平台注册），因此那几行在结果列表里标成 `primary`：整行可点
 * 即复制，五种写法一行一种，不必先去别处转一次格式。
 *
 * 与其它工具一样**不吃某一种数据类型**（[acceptedDataTypes] 留空）：密钥库与 APK 都是二进制，而
 * 现有的探测类型里没有「文件」这一档。路径有三条来路，都由工具自己接：拖进卡片、打开对话框、
 * 剪贴板里的**文件条目**（`ClipItem.files`，见 `Content` 里那个 `LaunchedEffect`）。
 */
internal object ApkSignDevTool : DevTool {

    override val metadata: DevToolMetadata = DevToolMetadata(
        id = "apk-sign",
        name = "Android 签名",
        description = "看 KeyStore 与 APK 的证书、指纹和签名方案，指纹按各平台要的写法给全；" +
            "也可以给 APK 重新签名（只另存，不动原文件）。",
        group = DevToolGroup.GENERATOR,
        icon = ClipperIconKind.ANDROID,
    )

    @Composable
    override fun Content(input: ClipItem?, host: DevToolHost) {
        var tab by remember { mutableStateOf(ApkSignTab.KeyStore) }

        // 三个密钥库会话：`KeyStore信息` 那一页一个（看它里面是什么），签名那一页两个——一把是
        // **`新KeyStore`**（签完之后这个包用它是「新身份」），一把是轮替时的 **`KeyStore`**（这个包
        // 现在用的那把，低版本那一层仍由它签，见 `RotationRequest`）。**不共用**是有意的：三处要的是
        // 三件事，共用一个「当前密钥库」会让一处的动作改掉另一处看到的东西，还会让签名非去第一页填
        // 口令不可（见 `KeyStoreSession`）。
        val viewSession = remember { KeyStoreSession() }
        val signSession = remember { KeyStoreSession() }
        val currentSession = remember { KeyStoreSession() }

        // APK 这一侧：路径、**防抖之后**的那一份、验签结果，以及「正在验」。
        //
        // 与密钥库那边同一个道理（见 `KeyStoreSession.appliedPath`）：这个框也能手敲，而验签要把
        // 整个包读进来验一遍——每敲一个字重验一次是不行的。`chooseApk` 走的是明确选择，立刻生效。
        var apkPath by remember { mutableStateOf<String?>(null) }
        var appliedApkPath by remember { mutableStateOf<String?>(null) }
        var verifyOutcome by remember { mutableStateOf<VerifyOutcome?>(null) }
        var verifying by remember { mutableStateOf(false) }

        // 签名：勾了哪几个方案、正在签、上一次签的结果；以及这一次要不要把包从 `KeyStore` 轮替到
        // `新KeyStore`。
        var schemes by remember { mutableStateOf(SignScheme.entries.toSet()) }
        var signing by remember { mutableStateOf(false) }
        var signOutcome by remember { mutableStateOf<SignOutcome?>(null) }
        var rotating by remember { mutableStateOf(false) }

        /** 轮替链没写出来时的那个路径；`null` 表示没这回事（见 `sign` 里那一小段）。 */
        var lineageProblem by remember { mutableStateOf<String?>(null) }

        // 新建页的表单。放在**这里**而不是页面内部：切换页签会让页面离开组合、状态随之丢失，而
        // 填写中途查看其他页是常见操作。整份表单收成一个值对象，修改哪一项就替换哪一项。
        var form by remember { mutableStateOf(KeyStoreForm()) }
        var creating by remember { mutableStateOf(false) }
        var createOutcome by remember { mutableStateOf<CreateOutcome?>(null) }

        val scope = rememberCoroutineScope()

        /**
         * 明确选定一个 APK 路径：打开 / 拖入 / 粘贴 / 清空都走这里，**不经过防抖**——那不是「正在
         * 敲字」（与 `KeyStoreSession.choosePath` 同一条规矩）。
         */
        fun chooseApk(path: String?) {
            apkPath = path
            appliedApkPath = path
        }

        /**
         * 把路径按**内容**分流到它该去的那一格。
         *
         * 判据是开头几个字节（见 `documentKindOf`），不看扩展名：`.keystore` 里装的可能是 PKCS#12，
         * 而 APK 就是个 ZIP。读文件因此下到后台——只有几个字节，但终究是磁盘。
         *
         * 只给**没有落点信息**的那两条路用：从剪贴板记录进来，以及第一页那张卡片上的拖放（那页只有
         * 一个槽，按内容判反而能替用户翻到该去的那一页）。指定了某一格的拖放 / 打开走 [routeTo]。
         *
         * **无法辨认的内容也照常放入输入框**（不再只提示一句）：路径保留在框内，由读取它的那一方
         * 给出原因。那条原因常驻显示（密钥库页在结果区，签名页在该行下方），而状态栏提示会自动
         * 消失，无法承担这类需要持续可见的说明。用户也可以直接在框内修改路径后重试。
         *
         * @param navigate 放入之后是否**切换到对应页签**。「KeyStore信息」页只有一个格，粘入一个
         *   APK 无处安放，切换过去更直接（与条码工具「在编码页粘图就切到解码页」同一做法）；
         *   APK 页两个格都在视野内，切换反而会让人以为自己点错了。
         */
        fun route(paths: List<String>, navigate: Boolean) {
            val path = paths.firstOrNull() ?: return
            scope.launch {
                val kind = withContext(Dispatchers.Default) { documentKindOf(path) }
                when (kind) {
                    SignInputKind.KeyStore -> {
                        // 落到**当前页**的密钥库格：在签名页拖入是准备用于签名，在「KeyStore信息」页
                        // 拖入是查看其中内容。两页各有会话，互不覆盖（见 `KeyStoreSession`）。
                        //
                        // `navigate` 那条路（从剪贴板记录进来的一个密钥库）是「打开看看」，归第一页。
                        if (!navigate && tab == ApkSignTab.Apk) {
                            signSession.choosePath(path)
                        } else {
                            viewSession.choosePath(path)
                        }
                        if (navigate) tab = ApkSignTab.KeyStore
                    }

                    SignInputKind.Apk -> {
                        chooseApk(path)
                        if (navigate) tab = ApkSignTab.Apk
                    }

                    // 认不出来：落到**这一页**能收它的那一格（签名页归 APK 那一行，其余归密钥库
                    // 那一格），由那一格自己去说读不出来——理由见上面那段说明。
                    null -> if (tab == ApkSignTab.Apk) chooseApk(path) else viewSession.choosePath(path)
                }
            }
        }

        /**
         * 落到**指定**那一格：某一格上的拖放、以及某一格自己的「打开」按钮都走这里。
         *
         * 落点由**用户指定的那一格**决定，这里不做搬移：拖到哪一行就落在哪一行，点哪一格的文件夹
         * 就落在哪一格。内容不符时由该格给出原因（常驻显示），而不是转移到别处并另给一句会消失的
         * 提示——转移之后，用户看到的是「拖入的内容不见了」。
         *
         * @param keystore 密钥库那一格归哪个会话：签名页那格是 [signSession]，第一页那格是
         *   [viewSession]。两个槽各自独立，见 `KeyStoreSession`。
         */
        fun routeTo(paths: List<String>, kind: SignInputKind, keystore: KeyStoreSession) {
            val path = paths.firstOrNull() ?: return
            if (kind == SignInputKind.Apk) chooseApk(path) else keystore.choosePath(path)
        }

        /**
         * 「打开」某个槽的文件：挑完之后落到**那一槽**。
         *
         * 与拖放共用 [routeTo]，区别只在落点从哪来：按钮自带槽位，拖放由用户拖在哪一行决定。
         */
        fun pickInto(kind: SignInputKind, keystore: KeyStoreSession) {
            host.pickFileToOpen()?.let { routeTo(listOf(it), kind, keystore) }
        }

        // 从剪贴板记录进来：只取**路径**，绝不读内容——密钥库与 APK 都是二进制，当文本读只会得到
        // 一片乱码（`devToolText` 在这里正是用不上的那一个）。
        LaunchedEffect(input) {
            input?.files?.firstOrNull()?.let { route(listOf(it), navigate = true) }
        }

        // 三个会话各自读取对应的文件。三份都在此生效：签名页的两把钥都不等用户切页才读——用户可能
        // 一开始就在签名页拖入文件。
        KeyStoreReadEffect(viewSession)
        KeyStoreReadEffect(signSession)
        KeyStoreReadEffect(currentSession)

        // 状态栏左段那份「内容从哪来」**不要**（空串是「这一段我不要」，见 `DevToolHost.reportSource`）：
        // 这个工具整页都是**文件**，「来自文件 · 路径」在这里没有信息量——用户要看的是这个密钥库 /
        // 这个包是什么状态，那是右段的事（见下面那段 `reportStatus`）。
        DevToolReportSource(host, "")

        // 本工具的状态只在一处显示：状态栏右段（`DevToolHost.reportStatus`，常驻到下一次报告）。
        // 页面里不再复述同一句话（原先签名按钮下方还有一行，已删除）。
        //
        // 报的是当前页签的状态：切换页签即更换，恢复正常时为 `null`（清除）。一页存在多个问题时按
        // 「先输入、后结果」排序，以分号连接，每段带主体名——否则同时出现两个「无法读取」时无法判断
        // 该处理哪一个。
        //
        // **只说「读这个文件的结果」，不说「还差什么才能签名」**：这一页最常见的用法是**只看一个包
        // 的签名情况**（见 `ApkPage` 的说明），此时常驻一句「需要选一个 KeyStore 用于签名」与用户
        // 当下在做的事无关，只会挤占状态栏。那句话改由**点签名按钮**时按需给出（见 `sign`）。
        val state = when (tab) {
            ApkSignTab.KeyStore ->
                (viewSession.outcome as? KeyStoreOutcome.Failed)?.let { "KeyStore 无法读取：${it.message}" }

            ApkSignTab.Apk -> listOfNotNull(
                (signSession.outcome as? KeyStoreOutcome.Failed)?.let { "KeyStore 无法读取：${it.message}" },
                (currentSession.outcome as? KeyStoreOutcome.Failed)
                    ?.takeIf { rotating }?.let { "KeyStore 无法读取：${it.message}" },
                (verifyOutcome as? VerifyOutcome.Failed)?.let { "APK 无法读取：${it.message}" },
                (signOutcome as? SignOutcome.Failed)?.let { "签名失败：${it.message}" },
                // 包签出来了、链没写出来：这件事与「签名成不成功」无关，但它得一直挂着——以后再要
                // 接着轮替，先得有这么一条链（见 `lineageFileNameOf`）。
                lineageProblem?.let { "轮替链没写成：$it" },
            ).takeIf { it.isNotEmpty() }?.joinToString("；")

            ApkSignTab.New ->
                (createOutcome as? CreateOutcome.Failed)?.let { "创建失败：${it.message}" }
        }
        LaunchedEffect(state) {
            host.reportStatus(state)
        }

        // 本工具的**粘贴**入口。三处文件输入都是 `DevToolFileField`（见 `ApkPage` /
        // `KeyStorePage`），没有一个是 `DevToolInputField`——而窗口层的 `⌘V` 只发给**登记过**的
        // 那一块（见 `DevToolPasteKey`）：不登记，本工具就无法接收粘贴的文件。处理方式不变：剪贴板
        // 中是文件则按内容分流，不是文件则交回系统（路径框仍可粘贴路径）。
        //
        // 三个页签共用**这一处**登记（不再按页签切换登记）：整个工具只有这一份，窗口层保留的也是它。
        //
        // `navigate` 按页签决定：签名页两个格都在视野内，切换反而会让人以为自己点错了；另外两页
        // 只放得下其中一个格，切换过去更直接（与条码工具「在编码页粘图就切到解码页」同一做法）。
        //
        // 用 `rememberUpdatedState` 取当前那份 `route`：登记只做一次，而它每次重组都是新的闭包，
        // 捕旧了会让粘贴按上一帧的状态去安置文件（同 `DevToolInputField` 里的说明）。
        val pasteKey = LocalDevToolPasteKey.current
        val routePasted by rememberUpdatedState<(List<String>) -> Unit> { paths ->
            route(paths, navigate = tab != ApkSignTab.Apk)
        }
        DisposableEffect(pasteKey) {
            val unregister = pasteKey?.register {
                val paths = host.clipboardFilePaths()
                if (paths.isEmpty()) {
                    false
                } else {
                    routePasted(paths)
                    true
                }
            }
            onDispose { unregister?.invoke() }
        }

        // APK 路径的防抖：见上面那段说明（`KeyStoreOpenDebounceMillis` 是同一道闸）。
        LaunchedEffect(apkPath) {
            delay(KeyStoreOpenDebounceMillis)
            appliedApkPath = apkPath
        }

        // 验签。换一个 APK 就重验一次：这一页的结论跟着文件走，留着上一个包的结论只会误导。
        LaunchedEffect(appliedApkPath) {
            val path = appliedApkPath
            if (path == null) {
                verifyOutcome = null
                verifying = false
                return@LaunchedEffect
            }
            verifying = true
            verifyOutcome = withContext(Dispatchers.Default) { verifyApk(path) }
            verifying = false
        }

        /** 这个包**现在**的签名证书；没签名、或没读出来时为 `null`（轮替要靠它认 `KeyStore` 对不对）。 */
        val apkSigner = (verifyOutcome as? VerifyOutcome.Ready)?.signers?.firstOrNull()?.certificate

        /**
         * 轮替这一栏还差什么；没勾轮替时恒为 `null`（判据本身是纯函数，见 `rotationProblem`）。
         *
         * 其中一条是硬的：**已经轮替过的包不给再轮一次**。本工具只做第一次轮替——再轮一次要在链上
         * 再挂一代，而且低版本那一层得继续由**原始**那把钥签（否则 28–32 的设备认的还是原始证书，
         * 会直接拒升级），那是 apksig 另一套「按 API 分档的定向签名者」的用法。这个工具做不了，
         * 就不该让人签出一个会被平台拒掉的包。
         */
        val rotationMissing = if (!rotating) {
            null
        } else {
            rotationProblem(
                apkSigner = apkSigner,
                alreadyRotated = (verifyOutcome as? VerifyOutcome.Ready)?.rotation?.isNotEmpty() == true,
                keystorePath = currentSession.appliedPath,
                keystoreFailed = currentSession.outcome is KeyStoreOutcome.Failed,
                currentEntry = currentSession.selectedEntry,
                password = currentSession.password,
            )
        }

        /**
         * 用来签名的那把钥在页面上叫什么。**不轮替时只有一把，就叫 `KeyStore`**；轮替时它是要**换上**
         * 的那把，改叫 `新KeyStore`（另一把「这个包现在用的」才叫 `KeyStore`，见 `ApkPage`）。
         *
         * 下面的报错话术跟着它走：名字与行标签对不上时，「需要选一个 KeyStore」在轮替的那一页上指哪
         * 一把都说得通，而这两件事要做的事完全不同（一把去选、一把去换）。
         */
        val newKeyName = if (rotating) "新KeyStore" else "KeyStore"

        /**
         * 还差什么才能签名；齐全时 `null`。
         *
         * 签名要五样东西齐：一个 APK、一把钥匙、一个**私钥**条目、一个密码、至少一个签名方案；勾了
         * 轮替则还要一组 `KeyStore`（见 [rotationProblem]）。一个方案都不勾时 apksig 会直接抛出来，
         * 这里先拦住——那不是一个「失败」，是一个还没填完的表单。
         *
         * 它**只在点下签名按钮那一刻**被读（见 [sign]），不进状态栏：这一页最常见的用法是只看一个
         * 包的签名情况，把「你要先选一个 KeyStore」常驻在那儿，对只看的人是一句无关的话。按钮因此
         * 一直接得动，缺什么就点一下问出来。
         */
        val signProblem: String? = when {
            appliedApkPath == null -> "需要先选一个 APK"
            signSession.appliedPath == null -> "需要选一个 $newKeyName 用于签名"
            signSession.outcome is KeyStoreOutcome.Failed -> "这个 $newKeyName 无法读取，换一个再签"
            signSession.selectedEntry?.isKeyEntry != true -> "这个 $newKeyName 里没有可用于签名的私钥条目"
            signSession.password.isEmpty() -> "需要填写 $newKeyName 的密码才能签名"
            schemes.isEmpty() -> "至少需要勾选一个签名方案（建议 v2 + v3）"
            rotationMissing != null -> rotationMissing
            else -> null
        }

        /**
         * 签名并另存。
         *
         * 先询问保存位置再执行：用户在该步骤取消时，不应白白完成一次签名（读私钥、算摘要、重写整个
         * ZIP 需要几百毫秒到几秒）。整段在后台线程上执行。
         *
         * 缺东西时**就地说明**（[signProblem]）而不是把按钮灰着：一个点不动又不说明原因的按钮是最
         * 难受的一种控件（同「新建KeyStore」页那个按钮）。判在问保存位置之前，所以缺东西时不会弹出
         * 对话框，也不会写盘。
         */
        fun sign() {
            signProblem?.let {
                host.showStatus(it)
                return
            }
            // 用**验过的那份**：框里可能刚被改过，而上面的判断用的是验签结果（同 `appliedPath`
            // 那条说明——不能拿一个路径去签、拿另一个的结论来判）。
            val apk = appliedApkPath ?: return
            // 用**防抖之后**那份路径：它才是刚被读过、别名也跟着它挑好的那个文件（见
            // `KeyStoreSession.appliedPath`）。用框里那一刻的文本会出现「拿这个路径去签、拿另一个
            // 的别名」这种错配。
            val keystore = signSession.appliedPath ?: return
            val alias = signSession.selectedEntry?.alias ?: return
            val storePassword = signSession.password
            // 轮替那一头同样用**防抖之后**的路径与它挑好的别名（同上面两条说明）：链要用这把钥的
            // 私钥签，拿错了就是一条起错头的链。下面这两个 `?: return` 与上面那几条一样只是兜底
            // ——缺东西在 `signProblem` 那一关就说完了，走不到这儿。
            val currentKeyStore = if (rotating) currentSession.appliedPath ?: return else null
            val currentAlias = if (rotating) currentSession.selectedEntry?.alias ?: return else null
            val currentStoreType = currentSession.storeType
            val currentStorePassword = currentSession.password
            scope.launch {
                val target = host.pickFileToSave(signedFileNameOf(apk)) ?: return@launch
                if (target == apk) {
                    host.showStatus("输出不能就是原文件——换个名字，原包要留着")
                    return@launch
                }
                // 口令数组到**真动手**这一刻才建（同新钥那一条）：上面两个提前返回都没建过它。
                val current = if (currentKeyStore != null && currentAlias != null) {
                    RotationRequest(
                        keystorePath = currentKeyStore,
                        storeType = currentStoreType,
                        storePassword = currentStorePassword.toCharArray(),
                        keyAlias = currentAlias,
                        keyPassword = currentStorePassword.toCharArray(),
                    )
                } else {
                    null
                }
                signing = true
                signOutcome = null
                lineageProblem = null
                val request = SignRequest(
                    apkPath = apk,
                    outPath = target,
                    keystorePath = keystore,
                    storeType = signSession.storeType,
                    storePassword = storePassword.toCharArray(),
                    keyAlias = alias,
                    // 密钥库口令与别名口令在 PKCS#12 里**必须**相同（格式本身如此），在 JKS 里也几乎
                    // 总是一致。这里先按同一个传入；两者确实不同时，下方报错会指出。
                    keyPassword = storePassword.toCharArray(),
                    schemes = schemes,
                    rotation = current,
                )
                val outcome = withContext(Dispatchers.Default) { signApk(request) }
                // 口令用完就擦：`request` 里那几份数组是这次运算唯一的副本（轮替时是四份）。
                request.storePassword.fill('\u0000')
                request.keyPassword.fill('\u0000')
                request.rotation?.storePassword?.fill('\u0000')
                request.rotation?.keyPassword?.fill('\u0000')
                signing = false
                signOutcome = outcome
                // 只报**成功**那一声：它是「刚刚发生了什么」，一闪而过没关系。失败那句不在这里报——
                // 整句原因统一由状态栏右段常驻地说（见 `Content` 里那段），这里再说一遍就是两处重复。
                if (outcome is SignOutcome.Done) {
                    // 轮替链另存一份（见 `lineageFileNameOf`）：以后再要接着轮替，先得有这么一条链，
                    // 所以值得从包里拿出来单独放。写不成**不影响这次签名**——链也在包里——所以只
                    // 如实说一句，由状态栏右段常驻地挂着（临时提示会飘走，那是个要处理的遗留问题）。
                    val lineage = outcome.lineage
                    var lineagePath: String? = null
                    if (lineage != null) {
                        val path = lineageFileNameOf(outcome.outPath)
                        if (writeBytesFile(path, lineage)) lineagePath = path else lineageProblem = path
                    }
                    host.showStatus(
                        when {
                            lineagePath != null -> "已签好：${outcome.outPath}；轮替链：$lineagePath"
                            lineageProblem != null -> "已签好：${outcome.outPath}（轮替链没能另存，见状态栏）"
                            else -> "已签好：${outcome.outPath}"
                        }
                    )
                }
                // 把**刚生成的包**放入输入位：本页结论跟随文件，上面那个 effect 会重新验签一次，
                // 用户立即看到本次签名是否成功。文件名带 `-signed`，不会与输入文件混淆。轮替过的包
                // 还能在结果区上方看到刚接出来的那条链（见 `RotationChain`）。
                if (outcome is SignOutcome.Done) chooseApk(outcome.outPath)
            }
        }

        /**
         * 建一个新密钥库。
         *
         * 成功后**就地收尾**：结论只显示在下方那一行提示里（含完整路径），不切换页签、表单也不动
         * ——用户通常还要继续调整，或紧接着再建一个，切走只会让他再切回来。这一页也不自行绘制结果：
         * 那是「KeyStore信息」页的职责，同一份内容在两处绘制，早晚会不一致。
         *
         * 也**不**把新建的文件填入「KeyStore信息」页。建一个密钥库与读一个密钥库是两件事：那一页
         * 回答「我手上这个文件里是什么」，填进去等于替用户宣布「这就是你要看的那个文件」，而他建完
         * 可能只是收起来，也可能接着建第二个（那时填的是哪一个？）。
         */
        fun create() {
            val problem = keyStoreRequestProblem(
                outPath = form.outPath,
                alias = form.alias,
                password = form.password,
                confirmPassword = form.confirmPassword,
                subject = form.subject,
            )
            if (problem != null) {
                host.showStatus(problem)
                return
            }
            val years = form.validityYears.toIntOrNull()
            if (years == null || years !in MinValidityYears..MaxValidityYears) {
                host.showStatus("有效期填 $MinValidityYears 到 $MaxValidityYears 之间的年数")
                return
            }
            // 覆盖许可有两种来路（见 `KeyStoreRequest.overwrite`）：保存对话框里那一句「要覆盖吗」，
            // 或者**上一次点完拿到的是「已经有文件」**——那次点击本身就是在回答那个问题（按钮那时已
            // 经是「覆盖」）。两种都在这里合流。
            val overwrite = form.overwriteConfirmed || createOutcome is CreateOutcome.Exists
            scope.launch {
                creating = true
                createOutcome = null
                val request = KeyStoreRequest(
                    outPath = form.outPath,
                    storeType = form.format.keyStoreType,
                    alias = form.alias,
                    password = form.password.toCharArray(),
                    algorithm = form.algorithm,
                    validityDays = years * 365,
                    subject = form.subject,
                    overwrite = overwrite,
                )
                val outcome = withContext(Dispatchers.Default) { createKeyStore(request) }
                // 口令用完就擦：`request` 里那份数组是这次运算唯一的副本（同签名那条路）。
                request.password.fill('\u0000')
                creating = false
                createOutcome = outcome
                // 只报成功那一声（`Done` 那句）。**失败不在这里报**——整句原因统一由状态栏右段常驻地
                // 说（见 `Content` 里那段）；「已经有文件」也不报：这事要在**面板上**说（按钮跟着变
                // 成「覆盖」），一闪而过的提示没法承担「再点一次」这个动作。
                //
                // 成功这一句是**必要的**：本页不切换页签、也不把路径与口令填入「KeyStore信息」页
                // ——建一个密钥库与读一个密钥库是两件事，这里只负责把手上这个建出来（见该函数的
                // 说明），所以它得自己报一声。
                if (outcome is CreateOutcome.Done) host.showStatus("已创建：${outcome.outPath}")
            }
        }

        Column(Modifier.fillMaxSize()) {
            DevToolTabBar(
                options = ApkSignTab.entries,
                selected = tab,
                optionLabel = { it.title },
                onSelect = { tab = it },
            )

            Spacer(Modifier.height(12.dp))

            when (tab) {
                ApkSignTab.KeyStore -> KeyStorePage(
                    keyStorePath = viewSession.path,
                    password = viewSession.password,
                    onPasswordChange = { viewSession.password = it },
                    outcome = viewSession.outcome,
                    entries = viewSession.entries,
                    selectedAlias = viewSession.selectedAlias,
                    onSelectAlias = { viewSession.selectedAlias = it },
                    readerBusy = viewSession.reading,
                    readWithoutPassword = viewSession.readWithoutPassword,
                    onOpen = { host.pickFileToOpen()?.let { route(listOf(it), navigate = true) } },
                    onClear = { viewSession.choosePath(null) },
                    onPathChange = { viewSession.path = it },
                    onDropFiles = { paths -> route(paths, navigate = true) },
                    host = host,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )

                ApkSignTab.Apk -> ApkPage(
                    apkPath = apkPath,
                    // 敲进去的只动显示值，让防抖那一层决定何时真去验（见 `appliedApkPath`）。
                    onApkPathChange = { apkPath = it },
                    keyStorePath = signSession.path,
                    // 敲进去的只动 `path`，让防抖那一层决定何时真去读（见 `KeyStoreSession`）。
                    onKeyStorePathChange = { signSession.path = it },
                    verifying = verifying,
                    verifyOutcome = verifyOutcome,
                    keyStorePassword = signSession.password,
                    onKeyStorePasswordChange = { signSession.password = it },
                    aliases = signSession.entries.map { it.alias },
                    selectedAlias = signSession.selectedAlias,
                    onSelectAlias = { signSession.selectedAlias = it },
                    signing = signing,
                    schemes = schemes,
                    onToggleScheme = { scheme, enabled ->
                        // 轮替要 v3（链只存在于 v3 块里）。取消 v3 就等于取消轮替——**说一声**，
                        // 无声地无视那一下点击比取消更糟。
                        if (scheme == SignScheme.V3 && !enabled && rotating) {
                            rotating = false
                            host.showStatus("轮替要用 v3 签名，已一并取消轮替")
                        } else {
                            schemes = if (enabled) schemes + scheme else schemes - scheme
                        }
                    },
                    rotating = rotating,
                    onToggleRotation = { enabled ->
                        rotating = enabled
                        // 勾上轮替顺手把 v3 带上：链只在 v3 块里，而「勾了轮替却少个 v3」本就是个
                        // 填不完的表单——顺手补齐比事后报一句「还差 v3」清楚。
                        if (enabled) schemes = schemes + SignScheme.V3
                    },
                    currentKeyStorePath = currentSession.path,
                    onCurrentKeyStorePathChange = { currentSession.path = it },
                    currentKeyStorePassword = currentSession.password,
                    onCurrentKeyStorePasswordChange = { currentSession.password = it },
                    currentAliases = currentSession.entries.map { it.alias },
                    currentSelectedAlias = currentSession.selectedAlias,
                    onSelectCurrentAlias = { currentSession.selectedAlias = it },
                    // 两个输入框上的拖放：**拖到哪一行就落在哪一行**（落点由用户指定的格决定，见
                    // `routeTo`）。不再按内容转移到另一个框——转移会让人以为拖入的内容丢失，而内容
                    // 不符本就该由该格说明。
                    onDropInto = { paths, kind -> routeTo(paths, kind, signSession) },
                    onDropIntoCurrent = { paths -> routeTo(paths, SignInputKind.KeyStore, currentSession) },
                    // 「打开」按钮自带槽位，落点由它定：都写在**签名那一侧**的会话上。
                    onOpenApk = { pickInto(SignInputKind.Apk, signSession) },
                    onOpenKeyStore = { pickInto(SignInputKind.KeyStore, signSession) },
                    onOpenCurrentKeyStore = { pickInto(SignInputKind.KeyStore, currentSession) },
                    onClearApk = { chooseApk(null) },
                    onClearKeyStore = { signSession.choosePath(null) },
                    onClearCurrentKeyStore = { currentSession.choosePath(null) },
                    onSign = ::sign,
                    host = host,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )

                ApkSignTab.New -> NewKeyStorePage(
                    form = form,
                    // 一改表单就把上一次的结论撤掉：那个「覆盖」按钮（以及它代表的那份许可）是对
                    // **上一个路径**说的，换了路径就不再成立。
                    onFormChange = {
                        form = it
                        createOutcome = null
                    },
                    creating = creating,
                    outcome = createOutcome,
                    onPickOutPath = {
                        host.pickFileToSave(defaultKeyStoreFileName(form.format))?.let { picked ->
                            form = form.copy(
                                outPath = picked,
                                // 那个对话框问过「要覆盖吗」，用户答过了。记下**是哪个路径**上的
                                // 回答（见 `KeyStoreForm.confirmedOutPath`）。
                                confirmedOutPath = picked,
                                // 名字里的扩展名反着同步一次：用户在对话框里把它写成 `.jks`，那就是
                                // 想要 JKS。选项跟着他改，而不是继续宣称 PKCS#12 再写一个名字与内容
                                // 对不上的文件。
                                format = storeFormatOfPath(picked) ?: form.format,
                            )
                        }
                    },
                    onCreate = ::create,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

/**
 * 新建那一页的表单。
 *
 * 字段与分组大体照 Android Studio 的「新建密钥库」对话框：保存路径、口令 + 确认、别名、有效期，
 * 然后是证书那六项。**字段名有调整**，因为照抄的那几个中文标签含义不清：
 *
 *  - `名字与姓氏` → `姓名`（含义相同，更短）；
 *  - `组织单位` / `组织` → `部门` / `组织`（原措辞无法区分：DN 中 `O` 是组织本身、`OU` 是它
 *    下属的部门，后者包含于前者）。
 *
 * **默认值也不再照抄**（AS 把六格全填成 `Unknown`，用户得先删六个词）：只有姓名给一个能直接用的
 * 值，其余五格**留空**——空着的项不会写进证书（见 [subjectOf]），也就没有东西要删。
 *
 * **AS 没有的两行是这里多出来的**：`容器格式` 与 `加密算法`。AS 一律写 JKS + RSA 2048，一个字都
 * 不解释；而这页把两个选项都摆出来、**每项配一句人话**（`StoreFormat.advice` / `KeyAlgorithm.advice`）
 * ——「JKS 还是 PKCS#12」「RSA 还是 EC」都需要用户选择，因此必须说明差别，而不是替其固定取值后
 * 让其另查资料。
 *
 * 收成一个值对象而不是散成十几个 `var`：每一格都要**同时**传给页面（漏传一项就是一处死字段），
 * 整份传就只有一个口子。
 */
internal data class KeyStoreForm(
    val outPath: String = "",
    /**
     * 用户在**系统保存对话框**里确认过的那个路径（那里问过「要覆盖吗」，他答过了）。
     *
     * 存**路径本身**而不是一个布尔量：判据要的是「要写的还是不是他确认过的那一个」，而不是「名字有
     * 没有被改过」——扩展名在 `.p12` 与 `.jks` 之间来回切一次，会经历一次改、再一次改回，落到的是
     * 同一个文件，原先那声「要覆盖」仍然算数。（按「有没有被改过」判，来回切一次就把许可弄丢了，
     * 那正是「选了替换还是盖不掉」的一个来路。）
     *
     * 手敲 / 粘贴过的路径为 `null`：那条路没人被问过。见 `KeyStoreRequest.overwrite`。
     */
    val confirmedOutPath: String? = null,
    val format: StoreFormat = StoreFormat.Pkcs12,
    val algorithm: KeyAlgorithm = KeyAlgorithm.Rsa2048,
    val password: String = "",
    val confirmPassword: String = "",
    val alias: String = "key0",
    val validityYears: String = (DefaultValidityDays / 365).toString(),
    val commonName: String = "Android Key",
    val organizationalUnit: String = "",
    val organization: String = "",
    val locality: String = "",
    val state: String = "",
    val country: String = "",
) {
    /** 证书那六项拼出来的 DN（见 `subjectOf`）。 */
    val subject: String get() = subjectOf(commonName, organizationalUnit, organization, locality, state, country)

    /** 这一次要写的文件，用户已经给过覆盖许可（见 [confirmedOutPath]）。 */
    val overwriteConfirmed: Boolean get() = outPath.isNotEmpty() && outPath == confirmedOutPath

    /** 换容器：路径的扩展名跟着一起换（见 `withStoreExtension`）。 */
    fun withFormat(format: StoreFormat): KeyStoreForm =
        copy(format = format, outPath = withStoreExtension(outPath, format))
}



/**
 * 密钥库页：开一个密钥库，看它的条目、证书与指纹。
 *
 * 结果区打印的是 **`keytool -list -v` 的原文**（[keytoolText]）——整段文本，不是「标签 + 值」的
 * 列表。理由见那个函数的说明，一句话：这份东西的形状就是它的用途（跟 keytool 的输出、跟别人的
 * 截图对着看），而它里面有多行的扩展段与靠空行分隔的版式。
 *
 * 别名那一栏留着，但它管的是**签名**要用哪把钥匙（见 `ApkPage`），不筛这一页的结果。
 */
@Composable
private fun KeyStorePage(
    keyStorePath: String?,
    password: String,
    onPasswordChange: (String) -> Unit,
    outcome: KeyStoreOutcome?,
    entries: List<KeyStoreEntry>,
    selectedAlias: String?,
    onSelectAlias: (String) -> Unit,
    readerBusy: Boolean,
    /** 这一次是没给口令就读的；决定「读不到证书」怎么解释，见 `CertificateAvailability`。 */
    readWithoutPassword: Boolean,
    onOpen: () -> Unit,
    onClear: () -> Unit,
    /** 用户在这一格里敲路径（只动显示值，真正去读由防抖那一层决定，见 `KeyStoreReadEffect`）。 */
    onPathChange: (String) -> Unit,
    /** 文件拖到了这一格（见 `route`：按内容分流，一个包掉进来会替用户翻到 APK 那一页）。 */
    onDropFiles: (List<String>) -> Unit,
    host: DevToolHost,
    modifier: Modifier,
) {
    val entry = entries.firstOrNull { it.alias == selectedAlias }
    // 下面几处提示都要说「是哪个别名」：取一次，比每处各写一遍 `entry?.alias.orEmpty()` 干净。
    val entryAlias = entry?.alias.orEmpty()

    Column(modifier) {
        // 与签名页的两个文件格**同一个控件**（见 `DevToolFileField`）：三处文件输入外观一致，不必
        // 区分何处是卡片、何处是输入框。它因此也支持直接输入路径——本页读盘本就走防抖（见
        // `KeyStoreReadEffect`），输入与「打开」走同一条路径。
        //
        // 本页原先那张卡片是**最后一块** `DevToolInputField`：替换之后三个页签都不再有输入区，
        // `⌘V` 的入口只有工具自己登记的那一处（见 `Content` 里那段）。
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowLabel("KeyStore")
            DevToolFileField(
                value = keyStorePath.orEmpty(),
                onValueChange = onPathChange,
                placeholder = "拖拽、粘贴、打开KeyStore文件，或输入路径",
                host = host,
                onDropFiles = onDropFiles,
                onOpen = onOpen,
                onClear = onClear,
                clearTooltip = "清除 KeyStore",
                // 宽度给这一行**剩余**的部分（与签名那一页同款）：右缘与路径长短无关。
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            // 两个标签都走 `RowLabel`：与上面那一格的文件行同一个宽度，字段左缘才对得齐。
            RowLabel("密码")
            SecretField(
                value = password,
                onValueChange = onPasswordChange,
                placeholder = "可留空",
                modifier = Modifier.width(200.dp),
            )
            DevToolActionSpacer()
            // 别名与口令排在同一行：它们是同一件事的两半（「哪把钥匙」与「它的口令」），排在一起
            // 修改时不必上下对照。
            RowLabel("别名")
            DevToolActionSpacer()
            DevToolMenuButton(
                label = selectedAlias ?: "—",
                options = entries.map { it.alias },
                selected = selectedAlias ?: "—",
                optionLabel = { it },
                onSelect = onSelectAlias,
                // 只有一个别名时不必点开一列只有一项的菜单。
                enabled = entries.size > 1,
            )
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.height(6.dp))

        // 证书此刻是什么状态。它决定上面那句提示与下面结果区怎么说——**三种状态三句话**，见
        // `CertificateAvailability`：把「被口令锁住」说成「没有证书」，是这一页最容易犯、也最误导人
        // 的一个错（PKCS#12 留空时就是这样）。
        val certificateState = entry?.let { certificateAvailabilityOf(it, readWithoutPassword) }

        Text(
            text = when {
                password.isNotEmpty() ->
                    "密码只在这一次运算里用，不进历史，也没有任何复制它的入口。"

                // 留空**不**等于看不了——JKS 的证书是明文存的，不给口令照样能看。只有 PKCS#12 会把
                // 证书一起加密（实测 `getCertificate` 返回 null），那时得说清楚是「要口令」而不是
                // 一句笼统的「留空就行」。
                certificateState == CertificateAvailability.NeedsPassword ->
                    "这个 KeyStore 把证书也一起加密了（PKCS#12 就是这么存的），填上密码才看得到。"

                else -> "密码留空能看证书与指纹；要拿去签名才需要填。留空时不做完整性校验——" +
                    "「能打开」只说明结构没坏，不代表密码对。"
            },
            fontSize = 11.sp,
            color = MaterialTheme.hintColor,
        )

        Spacer(Modifier.height(10.dp))

        // 容器类型与提供方从这次读出来的结果里取，不另开参数：它们就是 `outcome` 的一部分。
        val ready = outcome as? KeyStoreOutcome.Ready
        when {
            keyStorePath == null -> CenteredHint(
                text = "打开一个 KeyStore 后，这里显示它的证书与指纹",
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            readerBusy -> CenteredHint("正在读…", Modifier.fillMaxWidth().weight(1f))

            // 只陈述事实，**不复述原因、也不写「原因见状态栏」这类指路话**：原因在状态栏右段（见
            // `Content` 里那段）；此处只说明本区域的状态，指路话会被读作承认错误。
            outcome is KeyStoreOutcome.Failed -> FailedHint(
                message = "这个 KeyStore 无法读取",
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            // 读不到证书，而这次没给口令：**绝大多数**是 PKCS#12 把它锁住了（证书和私钥一起塞在加密
            // 的 safe bag 里）。说「填口令再看」，而不是断言它没有——那时打印出来的会是一份**缺了
            // 证书**的 keytool 文本，比不打印更容易被当成「这文件就是这样」。
            certificateState == CertificateAvailability.NeedsPassword -> CenteredHint(
                text = "别名「${entryAlias}」的证书要密码才读得出来——在上面填上 KeyStore 密码再看",
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            // 给了口令仍读不到，才是确实没有（JKS 允许只放一把私钥）：说明缺什么，而不是留空。
            certificateState == CertificateAvailability.Absent -> CenteredHint(
                text = "别名「$entryAlias」下没有证书，只有私钥",
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            ready == null || ready.entries.isEmpty() -> CenteredHint(
                text = "这个 KeyStore 里没有条目",
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            // 结果就是 `keytool -list -v` 的原文（见 `keytoolText`）：条目一条不落、含条目之间的
            // 星号线。因此这里**不**按选中的别名过滤——`keytool` 也没有这个过滤，而「这一页打印的
            // 是什么」有一个唯一、可对照的答案，比挑选几段出来更有用。
            // 别名那一栏仍然有用：它挑的是**签名**要用哪把钥匙。
            else -> {
                val keytoolOutput = keytoolText(
                    storeType = ready.storeType,
                    providerName = ready.providerName,
                    entries = ready.entries,
                    storePath = keyStorePath,
                )
                DevToolCodeField(
                    label = "结果",
                    value = keytoolOutput,
                    onValueChange = {},
                    editable = false,
                    // 不折行：`扩展` 那一段是十六进制转储，`0000: A4 D4 …` 与右侧 ASCII 列以空格
                    // 对齐；折行会使两列错位，而纵向对照正是这份文本的读法。
                    softWrap = false,
                    // 无行号、不折叠：这不是代码，行号只会多出一列无关数字。
                    lineNumbers = false,
                    folding = false,
                    scan = ::scanPlain,
                    placeholder = "打开一个 KeyStore 后，这里显示 keytool -list -v 的内容",
                    actions = {
                        DevToolResultActions(
                            value = keytoolOutput,
                            host = host,
                            suggestedFileName = "keystore.txt",
                        )
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

/**
 * APK 页：验一个包，也可以用同一把钥匙把它重签一遍。
 *
 * 这一页同时是「验签」与「签名」的落点，因为它们是同一件事的两头：**先知道这个包现在是什么状态，
 * 再决定要不要重签**。分成两页会让人把同一个包放两次。
 *
 * 顶上那两格（APK、KeyStore）都是 `DevToolFileField`。它们**都不登记**窗口层的粘贴——该入口由工具
 * 自己登记一处（见 `Content` 里那段 `register`）：窗口层只保留最后登记的那一块（见
 * `DevToolPasteKey`），而这里的落点需要按**内容**分流（APK 还是密钥库），不能取决于登记顺序。
 *
 * 签名那一行排在结果区**下面**，且是主按钮：它是这一页唯一会写盘的动作，摆显眼是对的。按钮
 * **一直接得动**（只在签名进行中禁用）：缺东西时点一下就把缺的那一项说出来（见 `sign`），而不是
 * 灰着不解释；真正要写盘还得先在保存对话框里确认一次，所以也不会「没看清结论就点错」。
 */
@Composable
private fun ApkPage(
    apkPath: String?,
    /** 用户在这一格里敲路径（只动显示值，真正去验由防抖那一层决定，见 `Content` 里那个 effect）。 */
    onApkPathChange: (String) -> Unit,
    keyStorePath: String?,
    /** 用户在这一格里敲路径（只动显示值，真正去读由防抖那一层决定，见 `KeyStoreSession`）。 */
    onKeyStorePathChange: (String) -> Unit,
    verifying: Boolean,
    verifyOutcome: VerifyOutcome?,
    /** 这一页**自己**的密钥库口令（见 `KeyStoreSession`）：签名要的东西都在这一页上，不必切页。 */
    keyStorePassword: String,
    onKeyStorePasswordChange: (String) -> Unit,
    /** 这一页自己那个密钥库里的别名；用来挑签名用哪把钥匙。 */
    aliases: List<String>,
    selectedAlias: String?,
    onSelectAlias: (String) -> Unit,
    /** 正在签（此时按钮禁用，旁边多一句「正在签…」）。 */
    signing: Boolean,
    schemes: Set<SignScheme>,
    onToggleScheme: (SignScheme, Boolean) -> Unit,
    /** 这一次要不要把包从 `KeyStore` 轮替到 `新KeyStore`（见 `RotationRequest`）。 */
    rotating: Boolean,
    onToggleRotation: (Boolean) -> Unit,
    /**
     * `KeyStore` 那一行（轮替时才画）的三样，与 `新KeyStore` 那一行**同构**（同 `KeyStoreRow`）。
     *
     * 收在同一个页面里是因为轮替要把两把钥摆在一起看：哪把是「现在用的」、哪把是要换上的，分开摆
     * 就得靠记忆对上号（见 `RotationRequest`——它要的正是这两把的私钥）。
     */
    currentKeyStorePath: String?,
    onCurrentKeyStorePathChange: (String) -> Unit,
    currentKeyStorePassword: String,
    onCurrentKeyStorePasswordChange: (String) -> Unit,
    currentAliases: List<String>,
    currentSelectedAlias: String?,
    onSelectCurrentAlias: (String) -> Unit,
    /** 文件拖到了**哪一行**就落到哪一行（见 `routeTo`）。 */
    onDropInto: (List<String>, SignInputKind) -> Unit,
    /** 拖到「当前密钥」那一行。 */
    onDropIntoCurrent: (List<String>) -> Unit,
    onOpenCurrentKeyStore: () -> Unit,
    onClearCurrentKeyStore: () -> Unit,
    onOpenApk: () -> Unit,
    onOpenKeyStore: () -> Unit,
    onClearApk: () -> Unit,
    onClearKeyStore: () -> Unit,
    onSign: () -> Unit,
    host: DevToolHost,
    modifier: Modifier,
) {
    Column(modifier) {
        // APK 这一格与下面「KeyStore」那一行用**同一个控件**（见 `DevToolFileField`）：同一页里两个
        // 文件输入长成一个样子，读的人不必先分辨「哪块是卡片、哪块是输入框」。它因此也能手敲路径、
        // 也能拖入。
        //
        // 代价是这一页不再有 `DevToolInputField`——`⌘V` 的入口改成工具自己登记（见 `Content` 里那段
        // `register`），处理与从前一样按内容分流，一把钥匙仍然不会被当成 APK。
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowLabel("APK")
            DevToolFileField(
                value = apkPath.orEmpty(),
                onValueChange = onApkPathChange,
                placeholder = "拖拽、粘贴、打开APK文件，或输入路径",
                host = host,
                onDropFiles = { paths -> onDropInto(paths, SignInputKind.Apk) },
                onOpen = onOpenApk,
                onClear = onClearApk,
                clearTooltip = "清除 APK",
                // 宽度给这一行**剩余**的部分（同下面那一行）：右缘与文件名长短无关。
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(10.dp))

        VerifyConclusion(
            apkPath = apkPath,
            verifying = verifying,
            outcome = verifyOutcome,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        val ready = verifyOutcome as? VerifyOutcome.Ready
        val signers = ready?.signers.orEmpty()
        when {
            apkPath == null -> CenteredHint(
                text = "打开一个 APK 后，这里显示它当前的签名",
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            verifying -> CenteredHint("正在验签…", Modifier.fillMaxWidth().weight(1f))

            // 尚无结果（防抖的 250ms 与读包期间）：**不要给出结论**。若落到下面输出「这个包里一个
            // 签名都没有」，等于把「尚未验签」写成「已验过、没有」；防抖使这段窗口可见之后，该
            // 结论即成为错误陈述。
            //
            // 措辞与上面那行结论**刻意不同**：结论行是「正在验签…」（说明这次操作进行到哪一步），
            // 这一块是「正在读这个包…」（说明这块区域为什么仍为空）。同一句话出现两遍，会让人以为
            // 是两个不同的状态。
            verifyOutcome == null -> CenteredHint("正在读这个包…", Modifier.fillMaxWidth().weight(1f))

            // 这一块只说「为什么这儿空着」——它是这一页最大的一片区域，空着更需要一句话。整句原因
            // 与上面那一行的结论都不复述：原因在状态栏右段（见 `Content` 里那段）。
            verifyOutcome is VerifyOutcome.Failed -> FailedHint(
                message = "这个包无法读取，所以没有签名信息可看",
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            signers.isEmpty() -> CenteredHint(
                text = "这个包里一个签名都没有——还没签过，或者签名块被整个剥掉了",
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            // 与密钥库那一页同一个形状：**整段文本**，不是一行一个标签的列表。这份东西的形状就是
            // 它的用途——跟 `keytool` 的输出、跟别人的截图对着看，而「逐行点一下复制」把一份完整
            // 的东西拆成了十几份。要复制整段，用右上角那两个动作（复制 / 存文件）。
            else -> {
                // 轮替链排在原文**上方**，且**只在这一支里**画：它是关于眼前这个包的结论之一（这个包
                // 轮替过、链上有谁），先看事实再往下看凭证原文；而在上面那几支（正在验、无法读取、一个
                // 签名都没有）里，`ready` 还是**上一个包**留下的那一份——摆出来就成了拿旧包的链说这个
                // 包。这一支的判据是「眼前这份结果非空」，两者同时成立。
                val rotation = ready?.rotation.orEmpty()
                if (rotation.isNotEmpty()) {
                    RotationChain(chain = rotation, host = host)
                    Spacer(Modifier.height(8.dp))
                }
                val report = printcertText(signers)
                DevToolCodeField(
                    label = "结果",
                    value = report,
                    onValueChange = {},
                    editable = false,
                    // 不折行：`扩展` 那一段是十六进制转储，`0000: A4 D4 …` 与右边的 ASCII 列靠空格
                    // 对齐——一折行，那两列就散了（同密钥库那一页）。
                    softWrap = false,
                    // 没有行号、不做折叠：这不是代码，行号只会平白多一列数字。
                    lineNumbers = false,
                    folding = false,
                    scan = ::scanPlain,
                    placeholder = "打开一个 APK 后，这里显示 keytool -printcert -jarfile 的内容",
                    actions = {
                        DevToolResultActions(
                            value = report,
                            host = host,
                            suggestedFileName = "certificate.txt",
                        )
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        DevToolSectionDivider()
        Spacer(Modifier.height(10.dp))

        // 下面是**签名那一栏**：签一个包要的东西全在这儿——签名方案、密钥库、口令、别名、按钮。
        // 上面那两块（输入卡、签名信息）回答「我在看什么」，这一栏回答「我要它变成什么」。
        //
        // 这一栏从上往下就是做这件事的顺序：**先定要写哪几层签名方案**，再定用哪把（轮替时是两把）
        // 钥匙，最后按按钮。
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowLabel("签名方案")
            // 三个开关而不是「全选 / 自选」两档：它们本来就可以任意组合，用复选框说出来最直白
            // （见 `DevToolWidgets` 里对「开关」与「多选一」的分工）。
            //
            // 遍历的是**全部**方案（`SignScheme.entries`），不是 `schemes` 那个集合：后者是「当前
            // 选中的那几个」，拿它当遍历对象的话，取消勾选就等于把它从这一行里删掉——开关会消失，
            // 再也点不回来。
            SignScheme.entries.forEach { scheme ->
                DevToolToggle(
                    title = scheme.title,
                    checked = scheme in schemes,
                    onCheckedChange = { onToggleScheme(scheme, it) },
                )
            }
            if (signing) {
                Spacer(Modifier.weight(1f))
                Text("正在签…", fontSize = 12.sp, color = MaterialTheme.hintColor)
            }
        }

        Spacer(Modifier.height(10.dp))

        // 密钥轮替的开关排在**两把钥上面**：它管的就是「这里要填一把还是两把」，摆在它们下面等于让
        // 开关去管它上面的东西。
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowLabel("密钥轮替")
            DevToolToggle(
                title = "换成新KeyStore",
                checked = rotating,
                onCheckedChange = onToggleRotation,
            )
        }

        Spacer(Modifier.height(6.dp))

        if (rotating) {
            // 两把钥**贴在一起**、且长得**一模一样**（同 `KeyStoreRow`）：它们是同一个决定的两头（把手
            // 上这个包交给谁签），摆成一个样子才读得出「这两把要一起填」。
            //
            // 「现在的」排在前面，是因为轮替这件事的读法就是从上往下：这个包现在是 `KeyStore` 签的，
            // 我要把它换成 `新KeyStore`——开关底下这两行正好是那句话的两半。
            KeyStoreRow(
                label = "KeyStore",
                path = currentKeyStorePath,
                onPathChange = onCurrentKeyStorePathChange,
                password = currentKeyStorePassword,
                onPasswordChange = onCurrentKeyStorePasswordChange,
                aliases = currentAliases,
                selectedAlias = currentSelectedAlias,
                onSelectAlias = onSelectCurrentAlias,
                onDropFiles = onDropIntoCurrent,
                onOpen = onOpenCurrentKeyStore,
                onClear = onClearCurrentKeyStore,
                host = host,
            )
            Spacer(Modifier.height(6.dp))
        }

        // 不轮替时它就是原来那一行（`KeyStore`）；轮替时它是要**换上**的那把，名字跟着改。
        KeyStoreRow(
            label = if (rotating) "新KeyStore" else "KeyStore",
            path = keyStorePath,
            onPathChange = onKeyStorePathChange,
            password = keyStorePassword,
            onPasswordChange = onKeyStorePasswordChange,
            aliases = aliases,
            selectedAlias = selectedAlias,
            onSelectAlias = onSelectAlias,
            onDropFiles = { paths -> onDropInto(paths, SignInputKind.KeyStore) },
            onOpen = onOpenKeyStore,
            onClear = onClearKeyStore,
            host = host,
        )

        if (rotating) {
            Spacer(Modifier.height(4.dp))
            // 两件在界面上看不出来的事：新钥**从哪个版本起**才算数，以及那份链还落了一个文件。
            Text(
                text = "新KeyStore 从 Android 13 起生效，更老的版本仍用 KeyStore 验，所以都能升级；" +
                    "轮替链会写进包，并另存到包旁边（.lineage）。",
                fontSize = 11.sp,
                color = MaterialTheme.hintColor,
            )
        }

        Spacer(Modifier.height(10.dp))

        // 按钮**独占一行**、左缘与标签列对齐（同「新建KeyStore」页）：与开关同行时会被挤到窗口
        // 最右侧，与前文脱节。
        //
        // 只在签名进行中禁用：缺东西时点得动，点一下就用一句临时提示说明缺什么（见 `sign`）。原先
        // 它是灰着的，而解释它的那句话常驻在状态栏——**只看签名情况的用户**因此一直被念「你需要
        // 选一个 KeyStore」，那件事与他在做的事无关。
        DevToolButton(
            title = "签名并另存为…",
            primary = true,
            enabled = !signing,
            onClick = onSign,
        )
    }
}

/**
 * 「一把钥」那一行：KeyStore 路径 + 密码 + 别名。
 *
 * 抽出来是因为这一页有两处**必须长得一模一样**的这种行——`新KeyStore`（签名要用的那把）与轮替时的
 * `KeyStore`（这个包现在用的那把）。两处各写一遍的话，日后改一处（比如给密码框换个宽度）另一处不会
 * 跟着变，而它们上下紧贴着摆，差一点点都看得出来（同 `DevToolFileField` 的来历）。
 *
 * [label] 由调用方给，而且**只给名字**：下面三个占位符 / 悬浮说明都按它拼（`拖拽、粘贴、打开$label
 * 文件…`），于是「这一行叫什么」只有一个出处——名字是读的人区分这两行**唯一**的东西，跟着别处漂就
 * 等于指错行。
 *
 * 「用哪把钥匙」的三项排在同一行：它们是同一个决定的三个部分——换一个库，密码与别名随之更换——分成
 * 两三行会让人需要上下对照才能确认它们同属一组。
 */
@Composable
private fun KeyStoreRow(
    label: String,
    path: String?,
    onPathChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    aliases: List<String>,
    selectedAlias: String?,
    onSelectAlias: (String) -> Unit,
    onDropFiles: (List<String>) -> Unit,
    onOpen: () -> Unit,
    onClear: () -> Unit,
    host: DevToolHost,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RowLabel(label)
        // 这一格使用共用的文件控件（见 `DevToolFileField`）：路径框、拖入落点、打开 / 清除绑定在一起。
        // 图标必须紧邻路径框——曾经把它放在该行另一端，紧挨「密码」与「别名」，结果被读成「清空密码」。
        DevToolFileField(
            value = path.orEmpty(),
            // 也可以**直接输入路径**（该路径走防抖，见 `KeyStoreSession`）：路径已在别处拿到时，可以
            // 省去打开对话框这一步。
            onValueChange = onPathChange,
            // `$` 后面跟汉字要加花括号：`$label文件` 被当成一个标识符，编译不过（踩过）。
            placeholder = "拖拽、粘贴、打开${label}文件，或输入路径",
            host = host,
            onDropFiles = onDropFiles,
            onOpen = onOpen,
            onClear = onClear,
            clearTooltip = "清除 $label",
            // 宽度给这一行**剩余**的部分：右缘因此与文件名长短无关，两个动作位置固定。
            modifier = Modifier.weight(1f),
        )
        // 这里的空隙**定宽**（不是 `weight`）：余量全归上面那个框，路径才铺得开。原先这里也放了一个
        // `weight(1f)`，两处抢，各分一半——框只拿到一半宽，长路径被截在文件名上。
        //
        // 定宽还有一层用处：它把「密码 / 别名」与那两个图钉隔开，谁也不会以为垃圾桶清的是密码。
        Spacer(Modifier.width(16.dp))
        Text("密码", fontSize = 12.sp, color = MaterialTheme.hintColor)
        DevToolActionSpacer()
        SecretField(
            value = password,
            onValueChange = onPasswordChange,
            placeholder = "$label 密码",
            modifier = Modifier.width(160.dp),
        )
        DevToolActionSpacer()
        Text("别名", fontSize = 12.sp, color = MaterialTheme.hintColor)
        DevToolActionSpacer()
        DevToolMenuButton(
            label = selectedAlias ?: "—",
            options = aliases,
            selected = selectedAlias ?: "—",
            optionLabel = { it },
            onSelect = onSelectAlias,
            // 只有一个别名时不必点开一列只有一项的菜单（同第一页那一栏）。
            enabled = aliases.size > 1,
        )
    }
}

/**
 * 验签结论那一行。
 *
 * 「验过了但不通过」与「读不了这个包」必须分开说明：前者是这个工具存在的原因（这个包被改过，
 * 或者签名与所选密钥不一致），后者只是文件本身有问题。合并成一句「验签失败」会把两件事混为一谈，
 * 用户只能反复试。
 */
@Composable
private fun VerifyConclusion(
    apkPath: String?,
    verifying: Boolean,
    outcome: VerifyOutcome?,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val text: String
    val color: androidx.compose.ui.graphics.Color
    when {
        apkPath == null -> {
            text = "还没有选 APK"
            color = MaterialTheme.hintColor
        }

        verifying -> {
            text = "正在验签…"
            color = MaterialTheme.hintColor
        }

        // 只给一句**短的现状**：整句原因统一在状态栏右段（见 `Content` 里那段），这一行紧贴着那一
        // 格，说清「现在是什么状态」就够——两处都印整句，等于同一句话说两遍。
        outcome is VerifyOutcome.Failed -> {
            text = "这个包无法读取"
            color = colors.error
        }

        outcome is VerifyOutcome.Ready && outcome.verified -> {
            text = "验签通过 · ${schemeText(outcome.schemes)}${signerText(outcome.signers.size)}"
            color = colors.primary
        }

        outcome is VerifyOutcome.Ready -> {
            text = "验签未通过：这个包被改过，或者签名信息不完整"
            color = colors.error
        }

        // 路径有了、结果还没回来（防抖那 250ms + 读包的时间）：说「正在验」。
        //
        // 这一支原先也写「还没有选 APK」——那条只在**真没选**时对；路径已经填上、只是还没验完的
        // 时候，它是在说反话（防抖把这段窗口拉长到看得见之后才露出来）。
        else -> {
            text = "正在验签…"
            color = MaterialTheme.hintColor
        }
    }

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 13.sp, color = color)
        Spacer(Modifier.weight(1f))
        if (outcome is VerifyOutcome.Ready && outcome.errors.isNotEmpty()) {
            // 错误明细没有更合适的落点，这里只报第一条：apksig 的第一条通常就是根因，后面几条
            // 多半是它带出来的连锁。
            Text(
                text = outcome.errors.first(),
                fontSize = 11.sp,
                color = MaterialTheme.hintColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 密钥轮替链：**只在包真带 proof-of-rotation 时画**（调用方判，见 `ApkPage`）。
 *
 * 为什么值得单独一块：`keytool -printcert -jarfile` **只打当前那张证书**（结果区就是它的原文，逐字
 * 对齐），于是「这个包轮替过」以及「更早那几张的指纹」在界面上完全看不见——而各平台注册要的常常
 * 正是最早那张。链其实一直在手里（`verifyApk` 早就读到了），原先只用它取末位证书，其余几张被丢掉。
 *
 * 每行整行可点即复制（与结果列表同一套行，见 `DevToolResultRow`）：指纹是一长串十六进制，让人精确
 * 拖选一段很容易选歪。
 */
@Composable
private fun RotationChain(chain: List<CertInfo>, host: DevToolHost) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            // 起个「共 N 张」是为了让人一眼看出轮替过几次：两张 = 换过一次钥。
            text = "密钥轮替 · 链上共 ${chain.size} 张证书（原始 → 当前）",
            fontSize = 11.sp,
            color = MaterialTheme.hintColor,
            // 与下面每行标签的起点对齐：那一行的内边距就是 8dp（见 `DevToolResultRow`）。说明文字比
            // 它管的几行靠左，读起来像在说上面的东西。
            modifier = Modifier.padding(start = 8.dp),
        )
        Spacer(Modifier.height(2.dp))
        rotationFingerprints(chain).forEach { row ->
            DevToolResultRow(
                labelText = row.label,
                valueText = row.value,
                onCopy = { host.copyToClipboard(row.value) },
            )
        }
    }
}

/** 真正验过的方案排成一串；一个都没有时说「没有任何签名方案」，而不是留一片空白。 */
private fun schemeText(schemes: Set<SignScheme>): String = if (schemes.isEmpty()) {
    "没有任何签名方案"
} else {
    SignScheme.entries.filter { it in schemes }.joinToString(" ") { it.title }
}

private fun signerText(count: Int): String = when (count) {
    0 -> ""
    1 -> " · 1 个签名者"
    else -> " · $count 个签名者"
}

/**
 * 新建那一页：造一个新的密钥库。
 *
 * 三页里只有这一页是**产出**一个文件，不是读一个文件——所以它没有输入卡，只有一张表：一行是
 * 「存哪儿」，其余是需要定下来的那几项。每一项都有默认值（见 `KeyStoreForm`），进来直接点「创建」
 * 就得到一把能用的钥匙。
 *
 * 这一页**不画结果**：那是「密钥库」那一页的事，同一份内容两个地方画，早晚长得不一样；建完也不
 * 翻过去（见 `create`）。底部那句提示只交代这条路走到了哪一步——该点创建、撞上了已有文件、建成
 * 了（带完整路径）、还是失败了。
 */
@Composable
private fun NewKeyStorePage(
    form: KeyStoreForm,
    onFormChange: (KeyStoreForm) -> Unit,
    creating: Boolean,
    outcome: CreateOutcome?,
    onPickOutPath: () -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier,
) {
    val problem = keyStoreRequestProblem(
        outPath = form.outPath,
        alias = form.alias,
        password = form.password,
        confirmPassword = form.confirmPassword,
        subject = form.subject,
    )
    val years = form.validityYears.toIntOrNull()
    // 到期日按填的年数**现算**并摆出来：这是新建时唯一一个「填错了要过很久才疼」的字段（密钥发
    // 出去就换不掉），而「25 年」这类数字本身没有直觉——算成日期才看得出够不够。
    val expiryMillis = years?.takeIf { it in MinValidityYears..MaxValidityYears }
        ?.let { validityEndMillis(Clock.System.now().toEpochMilliseconds(), it * 365) }

    Column(modifier) {
        // 表在**可滚的一栏**里，动作在栏外钉住底部——与 Android Studio 那张对话框同一个形状
        // （内容长了滚内容，按钮不动）。窗口不够高时，按钮不会被挤到看不见的地方去。
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            // 容器格式排在**第一格**：它决定这个文件是什么，而下面「保存到」那个名字是跟着它走的
            // （「选择…」预填的扩展名随格式变）。先定格式、再挑位置，顺序与因果一致。
            //
            // 两个「选项」行的说明一并交给 `hint`（即**选中那项**的说明）：选项本身只留名字，
            // 说明列跟别的行一样落在同一个 x 上，从上往下扫的时候不必中途换一次读法。
            FormRow("容器格式", hint = form.format.advice) {
                DevToolSegmentedControl(
                    options = StoreFormat.entries,
                    selected = form.format,
                    optionLabel = { it.title },
                    // 走 `withFormat`：扩展名跟着换，见那个函数。
                    onSelect = { onFormChange(form.withFormat(it)) },
                )
            }

            Spacer(Modifier.height(10.dp))

            // 密钥库不要跟代码放在一起：它一旦进过版本库就等于永久泄露（历史里删不干净），而这是
            // 整个工具唯一会写盘的地方，值得多这一句。
            FormRow("保存到", hint = "放在项目外面，别提交进仓库") {
                DevToolSingleLineField(
                    value = form.outPath,
                    // 手敲 / 粘贴的名字不再算「确认过覆盖」：这条路不过保存对话框，没人问过用户。
                    onValueChange = { onFormChange(form.copy(outPath = it, confirmedOutPath = null)) },
                    placeholder = "选一个保存位置",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                DevToolButton(title = "选择…", onClick = onPickOutPath)
            }

            Spacer(Modifier.height(10.dp))

            FormRow("加密算法", hint = form.algorithm.advice) {
                DevToolSegmentedControl(
                    options = KeyAlgorithm.entries,
                    selected = form.algorithm,
                    optionLabel = { it.title },
                    onSelect = { onFormChange(form.copy(algorithm = it)) },
                )
            }

            Spacer(Modifier.height(10.dp))

            // 口令与它的确认**并在一行**：它们是同一件事的两半，分两行只会多一次上下对照。
            //
            // 第二格前面那个「确认」是**写死的标签**，不是占位符：占位符一填上字就没了，而两格
            // 都只打点的时候，光看是分不出哪格是哪格的。十来个像素换掉一个必然会被问的问题。
            //
            // 只填一次（不像 Android Studio 那样密钥库与别名各一对）：默认容器 PKCS#12 在格式上
            // 就要求两者相同，摆两对只会让人以为它们可以不一样。
            FormRow("密码", hint = "至少 6 位，签名与读 KeyStore 都要它") {
                SecretField(
                    value = form.password,
                    onValueChange = { onFormChange(form.copy(password = it)) },
                    placeholder = "至少 6 位",
                    modifier = Modifier.width(FormPasswordWidth),
                )
                DevToolActionSpacer()
                Text("确认", fontSize = 12.sp, color = MaterialTheme.hintColor)
                DevToolActionSpacer()
                SecretField(
                    value = form.confirmPassword,
                    onValueChange = { onFormChange(form.copy(confirmPassword = it)) },
                    // 不给占位符：左边那个「确认」已经说清了要做什么，再写一句「再敲一遍」是在教
                    // 用户做一件他已经知道的事。
                    placeholder = "",
                    modifier = Modifier.width(FormPasswordWidth),
                )
            }

            Spacer(Modifier.height(8.dp))

            // 说明说**它是什么**，不说「签名时用它挑密钥」：那是它的**用途**，而这里正在建第一把，
            // 眼前根本没有可挑的东西——上一版这么写，第一个读到的人就问「挑什么？」。用途改由
            // 后半句交代场景（库里不止一把时），既说清为什么要有这个名字，也不逼他现在就理解。
            FormRow("别名", hint = "这把钥匙的名字；库里放多把时靠它区分") {
                DevToolSingleLineField(
                    value = form.alias,
                    onValueChange = { onFormChange(form.copy(alias = it)) },
                    placeholder = "key0",
                    modifier = Modifier.width(FormFieldWidth),
                )
            }

            Spacer(Modifier.height(8.dp))

            // 到期日排在有效期那一行的**说明列**里（原先单独一行挂在下面）。它本来就是那一格的
            // 结论，摆在同一行才看得出是谁的；早于 Play 那条线时还要标红——藏起来就等于没说。
            FormRow(
                label = "有效期（年）",
                hint = expiryHintText(expiryMillis),
                hintIsError = expiryMillis != null && expiryMillis < PlayKeyDeadlineMillis,
            ) {
                DevToolSingleLineField(
                    value = form.validityYears,
                    onValueChange = { onFormChange(form.copy(validityYears = it)) },
                    placeholder = "25",
                    modifier = Modifier.width(64.dp),
                )
            }

            Spacer(Modifier.height(14.dp))

            // 证书那几项列成六格，而不是一个要自己写 DN 语法的「主题」框——分格之后语法由我们拼
            // （含转义，见 `subjectOf`），用户只管填词。
            CertificateSectionLabel()
            // 五格空着，用户一定会问「这些要不要填」——就在这儿回答，别让他去猜、也别让他去试。
            FormHint("除了姓名，其余都可以不填")

            CertificateField("姓名", "证书上的名字（CN）", form.commonName) {
                onFormChange(form.copy(commonName = it))
            }
            CertificateField("部门", "组织下面的部门（OU）", form.organizationalUnit) {
                onFormChange(form.copy(organizationalUnit = it))
            }
            CertificateField("组织", "公司 / 团队（O）", form.organization) {
                onFormChange(form.copy(organization = it))
            }
            CertificateField("城市或地区", "城市（L）", form.locality) {
                onFormChange(form.copy(locality = it))
            }
            CertificateField("省份", "省 / 州（ST）", form.state) {
                onFormChange(form.copy(state = it))
            }
            CertificateField("国家代码", "两位，如 CN（C）", form.country) {
                onFormChange(form.copy(country = it))
            }
        }

        // 动作行钉在底部：说明与按钮**同一行**，说明在左、可能折行（占掉剩余宽度），按钮在右。
        // 撞上已有的文件：这是**在问一句**，而不是报错——按钮已经变成「覆盖」，再点一次就写下去。
        val askingOverwrite = outcome is CreateOutcome.Exists
        val footerNote = when {
            creating -> "正在生成密钥对、签证书、写文件…"
            outcome is CreateOutcome.Failed -> "创建失败"
            outcome is CreateOutcome.Exists ->
                "该位置已有文件。覆盖请再点一次——KeyStore 被覆盖后无法找回"
            // 建完不翻页（见 `create()`），所以这一句就是用户看到的**结果**：成功，以及它在哪。
            outcome is CreateOutcome.Done -> "已创建 ${outcome.outPath}"
            problem != null -> problem
            else -> ""
        }
        // 只有**真试过并且失败**才用错误色。表单还没填完不算「错」——那张表一打开就红着一句
        // 「请先选择保存位置」，读起来是在训人，而它其实只是「下一步该点哪儿」；「已有文件」
        // 也不是错，照它做就行。
        val footerIsError = outcome is CreateOutcome.Failed

        Spacer(Modifier.height(10.dp))
        DevToolSectionDivider()
        Spacer(Modifier.height(10.dp))

        // 按钮**自己占一行**，摆在提示上面，左缘与整张表的标签列对齐。
        //
        // 之前它与那句提示挤在同一行：提示吃掉剩余宽度、按钮被顶到窗口最右边，隔着大半屏空白，跟
        // 上面那张表是断开的（「位置那么偏」）。挪到自己一行之后，「填完 → 点创建 → 读下面那句
        // 结果」是从上往下的一条线，而这句结果本来就是在说这次点击。
        DevToolButton(
            // 撞上已有文件时按钮就叫「覆盖」：这一下点下去要发生什么，写在按钮上，而不是让用户从
            // 提示里自己推。
            title = when {
                creating -> "创建中…"
                askingOverwrite -> "覆盖"
                else -> "创建"
            },
            onClick = onCreate,
            // **点得动**：填得不对时点一下，下面那句就换成「哪儿不对」。原先这个按钮一直灰着，
            // 而解释它为什么灰的提示在窗口最底下、常常看不见——一个点不动又不说明原因的按钮
            // 是最难受的一种控件。
            enabled = !creating,
            primary = true,
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = footerNote,
            fontSize = 11.sp,
            color = if (footerIsError) MaterialTheme.colorScheme.error else MaterialTheme.hintColor,
        )
    }
}

/**
 * 把 [session] 里的路径与口令读成一个密钥库，并选择一个别名。
 *
 * 路径或口令一变就重开一次——这就是「试着输口令」的那条路，不必另给一个按钮。口令留空时按「只读
 * 证书」开：JKS 与 PKCS#12 都允许不校验口令读出别名与证书。
 *
 * 两个会话各调用一次（见 [Content]）；写成函数是为了避免把同一段抄两遍——两处一旦有一处漏改，
 * 症状是「其中一页读不出来」，较难发现。
 */
@Composable
private fun KeyStoreReadEffect(session: KeyStoreSession) {
    // 路径与口令各自防抖（见 `KeyStoreOpenDebounceMillis`）：这两样都可以边敲边变，而下面那个
    // effect 每动一次就要开一次文件。
    LaunchedEffect(session.password) {
        delay(KeyStoreOpenDebounceMillis)
        session.appliedPassword = session.password
    }
    LaunchedEffect(session.path) {
        delay(KeyStoreOpenDebounceMillis)
        session.appliedPath = session.path
    }

    LaunchedEffect(session.appliedPath, session.appliedPassword) {
        val path = session.appliedPath
        if (path == null) {
            session.outcome = null
            session.selectedAlias = null
            session.reading = false
            return@LaunchedEffect
        }
        session.reading = true
        val outcome = withContext(Dispatchers.Default) {
            readKeyStore(path, null, session.appliedPassword.takeIf { it.isNotEmpty() }?.toCharArray())
        }
        session.outcome = outcome
        // 重新挑一个别名：换了文件、或条目变了之后，原先选中的那个可能已经不在了。优先挑一个
        // **能签名**的私钥条目——打开密钥库十有八九就是为了拿它去签。
        val entries = session.entries
        if (entries.none { it.alias == session.selectedAlias }) {
            session.selectedAlias = entries.firstOrNull { it.isKeyEntry }?.alias ?: entries.firstOrNull()?.alias
        }
        session.reading = false
    }
}

/** 表单的标签列宽度。按最长的那条（`有效期（年）`）定。 */
private val FormLabelWidth = 88.dp

/**
 * 表单里一个输入格的宽度。
 *
 * 固定而不是铺满：这一页是张**表**，一格铺满整行就变成一条条通栏横条了。只有「保存到」那一行
 * 例外——路径本身就长。
 */
private val FormFieldWidth = 300.dp

/**
 * 密码那两个框的宽度。
 *
 * 比别的格窄：它们**并排两个**还要在右边留出说明列的位置，两个 [FormFieldWidth] 会顶出去。窄一点
 * 也不碍事——里面只有点，不读内容。
 */
private val FormPasswordWidth = 170.dp

/**
 * 输入列的宽度：**固定**，说明列才起得来。
 *
 * 说明排在输入框**后面**，而它要能从上往下对齐着扫，起点就不能跟着每行的控件宽度跑：`密码` 那一行
 * 是两个框，`加密算法` 那一行是一个分段控件，各自宽度都不同。所以把内容这一格钉成固定宽度（控件
 * 本身仍在里面左对齐），说明列于是落在同一个 x 上。
 *
 * 420dp 是按这一页最宽的一行定的（`密码` 那两个框 + 中间那个「确认」）。
 */
private val FormContentWidth = 420.dp

/** 输入列与说明列之间的间隔。 */
private val FormHintGap = 14.dp

/**
 * 表单的一行：标签列 + 输入列 + 说明列。
 *
 * 说明放在**输入框后面**，不另起一行：只有一两条时「下一行」那种写法还行，一旦每格都有说明，满页
 * 就是「说明 → 输入 → 说明 → 输入」，上下两组谁属于谁要靠位置猜。并到一行之后，每一行自成一条
 * 「这项叫什么 → 填什么 → 什么意思」，从上往下读就是完整的。
 *
 * [hint] 中**不得使用 Markdown 记号**：这些文字按原样绘制，`**` 与反引号会一并显示出来
 * （占位符中已出现过这种情况）。
 */
@Composable
private fun FormRow(
    label: String,
    hint: String? = null,
    hintIsError: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.hintColor,
            modifier = Modifier.width(FormLabelWidth),
        )
        // 定宽的一栏把控件围住：控件自己在里面左对齐，说明列的起点因此与控件宽度无关。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.width(FormContentWidth),
        ) {
            content()
        }
        if (hint != null) {
            Text(
                text = hint,
                fontSize = 11.sp,
                color = if (hintIsError) MaterialTheme.colorScheme.error else MaterialTheme.hintColor,
                modifier = Modifier.padding(start = FormHintGap),
            )
        }
    }
}

/**
 * 挂在**一块表头下面**的说明（目前只有「证书」那一块：那五格要不要填，是整组的问题，不属于任何
 * 单独一格）。
 *
 * 单格的说明走 [FormRow] 的 `hint`，不在下面另起一行。
 */
@Composable
private fun FormHint(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        color = MaterialTheme.hintColor,
        modifier = Modifier.padding(start = FormLabelWidth, top = 4.dp),
    )
}

/**
 * 一格里的一张证书项。六项的差异只有标签、说明与取值，集中一处以免重复六遍同样的代码。
 *
 * 宽度用 [FormFieldWidth] 而不是铺满：六格铺满会拉成六条通栏横条，读起来像六块区域而不是一张表；
 * 而这一页只有「保存到」那一行需要占满（路径长）。
 *
 * [hint] 里那个括号是这几格存在的理由：`部门` / `组织` 这两个中文词谁也分不出区别，而它们在
 * 证书里差着 DN 的一层——把 `OU` / `O` 摆出来，对得上别人给的规格，也对得上 keytool 的输出。
 */
@Composable
private fun CertificateField(
    label: String,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    FormRow(label, hint = hint) {
        DevToolSingleLineField(
            value = value,
            onValueChange = onValueChange,
            // 不给占位符：这几格是**选填**的，空着就是不要这一项（见 `subjectOf`）。写一句灰字
            // 反而像「这里有个值等着你删」。
            placeholder = "",
            modifier = Modifier.width(FormFieldWidth),
        )
    }
    // 六格之间留一口气：它们是一组，挤在一起会读成一块。
    Spacer(Modifier.height(8.dp))
}

/** 表单里一块的表头（目前只有「证书」那一块）。 */
@Composable
private fun CertificateSectionLabel() {
    Text(
        text = "证书",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
}

/**
 * 「到期 2056-10-06（Google Play 要求晚于 2033-10-22）」；年数填得不对时只说这一件事。
 *
 * 这一句直接写在有效期那一行上，而不是等创建失败才说：Play 那条线是**上架时**才卡的，等那时发现
 * 已经晚了——密钥发出去就换不掉。
 */
private fun expiryHintText(expiryMillis: Long?): String = if (expiryMillis == null) {
    "填 $MinValidityYears 到 $MaxValidityYears 之间的年数"
} else {
    "到期 ${formatCertDate(expiryMillis)}（Google Play 要求晚于 2033-10-22）"
}

/** 并排的输入行共用的标签宽度：按最长的一条（`新KeyStore`，一个汉字 + 九个半角）定。 */
private val RowLabelWidth = 80.dp

/**
 * 一行输入左边的标签（`KeyStore` / `新KeyStore` / `密码` / `别名` / `签名方案`）。
 *
 * 与「新建KeyStore」那一页的标签列同一个用途：各行左缘对齐，从上往下扫的时候不必一行行找回起点。
 * 几处文件格（签名页三处、KeyStore 信息页一处）与下面那些密码 / 别名行**共用这一份宽度**，所以
 * 同一条轴上不会出现两个起点——左边一列参差不齐时，读的人会先去找对齐关系，而不是内容。
 */
@Composable
private fun RowLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.hintColor,
        modifier = Modifier.width(RowLabelWidth),
    )
}

/**
 * 口令输入框。
 *
 * 刻意不复用共用的 `DevToolSingleLineField`：那一个是明文显示的，而口令框在这个应用里格外敏感
 * ——应用本身就是剪贴板管理器，窗口常被投屏、截图分享。这里只多一件事：
 * `PasswordVisualTransformation` 把字打成点。其余（高度、底色、聚焦描边）与那个控件保持一致，
 * 避免同一面板内出现两种输入框样式。
 *
 * 也**不带**任何复制动作：口令进不了系统剪贴板，是从这里断掉的。
 */
@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val codeColors = rememberCodeColors()

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = 13.sp, color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        interactionSource = interaction,
        visualTransformation = PasswordVisualTransformation(),
        modifier = modifier
            // 与工具栏控件同高（30dp）：这一行里它还挨着标签与别名下拉，高矮不一会参差。
            .height(30.dp)
            // 鼠标移进来变**文本光标**（同 `DevToolSingleLineField`）：能敲字就得让人看出来。
            .pointerHoverIcon(PointerIcon.Text)
            .clip(shape)
            .background(codeColors.editorBackground)
            .border(
                width = 1.dp,
                color = if (focused) colors.primary else colors.outline.copy(alpha = 0.6f),
                shape = shape,
            )
            .padding(horizontal = 12.dp),
        decorationBox = { innerTextField ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        fontSize = 13.sp,
                        color = MaterialTheme.hintColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                innerTextField()
            }
        },
    )
}

/** 结果区里的一句居中说明（还没有输入 / 正在读 / 没有条目 / 没有签名）。 */
@Composable
private fun CenteredHint(text: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(text = text, fontSize = 12.sp, color = MaterialTheme.hintColor)
    }
}

/**
 * 失败时的说明。
 *
 * 与 [CenteredHint] 的差别只在颜色：这里每一句都是**已经翻译过**的人话（见 `ApkSign.jvm.kt` 里
 * 那三个 `readableXxx`），不是异常栈，所以不必再套一层「出错了」。
 */
@Composable
private fun FailedHint(message: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(text = message, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
    }
}
