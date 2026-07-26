# MeowMic Android 客户端 proguard 规则

# JNI 桥接类(Native 方法不能被混淆)
-keep class com.meowmic.client.** { *; }
-keepclassmembers class com.meowmic.client.** {
    native <methods>;
}

# Rust 编译产物(符号已 strip,无需额外保留)
