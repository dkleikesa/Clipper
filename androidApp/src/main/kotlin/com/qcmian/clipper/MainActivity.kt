package com.qcmian.clipper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

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
