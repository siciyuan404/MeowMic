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
     * @return 0=失败, 1=已连接, 2=需要配对(等待 nativeCompletePairing),
     *         3=地址无效, 4=主机不可达(TCP 超时), 5=连接被拒绝(服务未启动/端口错误)
     */
    external fun nativeConnect(serverAddr: String, clientName: String): Int

    /**
     * 直接用已配对身份连接(跳过 Hello,直接发送 HelloPaired)
     * @return 0=失败, 1=已连接
     */
    external fun nativeConnectPaired(serverAddr: String, clientName: String): Int

    /**
     * 完成配对(用户输入 PIN 后调用)
     * @param pin 用户输入的 PIN(正向:PC 控制台显示的 PIN;反向:本机生成并显示的 PIN)
     * @return 0=失败(连接已坏,需重新连接), 1=配对成功并已连接,
     *         6=配对被拒绝(PIN 错误等,可用新 PIN 重试),
     *         7=等待响应超时(可重试)
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
     * 获取本客户端的 Ed25519 公钥(base64)
     * 用途:轮询 /serverinfo?pubkey=<此值> 查询服务端侧配对状态(pair_status)
     * @return base64 公钥;状态目录未初始化或读取失败时返回空字符串
     */
    external fun nativeGetClientPubkeyB64(): String

    /**
     * 检查 TCP 控制连接是否存活(由后台事件循环维护)
     */
    external fun nativeIsConnected(): Boolean

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
     * 发送带标志位的滚轮事件(支持水平滚动 / 缩放)
     * @param deltaY 垂直滚动量(正值向上)
     * @param deltaX 水平滚动量(正值向右)
     * @param buttonMask Scroll 事件标志位:
     *        bit0=垂直滚动(默认) bit1=水平滚动 bit2=缩放(dy>0 放大 / dy<0 缩小)
     *        传 0 等价于垂直滚动
     */
    fun sendScrollWithButton(deltaY: Float, deltaX: Float = 0f, buttonMask: Int = 1): Boolean {
        return try {
            nativeSendTouchWithButton(0x05, buttonMask, deltaX, deltaY)
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    /** 垂直滚动便捷方法(等价于 sendScroll,但显式带 button_mask=1) */
    fun sendVerticalScroll(deltaY: Float): Boolean = sendScrollWithButton(deltaY)

    /** 水平滚动便捷方法 */
    fun sendHorizontalScroll(deltaX: Float): Boolean =
        sendScrollWithButton(0f, deltaX, buttonMask = 2)

    /**
     * 缩放便捷方法(MAC 风格双指捏合)
     * @param dy 正值=放大,负值=缩小
     */
    fun sendZoom(dy: Float): Boolean = sendScrollWithButton(dy, buttonMask = 4)

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

    // ============ 视频 ============

    /**
     * 请求服务端开始视频推流(UDP push 模式)
     * @param width 屏幕宽度(如 1920)
     * @param height 屏幕高度(如 1080)
     * @param fps 目标帧率(如 30)
     * @param bitrate 目标码率(如 4_000_000 = 4Mbps)
     * @return true 成功启动接收循环;false 未连接或发送失败
     */
    external fun nativeStartVideo(width: Int, height: Int, fps: Int, bitrate: Int): Boolean

    /**
     * 取出一个完整 NALU(非阻塞)
     * @return NALU 字节数组,或 null 表示无完整帧
     */
    external fun nativePollVideoFrame(): ByteArray?

    /**
     * 取出并清零视频统计快照
     * @return JSON {"received":N,"lost":N,"recovered":N}
     * 视频未启动时返回全 0
     */
    external fun nativePollVideoStats(): String

    /**
     * 上报视频统计给服务端(用于自适应码率)
     * @return true 成功,false 未连接或发送失败
     */
    external fun nativeSendVideoStats(received: Int, lost: Int, recovered: Int, rttMs: Int): Boolean

    /** 停止视频推流 */
    external fun nativeStopVideo()

    // ============ PC→手机 音频(手机当喇叭) ============

    /**
     * 请求服务端开始 PC→手机 音频推流(手机充当电脑喇叭)
     * @param channel 0=左声道, 1=右声道, 2=立体声混合
     * @return true 成功启动接收循环;false 未连接或发送失败
     */
    external fun nativeStartAudioStream(channel: Int): Boolean

    /**
     * 从 jitter buffer 取出一帧解码后的 PCM(非阻塞)
     * @param pcm i16 PCM 数组,长度必须 >= 960
     * @return 实际样本数;0 表示尚无可用帧
     */
    external fun nativePollAudioFrame(pcm: ShortArray): Int

    /** 停止 PC→手机 音频推流(停止接收并通知服务端) */
    external fun nativeStopAudioStream()
}
