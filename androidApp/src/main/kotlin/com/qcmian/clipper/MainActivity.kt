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

        // The graph is owned by the Application, so it survives configuration changes.
        val container = (application as ClipperApplication).container
        setContent {
            App(container = container)
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    // Previews have no Application; a throwaway container is enough there.
    App(container = remember { AppContainer() })
}
