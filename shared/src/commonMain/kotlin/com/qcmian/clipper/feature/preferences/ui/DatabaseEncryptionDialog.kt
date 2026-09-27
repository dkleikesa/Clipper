package com.qcmian.clipper.feature.preferences.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.qcmian.clipper.core.ui.theme.hintColor
import com.qcmian.clipper.feature.history.state.EncryptionPrompt

/**
 * 数据库加密的密码框。
 *
 * 开启时收两次口令（新口令 + 确认），关闭时只做一次确认——解密不需要口令，连接上本来就
 * 带着当前口令。口令只在本地校验，**不落盘**：换钥成功后随本框一起消失。
 *
 * 与「清除历史」的确认框同风格（10dp 圆角、`surface` 底 + 6dp tonal elevation），但高一些，
 * 因为开启态要放下两个输入框与一条说明。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatabaseEncryptionDialog(
    prompt: EncryptionPrompt,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var password by remember(prompt.enabled) { mutableStateOf("") }
    var confirmation by remember(prompt.enabled) { mutableStateOf("") }
    var mismatch by remember(prompt.enabled) { mutableStateOf(false) }

    val problem = prompt.error ?: if (mismatch) "两次输入的口令不一致。" else null
    val confirmEnabled = !prompt.working && (!prompt.enabled || password.isNotEmpty())

    BasicAlertDialog(
        // 重写整库期间不允许点框外取消：半途中断的状态不可知，不如让用户等它跑完。
        onDismissRequest = { if (!prompt.working) onDismiss() },
        modifier = Modifier.width(340.dp),
        properties = DialogProperties(),
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = colors.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, top = 20.dp, end = 24.dp, bottom = 15.dp),
            ) {
                Text(
                    text = if (prompt.enabled) "开启数据库加密" else "关闭数据库加密",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Text(
                    text = if (prompt.enabled) {
                        "历史与偏好将用 SQLCipher 加密落盘。口令不会保存，每次启动都要重新输入；" +
                            "一旦忘记，数据将无法恢复。"
                    } else {
                        "关闭后历史与偏好将以明文落盘，任何能读到该文件的程序都能看到内容。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )

                if (prompt.enabled) {
                    PasswordField(
                        value = password,
                        label = "新口令",
                        enabled = !prompt.working,
                        onValueChange = {
                            password = it
                            mismatch = false
                        },
                    )
                    PasswordField(
                        value = confirmation,
                        label = "确认口令",
                        enabled = !prompt.working,
                        onValueChange = {
                            confirmation = it
                            mismatch = false
                        },
                    )
                }

                if (problem != null) {
                    Text(
                        text = problem,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else if (prompt.working) {
                    Text(
                        text = "正在重写数据库，请稍候……",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.hintColor,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss, enabled = !prompt.working) {
                        Text("取消")
                    }
                    TextButton(
                        enabled = confirmEnabled,
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.primary),
                        onClick = {
                            if (!prompt.enabled) {
                                onConfirm("")
                            } else if (password != confirmation) {
                                mismatch = true
                            } else {
                                onConfirm(password)
                            }
                        },
                    ) {
                        Text("确定", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** 一个口令输入框（密文显示、单行）。 */
@Composable
private fun PasswordField(
    value: String,
    label: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
    )
}
