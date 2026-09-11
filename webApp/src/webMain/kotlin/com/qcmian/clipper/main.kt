package com.qcmian.clipper

import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.qcmian.clipper.di.AppContainer

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport {
        val container = remember { AppContainer() }
        App(container = container)
    }
}
