package com.meowmic.client

/**
 * Rust JNI 桥接
 *
 * Native 方法对应 android/rust-core/src/lib.rs 中的实现。
 * 加载的 so 库名为 libmeowmic.so(由 Cargo.toml 的 [lib].name 指定)。
 */
object NativeBridge {
    @Volatile
    private var loaded: Boolean = false

    init {
        try {
            System.loadLibrary("meowmic")
            loaded = true
        } catch (e: UnsatisfiedLinkError) {
            // so 库未加载(可能未编译),会在 native 调用时抛错
            android.util.Log.w("MeowMic", "libmeowmic.so 未加载: ${e.message}")
        }
    }

    fun isLoaded(): Boolean = loaded

    /**
     * 连接到服务端
     * @param serverAddr 形如 "192.168.1.100:28900" (control 端口,touch/audio 自动 +1/+2)
     * @param clientName 客户端名称,用于服务端日志识别
     * @return true 表示连接握手成功
     */
    external fun nativeConnect(serverAddr: String, clientName: String): Boolean

    /**
     * 发送触摸事件
     * @param eventType 0x01=Down 0x02=Move 0x03=Up 0x04=Button
     * @param dx 相对 X 位移(像素)
     * @param dy 相对 Y 位移(像素)
     */
    external fun nativeSendTouch(eventType: Int, dx: Float, dy: Float): Boolean

    /**
     * 发送一帧音频 PCM
     * @param pcm i16 PCM,长度必须 >= 960 (48kHz * 20ms)
     */
    external fun nativeSendAudioFrame(pcm: ShortArray): Boolean

    /** 优雅断开 */
    external fun nativeDisconnect()

    /** 获取统计 JSON: {"touch_sent":N,"audio_sent":N} */
    external fun nativeGetStats(): String
}
