package com.qcmian.clipper

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.qcmian.clipper.di.AppContainer

/**
 * Android Studio 的布局预览入口。放在 `debug` 源集：它只是开发期的可视化工具，
 * 不该出现在发布构建里。
 *
 * 预览没有 `Application`，因此这里临时建一个容器——它不落盘，也不会与真实数据互相影响。
 */
@Preview
@Composable
fun AppAndroidPreview() {
    App(container = remember { AppContainer() })
}
