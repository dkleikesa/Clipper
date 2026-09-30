package com.qcmian.clipper.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 退出码是对**外部调用方**的承诺（README 里逐条写着），shell 脚本与 agent 靠它分支；
 * 数值本身因此是契约，不能随手改。
 */
class CliExitCodeTest {

    @Test
    fun `documented numeric values are stable`() {
        assertEquals(0, CliExitCode.OK)
        assertEquals(1, CliExitCode.ERROR)
        assertEquals(2, CliExitCode.USAGE)
        assertEquals(3, CliExitCode.NOT_FOUND)
        assertEquals(4, CliExitCode.NO_DAEMON)
        assertEquals(5, CliExitCode.TIMEOUT)
    }

    @Test
    fun `error codes map to the documented exit codes`() {
        assertEquals(CliExitCode.USAGE, CliExitCode.forErrorCode(CliErrorCode.BAD_REQUEST))
        assertEquals(CliExitCode.USAGE, CliExitCode.forErrorCode(CliErrorCode.UNKNOWN_COMMAND))
        assertEquals(CliExitCode.NOT_FOUND, CliExitCode.forErrorCode(CliErrorCode.NOT_FOUND))
        assertEquals(CliExitCode.NO_DAEMON, CliExitCode.forErrorCode(CliErrorCode.DAEMON_UNAVAILABLE))
        assertEquals(CliExitCode.TIMEOUT, CliExitCode.forErrorCode(CliErrorCode.TIMEOUT))
    }

    @Test
    fun `anything unrecognised falls back to the generic error code`() {
        assertEquals(CliExitCode.ERROR, CliExitCode.forErrorCode(CliErrorCode.INTERNAL))
        assertEquals(CliExitCode.ERROR, CliExitCode.forErrorCode(CliErrorCode.UNSUPPORTED))
        assertEquals(CliExitCode.ERROR, CliExitCode.forErrorCode(""))
        // 新版本 app 可能返回旧 CLI 没听说过的错误码：当作未知错误，而不是崩掉。
        assertEquals(CliExitCode.ERROR, CliExitCode.forErrorCode("SOMETHING_NEW"))
    }
}
