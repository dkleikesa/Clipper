package com.qcmian.clipper

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.qcmian.clipper.di.AppContainer

fun MainViewController() = ComposeUIViewController {
    val container = remember { AppContainer() }
    App(container = container)
}
