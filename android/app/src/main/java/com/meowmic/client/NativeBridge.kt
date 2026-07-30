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
     * 设置配对状态文件所在目录(由 Kotlin 通过 Context.getFilesDir() 传入)
     * 必须在 nativeConnect 之前调用
     */
    external fun nativeSetStateDir(path: String)

    /**
     * 连接到服务端
     * @param serverAddr 形如 "192.168.1.100:28900" (control 端口,touch/audio 自动 +1/+2)
     * @param clientName 客户端名称,用于服务端日志识别
     * @return 0=失败, 1=已连接, 2=需要配对(等待 nativeCompletePairing)
     */
    external fun nativeConnect(serverAddr: String, clientName: String): Int

    /**
     * 直接用已配对身份连接(跳过 Hello,直接发送 HelloPaired)
     * @return 0=失败, 1=已连接
     */
    external fun nativeConnectPaired(serverAddr: String, clientName: String): Int

    /**
     * 完成配对(用户输入 PIN 后调用)
     * @param pin 用户输入的 PIN
     * @return 0=失败, 1=配对成功并已连接
     */
    external fun nativeCompletePairing(pin: String): Int

    /** 取消 pending 配对状态(断开连接) */
    external fun nativeCancelPairing()

    /**
     * 查询是否已配对该服务端
     * @param serverPubkeyB64 服务端公钥的 base64 字符串
     */
    external fun nativeIsServerPaired(serverPubkeyB64: String): Boolean

    /**
     * 发送触摸事件
     * @param eventType 0x01=Down 0x02=Move 0x03=Up 0x04=Button 0x05=Scroll
     * @param dx 相对 X 位移(像素)
     * @param dy 相对 Y 位移(像素)
     */
    external fun nativeSendTouch(eventType: Int, dx: Float, dy: Float): Boolean

    /**
     * 发送带按钮掩码的触摸事件
     * @param eventType 0x04=Button (其他同 nativeSendTouch)
     * @param buttonMask bit0=左键 bit1=右键 bit2=中键
     * @param dx 对于 Button 事件: >0 表示按下, <=0 表示抬起
     * @param dy 相对 Y 位移(像素)
     */
    external fun nativeSendTouchWithButton(
        eventType: Int,
        buttonMask: Int,
        dx: Float,
        dy: Float,
    ): Boolean

    // ============ 便捷方法 ============

    /**
     * 发送鼠标按键按下事件
     * @param buttonMask bit0=左键 bit1=右键 bit2=中键
     */
    fun sendButtonDown(buttonMask: Int): Boolean {
        return try {
            nativeSendTouchWithButton(0x04, buttonMask, 1f, 0f)
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    /**
     * 发送鼠标按键抬起事件
     * @param buttonMask bit0=左键 bit1=右键 bit2=中键
     */
    fun sendButtonUp(buttonMask: Int): Boolean {
        return try {
            nativeSendTouchWithButton(0x04, buttonMask, 0f, 0f)
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    /**
     * 发送鼠标按键点击(按下+抬起)
     * @param buttonMask bit0=左键 bit1=右键 bit2=中键
     */
    fun sendButtonClick(buttonMask: Int): Boolean {
        val down = sendButtonDown(buttonMask)
        val up = sendButtonUp(buttonMask)
        return down && up
    }

    /**
     * 发送滚轮事件
     * @param deltaY 滚动量(正值向上,负值向下)
     */
    fun sendScroll(deltaY: Float): Boolean {
        return try {
            nativeSendTouch(0x05, 0f, deltaY)
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    /**
     * 发送一帧音频 PCM
     * @param pcm i16 PCM,长度必须 >= 960 (48kHz * 20ms)
     */
    external fun nativeSendAudioFrame(pcm: ShortArray): Boolean

    /** 优雅断开 */
    external fun nativeDisconnect()

    /**
     * 通知服务端切换外放静音状态
     * @param muted true=静音(丢弃 PCM 不输出到 PC 扬声器)
     * @return true 表示控制消息发送成功
     */
    external fun nativeSetMuteSpeaker(muted: Boolean): Boolean

    /**
     * 发送键盘事件(走 TCP 控制通道,可靠传递)
     * @param keyCode Windows VK code(如 0x11=Ctrl, 0x43=C)
     * @param isDown true=按下, false=抬起
     * @return true 表示控制消息发送成功
     */
    external fun nativeSendKey(keyCode: Int, isDown: Boolean): Boolean

    // ============ 键盘便捷方法 ============

    /**
     * 发送键盘按键按下事件
     * @param keyCode Windows VK code
     */
    fun sendKeyDown(keyCode: Int): Boolean {
        return try {
            nativeSendKey(keyCode, true)
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    /**
     * 发送键盘按键抬起事件
     * @param keyCode Windows VK code
     */
    fun sendKeyUp(keyCode: Int): Boolean {
        return try {
            nativeSendKey(keyCode, false)
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    /**
     * 发送键盘按键点击(按下+抬起)
     * @param keyCode Windows VK code
     */
    fun sendKeyPress(keyCode: Int): Boolean {
        val down = sendKeyDown(keyCode)
        val up = sendKeyUp(keyCode)
        return down && up
    }

    /**
     * 发送组合键(按下所有键 → 抬起所有键,逆序)
     * @param keyCodes VK code 数组,按顺序传入(如 [VK_CONTROL, VK_C] 表示 Ctrl+C)
     */
    fun sendKeyCombo(vararg keyCodes: Int): Boolean {
        if (keyCodes.isEmpty()) return false
        // 顺序按下,记录已成功的以便失败时回滚
        val pressed = mutableListOf<Int>()
        for (kc in keyCodes) {
            if (!sendKeyDown(kc)) {
                // 失败回滚:逆序抬起已按下的键,避免按键在服务端粘住
                for (down in pressed.asReversed()) {
                    sendKeyUp(down)
                }
                return false
            }
            pressed.add(kc)
        }
        // 逆序抬起
        for (kc in keyCodes.reversedArray()) {
            if (!sendKeyUp(kc)) return false
        }
        return true
    }

    /** 获取统计 JSON: {"touch_sent":N,"audio_sent":N} */
    external fun nativeGetStats(): String
}
