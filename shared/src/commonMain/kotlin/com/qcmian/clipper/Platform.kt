package com.qcmian.clipper

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform