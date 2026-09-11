package com.qcmian.clipper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.qcmian.clipper.di.AppContainer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 依赖图由 Application 持有，因此能跨配置变更存活。
        val container = (application as ClipperApplication).container
        setContent {
            App(container = container)
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    // 预览没有 Application，用一个临时容器就足够了。
    App(container = remember { AppContainer() })
}
