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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
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
import com.qcmian.clipper.devtools.ui.components.DevToolActionSpacer
import com.qcmian.clipper.devtools.ui.components.DevToolButton
import com.qcmian.clipper.devtools.ui.components.DevToolFieldAction
import com.qcmian.clipper.devtools.ui.components.DevToolInputField
import com.qcmian.clipper.devtools.ui.components.DevToolInputOrigin
import com.qcmian.clipper.devtools.ui.components.DevToolMenuButton
import com.qcmian.clipper.devtools.ui.components.DevToolResultActions
import com.qcmian.clipper.devtools.ui.components.DevToolResultList
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

/** 输入卡的高度：装得下「框名 + 一行路径 + 空态那两句提示」。 */
private val InputCardHeight = 92.dp

/**
 * 有效期允许填的年数。上限只是一个护栏：`Date` 撑得住更远的日期，但再长没有意义，而填错一位数
 * （比如 `300`）会让到期日跨到看不见的年份上去。
 */
private const val MinValidityYears = 1
private const val MaxValidityYears = 100

/**
 * 结果列表的标签列宽度：比共用的 `DevToolResultLabelWidth`（92dp）宽一档。
 *
 * 共用的那一个是按「时间戳 / 摘要」那种两三个字的标签定的，而这个工具里最长的一条是
 * `主体公共密钥算法`（8 个汉字，12sp 下正好 96dp）。挤在 92dp 里它会被折成两行——而这一列一折，
 * 「从上往下对齐着扫」这件事就没了，证书那一页正是靠对齐才读得动。
 */
private val ApkSignLabelWidth = 108.dp

/**
 * 口令停下来多久才真去开密钥库。
 *
 * 与其它工具的 150ms 不是一回事：那边防的是「每敲一个字就重算一遍」，这边防的是**每敲一个字就
 * 读一次磁盘 + 解一遍密钥库**，顺带还会在输入途中闪出「口令不对」。250ms 足够盖住盲打。
 */
private const val PasswordDebounceMillis = 250L

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
    KeyStore("密钥库"),
    Apk("APK"),
    New("新建KeyStore"),
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
        description = "看密钥库与 APK 的证书、指纹和签名方案，指纹按各平台要的写法给全；" +
            "也可以给 APK 重新签名（只另存，不动原文件）。",
        group = DevToolGroup.GENERATOR,
        icon = ClipperIconKind.ANDROID,
    )

    @Composable
    override fun Content(input: ClipItem?, host: DevToolHost) {
        var tab by remember { mutableStateOf(ApkSignTab.KeyStore) }

        // 密钥库这一侧。路径与口令是**两页共用**的：签名那一页要拿同一把钥匙。
        var keyStorePath by remember { mutableStateOf<String?>(null) }
        var password by remember { mutableStateOf("") }
        var appliedPassword by remember { mutableStateOf("") }
        var keyStoreOutcome by remember { mutableStateOf<KeyStoreOutcome?>(null) }
        var selectedAlias by remember { mutableStateOf<String?>(null) }
        var readingKeyStore by remember { mutableStateOf(false) }

        // APK 这一侧：路径、验签结果，以及「正在验」。
        var apkPath by remember { mutableStateOf<String?>(null) }
        var verifyOutcome by remember { mutableStateOf<VerifyOutcome?>(null) }
        var verifying by remember { mutableStateOf(false) }

        // 签名：勾了哪几个方案、正在签、上一次签的结果。
        var schemes by remember { mutableStateOf(SignScheme.entries.toSet()) }
        var signing by remember { mutableStateOf(false) }
        var signOutcome by remember { mutableStateOf<SignOutcome?>(null) }

        // 新建那一页的表单。摆在**这里**而不是页面内部：换页签会让页面离开组合、状态跟着没，而
        // 填了一半再去别的页看一眼是常事。整份表单收成一个值对象，改哪一项就换掉哪一项。
        var form by remember { mutableStateOf(KeyStoreForm()) }
        var creating by remember { mutableStateOf(false) }
        var createOutcome by remember { mutableStateOf<CreateOutcome?>(null) }

        val scope = rememberCoroutineScope()

        /**
         * 把路径按**内容**分流到它该去的那一格。
         *
         * 判据是开头几个字节（见 `documentKindOf`），不看扩展名：`.keystore` 里装的可能是 PKCS#12，
         * 而 APK 就是个 ZIP。读文件因此下到后台——只有几个字节，但终究是磁盘。
         *
         * 拖放与粘贴都走这里，且**不分是哪一格**：一页只有一个输入区（见 [ApkPage]），按键与拖放
         * 落下来时根本不知道用户瞄的是哪个槽，只能按内容判。
         *
         * @param navigate 落进去之后要不要**翻到那一页**。「密钥库」那一页只有一个槽，粘进来一个
         *   APK 没处放，替用户翻过去最省事（与条码工具「在编码页粘图就翻到解码页」同一条做法）；
         *   APK 那一页两个槽都在眼前，翻页反而让人以为自己点错了。
         */
        fun route(paths: List<String>, origin: DevToolInputOrigin, navigate: Boolean) {
            val path = paths.firstOrNull() ?: return
            scope.launch {
                val kind = withContext(Dispatchers.Default) { documentKindOf(path) }
                when (kind) {
                    SignInputKind.KeyStore -> {
                        keyStorePath = path
                        if (navigate) tab = ApkSignTab.KeyStore
                    }

                    SignInputKind.Apk -> {
                        apkPath = path
                        if (navigate) tab = ApkSignTab.Apk
                    }

                    null -> host.showStatus(unrecognizedMessage(path, origin))
                }
            }
        }

        /**
         * 落到**指定**那一格——只给两个槽各自的「打开」按钮用。
         *
         * 「打开」是用户**明确点了某一格**再去挑文件的，这时替他猜「其实你想放另一个框」是多余的；
         * 挑错了就说一句（`other`），不翻页、也不塞进别的框。拖放与粘贴不走这条路（它们没有「用户
         * 点了哪一格」这个信息，见 `route`）。
         */
        fun routeTo(paths: List<String>, expected: SignInputKind, other: String) {
            val path = paths.firstOrNull() ?: return
            scope.launch {
                val kind = withContext(Dispatchers.Default) { documentKindOf(path) }
                when (kind) {
                    expected -> if (expected == SignInputKind.Apk) apkPath = path else keyStorePath = path
                    null -> host.showStatus(unrecognizedMessage(path, DevToolInputOrigin.Open))
                    else -> host.showStatus(other)
                }
            }
        }

        /**
         * 「打开」某个槽的文件：挑完之后落到**那一槽**，挑错了只说一句（见 `routeTo`）。
         *
         * 与拖放 / 粘贴分开的正是这一点：那两个动作没有「用户瞄的是哪一格」这个信息，只能按内容判；
         * 而按钮自带槽位，猜反而多余。
         */
        fun pickInto(kind: SignInputKind) {
            host.pickFileToOpen()?.let { routeTo(listOf(it), kind, wrongCardMessage(kind)) }
        }

        // 从剪贴板记录进来：只取**路径**，绝不读内容——密钥库与 APK 都是二进制，当文本读只会得到
        // 一片乱码（`devToolText` 在这里正是用不上的那一个）。
        LaunchedEffect(input) {
            input?.files?.firstOrNull()?.let { route(listOf(it), DevToolInputOrigin.Open, navigate = true) }
        }

        // 口令防抖：见 `PasswordDebounceMillis`。防抖之后的那一份才拿去开密钥库。
        LaunchedEffect(password) {
            delay(PasswordDebounceMillis)
            appliedPassword = password
        }

        // 开密钥库。路径或口令一变就重开一次——这就是「试着输口令」的那条路，不必另给一个按钮。
        // 口令留空时按「只读证书」开：JKS 与 PKCS#12 都允许不校验口令读出别名与证书。
        LaunchedEffect(keyStorePath, appliedPassword) {
            val path = keyStorePath
            if (path == null) {
                keyStoreOutcome = null
                selectedAlias = null
                readingKeyStore = false
                return@LaunchedEffect
            }
            readingKeyStore = true
            val outcome = withContext(Dispatchers.Default) {
                readKeyStore(path, null, appliedPassword.takeIf { it.isNotEmpty() }?.toCharArray())
            }
            keyStoreOutcome = outcome
            // 重新挑一个别名：换了文件、或条目变了之后，原先选中的那个可能已经不在了。优先挑一个
            // **能签名**的私钥条目——打开密钥库十有八九就是为了拿它去签。
            val entries = (outcome as? KeyStoreOutcome.Ready)?.entries.orEmpty()
            if (entries.none { it.alias == selectedAlias }) {
                selectedAlias = entries.firstOrNull { it.isKeyEntry }?.alias ?: entries.firstOrNull()?.alias
            }
            readingKeyStore = false
        }

        // 验签。换一个 APK 就重验一次：这一页的结论跟着文件走，留着上一个包的结论只会误导。
        LaunchedEffect(apkPath) {
            val path = apkPath
            if (path == null) {
                verifyOutcome = null
                verifying = false
                return@LaunchedEffect
            }
            verifying = true
            verifyOutcome = withContext(Dispatchers.Default) { verifyApk(path) }
            verifying = false
        }

        val keyStoreReady = keyStoreOutcome as? KeyStoreOutcome.Ready
        val entries = keyStoreReady?.entries.orEmpty()
        val selectedEntry = entries.firstOrNull { it.alias == selectedAlias }
        // 签名要五样东西齐：一个 APK、一把钥匙、一个**私钥**条目、一个口令、至少一个签名方案。
        // 一个方案都不勾时 apksig 会直接抛出来，这里先拦住——那不是一个「失败」，是一个还没填完的
        // 表单。
        val canSign = apkPath != null &&
            keyStorePath != null &&
            selectedEntry?.isKeyEntry == true &&
            password.isNotEmpty() &&
            schemes.isNotEmpty() &&
            !signing

        /**
         * 签名并另存。
         *
         * 先问保存到哪再动手：用户在那一步取消，就不该白签一遍（读私钥、算摘要、重写整个 ZIP 是
         * 几百毫秒到几秒的事）。整段在后台线程上。
         */
        fun sign() {
            val apk = apkPath ?: return
            val keystore = keyStorePath ?: return
            val alias = selectedEntry?.alias ?: return
            scope.launch {
                val target = host.pickFileToSave(signedFileNameOf(apk)) ?: return@launch
                if (target == apk) {
                    host.showStatus("输出不能就是原文件——换个名字，原包要留着")
                    return@launch
                }
                signing = true
                signOutcome = null
                val request = SignRequest(
                    apkPath = apk,
                    outPath = target,
                    keystorePath = keystore,
                    storeType = keyStoreReady?.storeType,
                    storePassword = password.toCharArray(),
                    keyAlias = alias,
                    // 密钥库口令与别名口令在 PKCS#12 里**必须**是同一个（格式本身就如此），JKS 里也
                    // 几乎总是同一个。这里先按同一个传；真不一样时下面的报错会说出来。
                    keyPassword = password.toCharArray(),
                    schemes = schemes,
                )
                val outcome = withContext(Dispatchers.Default) { signApk(request) }
                // 口令用完就擦：`request` 里那两份数组是这次运算唯一的副本。
                request.storePassword.fill('\u0000')
                request.keyPassword.fill('\u0000')
                signing = false
                signOutcome = outcome
                host.showStatus(
                    when (outcome) {
                        is SignOutcome.Done -> "已签好：${outcome.outPath}"
                        is SignOutcome.Failed -> "签名失败：${outcome.message}"
                    }
                )
                // 把**刚出炉那个包**摆到输入位上：这一页的结论跟着文件走，所以上面那个 effect 会
                // 自己重验一次，用户立刻看到「这次签名到底成没成」。文件名带 `-signed`，不会认错。
                if (outcome is SignOutcome.Done) apkPath = outcome.outPath
            }
        }

        /**
         * 建一个新密钥库。
         *
         * 成功后**就地收尾**：结论只摆在下面那句提示里（带完整路径），这一页不翻、表单也不动——
         * 用户多半还要接着调，或者顺手再建一个，把他甩到另一页去只会让他再切回来。这一页也不自己
         * 画一份结果：那是「密钥库」那一页的事，同一份内容两个地方画，早晚长得不一样。
         *
         * 也**不**顺手把新文件填进「密钥库」那一页。建一个密钥库与读一个密钥库是两件事：那一页
         * 是「我手上这个文件里是什么」，填进去等于替用户宣布「这就是你要看的那个文件」——而他建完
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
                when (outcome) {
                    // 只有这一句状态栏提示。**不翻页、也不往「密钥库」那一页填路径与口令**：
                    // 建一个密钥库跟读一个密钥库是两件事，这里只负责把手上这个建出来（见这个函数的
                    // 说明）。
                    is CreateOutcome.Done -> host.showStatus("已创建：${outcome.outPath}")

                    is CreateOutcome.Failed -> host.showStatus("创建失败：${outcome.message}")

                    // 不弹状态栏那一句：这事要在**面板上**说（按钮跟着变成「覆盖」），一闪而过的
                    // 提示没法承担「再点一次」这个动作。
                    is CreateOutcome.Exists -> Unit
                }
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
                    keyStorePath = keyStorePath,
                    password = password,
                    onPasswordChange = { password = it },
                    outcome = keyStoreOutcome,
                    entries = entries,
                    selectedAlias = selectedAlias,
                    onSelectAlias = { selectedAlias = it },
                    readerBusy = readingKeyStore,
                    readWithoutPassword = keyStoreReady?.readWithoutPassword ?: false,
                    onOpen = { host.pickFileToOpen()?.let { route(listOf(it), DevToolInputOrigin.Open, navigate = true) } },
                    onClear = { keyStorePath = null },
                    onFiles = { paths, origin -> route(paths, origin, navigate = true); "" },
                    host = host,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )

                ApkSignTab.Apk -> ApkPage(
                    apkPath = apkPath,
                    keyStorePath = keyStorePath,
                    verifying = verifying,
                    verifyOutcome = verifyOutcome,
                    selectedAlias = selectedAlias,
                    canSign = canSign,
                    signing = signing,
                    signOutcome = signOutcome,
                    schemes = schemes,
                    onToggleScheme = { scheme, enabled ->
                        schemes = if (enabled) schemes + scheme else schemes - scheme
                    },
                    onFiles = { paths, origin -> route(paths, origin, navigate = false); "" },
                    onOpenApk = { pickInto(SignInputKind.Apk) },
                    onOpenKeyStore = { pickInto(SignInputKind.KeyStore) },
                    onClearApk = { apkPath = null },
                    onClearKeyStore = { keyStorePath = null },
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
 * 字段与分组大体照 Android Studio 那张「新建密钥库」对话框：保存路径、口令 + 确认、别名、有效期，
 * 然后是证书那六项。**字段名改了**，因为照着抄的那几个中文标签都不好使：
 *
 *  - `名字与姓氏` → `姓名`（同一个意思，四个字变两个字）；
 *  - `组织单位` / `组织` → `部门` / `组织`（原样那对谁都分不出区别，而它们本来就是一个在另一个
 *    里面：DN 里 `O` 是组织本身、`OU` 是它下面的部门）。
 *
 * **默认值也不再照抄**（AS 把六格全填成 `Unknown`，用户得先删六个词）：只有姓名给一个能直接用的
 * 值，其余五格**留空**——空着的项不会写进证书（见 [subjectOf]），也就没有东西要删。
 *
 * **AS 没有的两行是这里多出来的**：`容器格式` 与 `加密算法`。AS 一律写 JKS + RSA 2048，一个字都
 * 不解释；而这页把两个选项都摆出来、**每项配一句人话**（`StoreFormat.advice` / `KeyAlgorithm.advice`）
 * ——「JKS 还是 PKCS#12」「RSA 还是 EC」都是要选的，既然要选，就得说清差别，而不是替用户定死再
 * 让他自己去查。
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

/** 认不出这个文件时的交代。粘贴与打开的语气不一样：一个是从剪贴板来的，一个是用户自己挑的。 */
private fun unrecognizedMessage(path: String, origin: DevToolInputOrigin): String = when (origin) {
    DevToolInputOrigin.Paste -> "剪贴板里的这个文件既不像密钥库，也不像 APK"
    else -> "这既不是密钥库（JKS / PKCS#12）也不是 APK：${path.substringAfterLast('/')}"
}

/**
 * 文件落进**不合适**的那个框时的交代（见 `routeTo`）。
 *
 * APK 那一页有两个框，左右并排（见 [ApkPage]）：左 APK、右密钥库。所以「该放哪边」说得出来，
 * 也值得说——用户此刻正盯着两个长得很像的框。
 */
private fun wrongCardMessage(expected: SignInputKind): String = when (expected) {
    SignInputKind.Apk -> "这是个密钥库，不属于「输入 · APK」——放到右边「签名 · 密钥库」那个框里"
    SignInputKind.KeyStore -> "这是个 APK，不属于「签名 · 密钥库」——放到左边「输入 · APK」那个框里"
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
    onFiles: (List<String>, DevToolInputOrigin) -> String?,
    host: DevToolHost,
    modifier: Modifier,
) {
    val entry = entries.firstOrNull { it.alias == selectedAlias }
    // 下面几处提示都要说「是哪个别名」：取一次，比每处各写一遍 `entry?.alias.orEmpty()` 干净。
    val entryAlias = entry?.alias.orEmpty()

    Column(modifier) {
        // 一整块输入区里摆一个槽。**必须是一个** `DevToolInputField`：窗口层的粘贴只记得住最后
        // 登记的那个输入区（见 `DevToolPasteKey`），一页摆两个的话，另一个就永远收不到 `⌘V`。
        FileInputArea(
            host = host,
            onFiles = onFiles,
            modifier = Modifier.fillMaxWidth().height(InputCardHeight),
        ) { cardModifier ->
            FileSlot(
                title = "输入 · 密钥库",
                path = keyStorePath,
                emptyHint = "把 .jks / .keystore / .p12 拖进来，或粘贴 / 打开一个",
                onOpen = onOpen,
                onClear = onClear,
                modifier = cardModifier,
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("密码", fontSize = 12.sp, color = MaterialTheme.hintColor)
            DevToolActionSpacer()
            SecretField(
                value = password,
                onValueChange = onPasswordChange,
                placeholder = "可留空",
                modifier = Modifier.width(200.dp),
            )
            DevToolActionSpacer()
            // 别名与口令排在同一行：它们是同一件事的两半（「哪把钥匙」与「它的口令」），改哪个都
            // 顺手，分两行反而要多上下看一次。
            Text("别名", fontSize = 12.sp, color = MaterialTheme.hintColor)
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
                    "口令只在这一次运算里用，不进历史，也没有任何复制它的入口。"

                // 留空**不**等于看不了——JKS 的证书是明文存的，不给口令照样能看。只有 PKCS#12 会把
                // 证书一起加密（实测 `getCertificate` 返回 null），那时得说清楚是「要口令」而不是
                // 一句笼统的「留空就行」。
                certificateState == CertificateAvailability.NeedsPassword ->
                    "这个密钥库把证书也一起加密了（PKCS#12 就是这么存的），填上口令才看得到。"

                else -> "口令留空能看证书与指纹；要拿去签名才需要填。留空时不做完整性校验——" +
                    "「能打开」只说明结构没坏，不代表口令对。"
            },
            fontSize = 11.sp,
            color = MaterialTheme.hintColor,
        )

        Spacer(Modifier.height(10.dp))

        // 容器类型与提供方从这次读出来的结果里取，不另开参数：它们就是 `outcome` 的一部分。
        val ready = outcome as? KeyStoreOutcome.Ready
        when {
            keyStorePath == null -> CenteredHint(
                text = "打开一个密钥库后，这里显示它的证书与指纹",
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            readerBusy -> CenteredHint("正在读…", Modifier.fillMaxWidth().weight(1f))

            outcome is KeyStoreOutcome.Failed -> FailedHint(
                message = outcome.message,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            // 读不到证书，而这次没给口令：**绝大多数**是 PKCS#12 把它锁住了（证书和私钥一起塞在加密
            // 的 safe bag 里）。说「填口令再看」，而不是断言它没有——那时打印出来的会是一份**缺了
            // 证书**的 keytool 文本，比不打印更容易被当成「这文件就是这样」。
            certificateState == CertificateAvailability.NeedsPassword -> CenteredHint(
                text = "别名「${entryAlias}」的证书要口令才读得出来——在上面填上密钥库口令再看",
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            // 给了口令还读不到，那才是真的没有（JKS 允许只放一把私钥）：说清缺什么，而不是画空白。
            certificateState == CertificateAvailability.Absent -> CenteredHint(
                text = "别名「$entryAlias」下没有证书，只有私钥",
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            ready == null || ready.entries.isEmpty() -> CenteredHint(
                text = "这个密钥库里没有条目",
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            // 结果就是 `keytool -list -v` 的原文（见 `keytoolText`）：条目一条不落、含条目之间的
            // 星号线。所以这里**不**按选中的别名过滤——`keytool` 也没有这个过滤，而「这一页打印的
            // 是什么」有一个唯一、可对照的答案，比顺手挑几段出来更有用。
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
                    // 不折行：`扩展` 那一段是十六进制转储，`0000: A4 D4 …` 与右边的 ASCII 列靠空格
                    // 对齐——一折行，那两列就散了，而「一眼扫过去」正是这份文本的读法。
                    softWrap = false,
                    // 没有行号、不做折叠：这不是代码，行号只会平白多一列数字。
                    lineNumbers = false,
                    folding = false,
                    scan = ::scanPlain,
                    placeholder = "打开一个密钥库后，这里显示 keytool -list -v 的内容",
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
 * 顶上那两个槽（左 APK、右密钥库）**共用一块输入区**，这有个不能动的理由：窗口层的粘贴只记得住
 * 最后登记的那个输入区（见 `DevToolPasteKey`），两个槽各挂一块的话，`⌘V` 就永远只会往后登记的那
 * 个去，左边那格再也粘不进东西。见 [FileInputArea]。
 *
 * 签名那一行排在结果区**下面**，且是主按钮：它是这一页唯一会写盘的动作，摆显眼是对的；但它需要
 * 四样东西齐全才亮（见 `canSign`），所以不存在「没看清结论就点错」这回事。
 */
@Composable
private fun ApkPage(
    apkPath: String?,
    keyStorePath: String?,
    verifying: Boolean,
    verifyOutcome: VerifyOutcome?,
    selectedAlias: String?,
    canSign: Boolean,
    signing: Boolean,
    signOutcome: SignOutcome?,
    schemes: Set<SignScheme>,
    onToggleScheme: (SignScheme, Boolean) -> Unit,
    onFiles: (List<String>, DevToolInputOrigin) -> String?,
    onOpenApk: () -> Unit,
    onOpenKeyStore: () -> Unit,
    onClearApk: () -> Unit,
    onClearKeyStore: () -> Unit,
    onSign: () -> Unit,
    host: DevToolHost,
    modifier: Modifier,
) {
    Column(modifier) {
        // 两个槽**共用一个输入区**（一个 `DevToolInputField`）。
        //
        // 这不是排版上的取舍，是功能上的硬约束：窗口层的粘贴只记得住最后登记的那个输入区
        // （见 `DevToolPasteKey`），两个槽各挂一个的话，`⌘V` 永远只会去后登记的那个——左边那格
        // 就再也粘不进东西了（拖放与打开照旧能用，所以这个 bug 只表现为「粘贴不好使」）。
        //
        // 合起来之后的规矩：拖放与粘贴**按内容**落到该去的那一格（按键落下来时谁也不知道用户瞄的
        // 是哪个槽）；两个槽各自的「打开」按钮仍按槽定位，那是用户明确点的那一格。
        FileInputArea(
            host = host,
            onFiles = onFiles,
            modifier = Modifier.fillMaxWidth().height(InputCardHeight),
        ) { cardModifier ->
            Row(cardModifier) {
                FileSlot(
                    title = "输入 · APK",
                    path = apkPath,
                    emptyHint = "把 .apk 拖进来，或粘贴 / 打开一个",
                    onOpen = onOpenApk,
                    onClear = onClearApk,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                Spacer(Modifier.width(10.dp))
                FileSlot(
                    title = "签名 · 密钥库",
                    path = keyStorePath,
                    emptyHint = "插进要用来签名的密钥库（口令在「密钥库」页填）",
                    onOpen = onOpenKeyStore,
                    onClear = onClearKeyStore,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
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
        val rows = signers.flatMapIndexed { index, signer -> signerRows(index, signer, signers.size > 1) }
        when {
            apkPath == null -> CenteredHint(
                text = "打开一个 APK 后，这里显示它当前的签名",
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            verifying -> CenteredHint("正在验签…", Modifier.fillMaxWidth().weight(1f))

            verifyOutcome is VerifyOutcome.Failed -> FailedHint(
                message = verifyOutcome.message,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            rows.isEmpty() -> CenteredHint(
                text = "这个包里一个签名都没有——还没签过，或者签名块被整个剥掉了",
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            else -> DevToolResultList(
                items = rows,
                label = { it.label },
                value = { it.value },
                onCopy = { host.copyToClipboard(it.value) },
                wrapValues = true,
                primary = { it.primary },
                labelWidth = ApkSignLabelWidth,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }

        Spacer(Modifier.height(10.dp))
        DevToolSectionDivider()
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("签名方案", fontSize = 12.sp, color = MaterialTheme.hintColor)
            DevToolActionSpacer()
            // 三个开关而不是「全选 / 自选」两档：它们本来就可以任意组合，用复选框说出来最直白
            // （见 `DevToolWidgets` 里对「开关」与「多选一」的分工）。
            schemes.forEach { scheme ->
                DevToolToggle(
                    title = scheme.title,
                    checked = scheme in schemes,
                    onCheckedChange = { onToggleScheme(scheme, it) },
                )
            }
            Spacer(Modifier.weight(1f))
            if (signing) {
                Text("正在签…", fontSize = 12.sp, color = MaterialTheme.hintColor)
                DevToolActionSpacer()
            }
            DevToolButton(
                title = "签名并另存为…",
                primary = true,
                enabled = canSign,
                onClick = onSign,
            )
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = when {
                signing -> "正在读私钥、算摘要、重写整个包…"
                signOutcome is SignOutcome.Failed -> "上次签名失败：${signOutcome.message}"
                signOutcome is SignOutcome.Done -> "上次签到了 ${signOutcome.outPath}；上面验的就是它。"
                apkPath == null -> "要先有一个 APK。"
                keyStorePath == null -> "再把要用的密钥库放到右边那个框里。"
                selectedAlias == null -> "那把钥匙里没有可用的别名。"
                schemes.isEmpty() -> "至少要勾一个签名方案（建议 v2 + v3）。"
                !canSign -> "还要填上密钥库口令才能签名（在「密钥库」那一页）。"
                else -> "签名结果写到你选的新文件，原包不动。"
            },
            fontSize = 11.sp,
            color = MaterialTheme.hintColor,
        )
    }
}

/**
 * 验签结论那一行。
 *
 * 「验过了但不通过」与「读不了这个包」必须分开说：前者正是这个工具存在的理由（这个包被人改过，
 * 或者签名跟你要的那把钥匙不是同一把），后者只是文件不对。一句「验签失败」把两件事糊在一起，
 * 用户只能瞎试。
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

        outcome is VerifyOutcome.Failed -> {
            text = outcome.message
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

        else -> {
            text = "还没有选 APK"
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
            FormRow("密码", hint = "至少 6 位，签名与读密钥库都要它") {
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
            outcome is CreateOutcome.Failed -> outcome.message
            outcome is CreateOutcome.Exists ->
                "这个位置已经有文件了。要覆盖它就再点一次——密钥库盖掉就找不回来"
            // 建完不翻页（见 `create()`），所以这一句就是用户看到的**结果**：成功，以及它在哪。
            outcome is CreateOutcome.Done -> "已创建 ${outcome.outPath}"
            problem != null -> problem
            else -> ""
        }
        // 只有**真试过并且失败**才用错误色。表单还没填完不算「错」——那张表一打开就红着一句
        // 「先选一个保存位置」，读起来是在训人，而它其实只是「下一步该点哪儿」；「已经有文件」
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
 * [hint] 里**不许写 Markdown 记号**：这些字是原样画出来的，`**` 与反引号会一个不落地显示出来
 * （占位符里踩过一次，图里看得很清楚）。
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
 * 一格里的一张证书项。六项除了标签、说明与取值完全是同一件事，收成一处免得写六遍一样的行。
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

/**
 * 结果列表里的一行：标签 + 值，点整行复制值。
 *
 * [primary] 挑出**真正要拿走的那些**（指纹）：结果列表会把它们画成主色。证书那几行同等重要，但
 * 不是「要拿走」的对象，保持默认色。
 */
internal class InfoRow(val label: String, val value: String, val primary: Boolean = false)

/**
 * 一个签名者 → 结果行。
 *
 * @param indexed 这个包里不止一个签名者时给每组加个序号。不加的话，两组的 `所有者` / `发布者` /
 *   指纹挨着排下来，**看不出哪几行属于谁**。只有一个签名者（绝大多数包）时不加——那是白占一行。
 */
private fun signerRows(index: Int, signer: SignerInfo, indexed: Boolean): List<InfoRow> = buildList {
    if (indexed) add(InfoRow("签名者[${index + 1}]", signer.certificate.subject))
    addAll(certificateRows(signer.certificate))
}

/**
 * 证书 → 结果行：字段 + 两枚指纹。
 *
 * **密钥库那一页不用它**：那边打印的是 `keytool -list -v` 的原文（见 `keytoolText`）。这里留在
 * 「标签 + 值、点一下复制」这套里，是因为 APK 这一页对应的命令行（`apksigner verify --print-certs`）
 * 本来就是逐项列出来的，而用户在这儿要拿走的通常只有一条（某张证书的某个摘要）。
 */
internal fun certificateRows(certificate: CertInfo): List<InfoRow> = buildList {
    addAll(certificateFieldRows(certificate))
    add(InfoRow("SHA-256", certificate.digests.sha256.toHex(separator = ":", upperCase = true), primary = true))
    add(InfoRow("SHA-1", certificate.digests.sha1.toHex(separator = ":", upperCase = true), primary = true))
}

/**
 * 一张证书的字段：名与序都照 `keytool -list -v` 里每张证书那一块的写法（先是所有者、发布者、
 * 序列号、两个时间，再是两张指纹）。
 *
 * 这里刻意**不把 `keytool` 那个带方括号的行标写进文档**：KDoc 会把方括号当成符号引用去找，找不到
 * 就报一句「Cannot resolve symbol」（实测踩到过）。
 */
private fun certificateFieldRows(certificate: CertInfo): List<InfoRow> = listOf(
    InfoRow("所有者", certificate.subject),
    InfoRow("发布者", certificate.issuer),
    InfoRow("序列号", certificate.serial),
    InfoRow("生效时间", formatCertDate(certificate.notBeforeMillis)),
    InfoRow("失效时间", formatCertDate(certificate.notAfterMillis)),
    InfoRow("签名算法名称", certificate.signatureAlgorithm),
    InfoRow(
        label = "主体公共密钥算法",
        value = if (certificate.publicKeyBits > 0) {
            "${certificate.publicKeyBits} 位 ${certificate.publicKeyAlgorithm} 密钥"
        } else {
            certificate.publicKeyAlgorithm
        },
    ),
)

/**
 * 一页的输入区：**一个** [DevToolInputField]，里面装什么由 [content] 画。
 *
 * 收成一处是为了让「一页只有一个输入区」这条约束有个落点，而不是靠调用点自觉：窗口层的粘贴只
 * 记得住最后登记的那个输入区（见 `DevToolPasteKey`），一页摆两个的话，另一个就永远收不到 `⌘V`
 * ——而它只表现为「粘贴不好使」，拖放与打开照旧正常，很难一眼看出是布局的锅。
 *
 * 卡片这一侧的行为与条码工具的码图卡一致：它**一直**替掉文本框（[DevToolInputField] 的
 * `sourceCard`），因为这两页都没有可敲的正文，摆一个编辑框只会让人以为能粘一段字进去。
 *
 * @param onFiles 文件落进这一块（打开 / 拖入 / 粘贴）。调用方返回空串——卡片上没有文本框可填，
 *   而空串正是 `DevToolInputField` 约定的「这次输入我接管了」，顺带**吞掉**系统的「粘成文件名」。
 */
@Composable
private fun FileInputArea(
    host: DevToolHost,
    onFiles: (List<String>, DevToolInputOrigin) -> String?,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    DevToolInputField(
        label = "",
        value = "",
        onValueChange = {},
        host = host,
        modifier = modifier,
        onFiles = onFiles,
        sourceCard = content,
    )
}

/**
 * 输入区里的**一个槽**：一行框名 + 打开 / 清空两个动作，中间是路径或空态提示。
 *
 * 它**自己不接拖放与粘贴**——那是外面那一整块输入区的事（见 [FileInputArea]）。这个槽只负责画，
 * 以及它那两个按钮：按钮知道自己是哪一格，而键按落下时谁也不知道。
 */
@Composable
private fun FileSlot(
    title: String,
    path: String?,
    emptyHint: String,
    onOpen: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)
    val codeColors = rememberCodeColors()

    Column(
        modifier = modifier
            .clip(shape)
            .background(codeColors.editorBackground)
            .border(1.dp, colors.outline.copy(alpha = 0.6f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 13.sp, color = MaterialTheme.hintColor)
            Spacer(Modifier.weight(1f))
            DevToolFieldAction(
                kind = ClipperIconKind.FOLDER,
                tooltip = if (path == null) "打开文件" else "换一个文件",
                onClick = onOpen,
            )
            Spacer(Modifier.width(4.dp))
            DevToolFieldAction(
                kind = ClipperIconKind.TRASH,
                tooltip = "清除",
                enabled = path != null,
                onClick = onClear,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.CenterStart) {
            if (path == null) {
                Text(emptyHint, fontSize = 12.sp, color = MaterialTheme.hintColor)
            } else {
                Text(
                    // 只给文件名：整条路径会把更该看见的东西挤掉，而完整路径在状态栏的「来自…」
                    // 那一栏里就有。
                    text = path.substringAfterLast('/').ifBlank { path },
                    fontSize = 13.sp,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 口令输入框。
 *
 * 刻意不复用共用的 `DevToolSingleLineField`：那一个是明文显示的，而口令框在这个应用里格外敏感
 * ——应用本身就是剪贴板管理器，窗口常被投屏、截图分享。这里只多一件事：
 * `PasswordVisualTransformation` 把字打成点。其余（高度、底色、聚焦描边）与那个控件保持一致，
 * 免得同一个面板里出现两种输入框。
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
