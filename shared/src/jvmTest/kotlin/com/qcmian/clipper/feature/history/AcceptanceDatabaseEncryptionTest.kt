@file:OptIn(ExperimentalCoroutinesApi::class)

package com.qcmian.clipper.feature.history

import com.qcmian.clipper.core.data.local.FakeBackup
import com.qcmian.clipper.core.data.local.MutableSessionKey
import com.qcmian.clipper.core.data.local.openFileDatabase
import com.qcmian.clipper.core.data.source.isDatabaseEncrypted
import com.qcmian.clipper.core.data.source.verifyDatabasePassphrase
import com.qcmian.clipper.core.testutil.InProcessCluster
import com.qcmian.clipper.feature.history.state.ClipboardUiAction
import com.qcmian.clipper.feature.history.viewmodel.ClipboardViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F8（A 层）：**数据库加密**。
 *
 * 真 SQLCipher 驱动 + **落盘**的库 + 真 `ClipboardViewModel`：口令是会话级的，因此「重启」在
 * 测试里就是关库再用另一个口令重新打开——这也是唯一能验证「没口令真的打不开」的办法。
 *
 * 覆盖：开启后重启需要口令、错误口令被拒、关闭加密前必须核对当前口令。
 */
class AcceptanceDatabaseEncryptionTest {

    private lateinit var directory: File
    private lateinit var file: File

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        directory = Files.createTempDirectory("clipper-encryption").toFile()
        file = File(directory, "clipper.db")
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        directory.deleteRecursively()
    }

    @Test
    fun `开启加密后重启需要口令`() = runBlocking<Unit> {
        // ① 开启加密。
        enableEncryption()

        assertTrue(isDatabaseEncrypted(file), "开启之后文件头不再是明文 SQLite")

        // ② 重启但不给口令：连读都不能读。
        val locked = openFileDatabase(file, passphrase = { null })
        try {
            val failure = runCatching { locked.clipHistoryDao().countAll() }
            assertTrue(failure.isFailure, "没有口令就打不开加密库——这正是「保护」的含义")
        } finally {
            locked.close()
        }

        // 口令校验：错的被拒，对的通过。
        assertFalse(verifyDatabasePassphrase(file, "猜的"))
        assertTrue(verifyDatabasePassphrase(file, "秘密"))

        // ③ 给出正确口令：读得到。
        val unlocked = openFileDatabase(file, passphrase = { "秘密" })
        try {
            assertEquals(0, unlocked.clipHistoryDao().countAll(), "用正确口令重开，数据照常可读")
        } finally {
            unlocked.close()
        }
    }

    @Test
    fun `关闭加密前必须核对当前口令`() = runBlocking<Unit> {
        enableEncryption()

        val key = MutableSessionKey("秘密")
        open(key).let { cluster ->
            try {
                cluster.start()
                assertTrue(cluster.repository.databaseEncrypted, "带口令打开的是一个加密库")

                val viewModel = ClipboardViewModel(cluster.repository, cluster.repository, cluster.useCases)

                // 拨动开关只打开密码框；真正的换钥发生在确认口令之后。
                viewModel.onAction(ClipboardUiAction.RequestDatabaseEncryption(enabled = false))

                // 错口令：核对不通，仍是加密库。
                viewModel.onAction(ClipboardUiAction.ConfirmDatabaseEncryption("猜的"))
                await { viewModel.uiState.value.encryptionPrompt?.error != null }
                assertTrue(cluster.repository.databaseEncrypted, "口令不对就不能把保护去掉")
                assertTrue(isDatabaseEncrypted(file))

                // 对口令：解密回明文。
                viewModel.onAction(ClipboardUiAction.ConfirmDatabaseEncryption("秘密"))
                await { viewModel.uiState.value.encryptionPrompt == null && !cluster.repository.databaseEncrypted }
            } finally {
                cluster.shutdown()
            }
        }

        assertFalse(isDatabaseEncrypted(file), "解密之后文件头回到明文")
        val plain = openFileDatabase(file, passphrase = { null })
        try {
            assertEquals(0, plain.clipHistoryDao().countAll(), "明文库无口令即可打开")
        } finally {
            plain.close()
        }
    }

    // -------------------------------------------------------------------------------------

    /** 起一个加密库、从设置页开启加密、再关库。 */
    private suspend fun enableEncryption() {
        val key = MutableSessionKey()
        open(key).let { cluster ->
            try {
                cluster.start()
                assertTrue(cluster.repository.supportsDatabaseEncryption)

                val viewModel = ClipboardViewModel(cluster.repository, cluster.repository, cluster.useCases)
                viewModel.onAction(ClipboardUiAction.RequestDatabaseEncryption(enabled = true))
                viewModel.onAction(ClipboardUiAction.ConfirmDatabaseEncryption("秘密"))

                await { viewModel.uiState.value.encryptionPrompt == null && cluster.repository.databaseEncrypted }
            } finally {
                cluster.shutdown()
            }
        }
    }

    /**
     * 以 [key] 的口令打开同一个库文件。
     *
     * 装配与线上一致（见 `createClipStorageDataSource`）：会话密钥 + 换钥备份 + 关闭加密前的
     * 口令核对。只有路径是临时目录。
     */
    private fun open(key: MutableSessionKey): InProcessCluster = InProcessCluster(
        database = openFileDatabase(file, passphrase = { key.current }),
        sessionKey = key,
        backup = FakeBackup(),
        passphraseVerifier = { verifyDatabasePassphrase(file, it) },
    )

    private suspend fun await(timeoutMillis: Long = 15_000L, condition: () -> Boolean) {
        withTimeout(timeoutMillis) {
            while (!condition()) delay(5L)
        }
    }
}
