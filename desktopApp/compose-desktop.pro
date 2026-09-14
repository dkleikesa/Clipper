# Room 在 JVM 上通过数据库类名反射加载对应的 _Impl 类；Release 不能改名或裁剪它。
-keep class * extends androidx.room3.RoomDatabase { *; }

# JNA 动态解析原生方法与回调；保留其实现，避免运行时找不到 Native.dispose 等成员。
-keep class com.sun.jna.** { *; }


# Bundled SQLite 通过 JNI 绑定 Kotlin 顶层 native 方法；不得改名或裁剪。
-keep class androidx.sqlite.driver.bundled.** { *; }

# macOS 原生层将 Kotlin 回调注册到 Objective-C 运行时；回调方法由 JNA 反射发现。
-keep class com.qcmian.clipper.core.platform.macos.** { *; }
