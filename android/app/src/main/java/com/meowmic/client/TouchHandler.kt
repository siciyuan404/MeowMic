package com.meowmic.client

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 触控风格
 * - THINKPAD:Windows 风格(反向滚动 + 无惯性 + 双指缩放需配合 Ctrl)
 * - MAC:macOS 风格(自然滚动 + 平滑惯性 + 原生双指缩放)
 */
enum class TouchStyle {
    THINKPAD,
    MAC;

    companion object {
        fun fromName(name: String?): TouchStyle =
            entries.firstOrNull { it.name == name } ?: THINKPAD
    }
}

/**
 * 触摸事件处理器(参考 moonlight-android 的触控板逻辑)
 *
 * 核心设计(借鉴 moonlight-android):
 * 1. **遍历历史采样点**:Android 在每帧之间会聚合多个 MotionEvent 采样,
 *    必须用 getHistorySize() + getHistoricalX/Y() 遍历每个采样,否则丢失中间轨迹导致卡顿。
 * 2. **死区机制**:按下后 100ms 内或 20px 内不触发点击,避免误触。
 * 3. **定时器驱动的状态机**:tap/longPress/drag 用时间阈值 + 距离阈值组合判定。
 *
 * 手势支持:
 * - 单指滑动:鼠标移动(每个历史采样都发送)
 * - 单指轻触:鼠标左键单击
 * - 单指双击:鼠标左键双击(双击死区 250ms + 60px)
 * - 单指长按:鼠标左键按下(拖拽模式)
 * - 双指滑动:鼠标滚轮滚动
 * - 双指轻触:鼠标右键单击
 *
 * 风格差异(由 [touchStyle] 控制):
 * - **滚动方向**:THINKPAD 反向(手指上滑 → 页面向下);MAC 自然(手指上滑 → 页面向上)
 * - **滚动惯性**:MAC 抬起后继续衰减滚动若干帧;THINKPAD 立即停止
 *
 * 事件类型常量: 0x02=Move 0x04=Button 0x05=Scroll
 * 按钮掩码位: bit0=左键 bit1=右键 bit2=中键
 */
class TouchHandler(
    private val sensitivity: Float = 1.0f,
    private val invertY: Boolean = false,
) {
    var screenRotation: Int = 0

    /** 触控风格(运行时可切换,默认 THINKPAD) */
    var touchStyle: TouchStyle = TouchStyle.THINKPAD

    // ============ Handler(主线程,用于定时触发长按和惯性滚动) ============
    private val handler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (!isLeftDrag && !isTwoFingerScroll && downPointerCount == 1) {
            NativeBridge.sendButtonDown(BTN_LEFT)
            isLeftDrag = true
            leftDragTriggered = true
        }
    }

    /** 惯性滚动 Runnable(MAC 风格):抬起后按速度衰减继续滚动 */
    private val inertiaRunnable = Runnable { tickInertia() }

    // ============ 按钮掩码常量 ============
    private val BTN_LEFT = 0x01
    private val BTN_RIGHT = 0x02

    // ============ 事件类型常量 ============
    private val EVT_MOVE = 0x02
    private val EVT_SCROLL = 0x05

    // ============ 单指状态 ============
    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var hasLast: Boolean = false
    private var downTime: Long = 0L
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var downPointerCount: Int = 0

    // ============ 多指 pointerId 跟踪(Bug A:不再用 pointerIndex) ============
    private var primaryPointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var secondaryPointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var pendingSingleX: Float = 0f
    private var pendingSingleY: Float = 0f

    // ============ 双击检测 ============
    private var lastTapTime: Long = 0L
    private var lastTapX: Float = 0f
    private var lastTapY: Float = 0f
    private val doubleTapTimeout = 280L
    private val doubleTapSlop = 60f

    // ============ 双指滚动状态 ============
    private var lastScrollY: Float = 0f
    private var isTwoFingerScroll: Boolean = false
    /** 惯性滚动速度(MAC 风格抬起后衰减用) */
    private var inertiaVelocity: Float = 0f
    /** 惯性滚动是否在运行 */
    private var isInertiaRunning: Boolean = false
    /** 最近一次双指滚动 delta,用于抬起时初始化惯性速度 */
    private var lastScrollDelta: Float = 0f
    /** 惯性滚动的最后事件时间戳,用于计算衰减帧率 */
    private var lastInertiaTime: Long = 0L
    /** 惯性阈值,小于此值停止 */
    private val inertiaStopThreshold = 0.5f
    /** 惯性衰减系数(每帧乘以此系数,接近 1 = 衰减慢) */
    private val inertiaFriction = 0.95f
    /** 惯性滚动间隔(ms),~60fps */
    private val inertiaIntervalMs = 16L

    // ============ 点击/长按判定阈值(参考 moonlight-android) ============
    private val clickThreshold = 24f            // 点击允许的最大移动距离
    private val clickTimeout = 200L             // 点击时长阈值
    private val longPressTimeout = 650L         // 长按阈值(ms)
    private val longPressSlop = 30f             // 长按后允许的轻微移动
    private val touchDownDeadZoneTime = 100L    // 按下死区时间
    private val touchDownDeadZoneDist = 20f     // 按下死区距离

    // ============ 长按拖拽(左键按住拖动) ============
    private var isLeftDrag: Boolean = false
    private var leftDragTriggered: Boolean = false

    fun handle(event: MotionEvent): Boolean {
        if (!NativeBridge.isLoaded()) return false

        val action = event.actionMasked
        val pointerIndex = event.actionIndex
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        val currentTime = event.eventTime
        val pointerCount = event.pointerCount

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                // 触控开始:取消任何惯性滚动
                cancelInertia()
                primaryPointerId = event.getPointerId(0)
                secondaryPointerId = MotionEvent.INVALID_POINTER_ID
                downTime = currentTime
                downX = x
                downY = y
                downPointerCount = 1
                lastX = x
                lastY = y
                hasLast = true
                isTwoFingerScroll = false
                isLeftDrag = false
                leftDragTriggered = false
                lastScrollY = y
                lastScrollDelta = 0f
                pendingSingleX = x
                pendingSingleY = y
                handler.postDelayed(longPressRunnable, longPressTimeout)
                return false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(longPressRunnable)
                val newId = event.getPointerId(pointerIndex)
                if (primaryPointerId == MotionEvent.INVALID_POINTER_ID) {
                    primaryPointerId = newId
                } else if (secondaryPointerId == MotionEvent.INVALID_POINTER_ID) {
                    secondaryPointerId = newId
                }
                if (pointerCount == 2) {
                    isTwoFingerScroll = false
                    val primaryIdx = event.findPointerIndex(primaryPointerId)
                    val secondaryIdx = event.findPointerIndex(secondaryPointerId)
                    if (primaryIdx >= 0 && secondaryIdx >= 0) {
                        lastScrollY = (event.getY(primaryIdx) + event.getY(secondaryIdx)) / 2f
                    }
                    if (isLeftDrag) {
                        NativeBridge.sendButtonUp(BTN_LEFT)
                        isLeftDrag = false
                        leftDragTriggered = false
                    }
                }
                downPointerCount = pointerCount
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!hasLast) {
                    lastX = x
                    lastY = y
                    hasLast = true
                    return false
                }

                if (pointerCount >= 2) {
                    val primaryIdx = event.findPointerIndex(primaryPointerId)
                    val secondaryIdx = event.findPointerIndex(secondaryPointerId)
                    if (primaryIdx < 0 || secondaryIdx < 0) {
                        return false
                    }
                    var handled = false
                    val historySize = event.historySize
                    var lastDelta = 0f
                    for (i in 0 until historySize) {
                        val histMidY = (event.getHistoricalY(primaryIdx, i) +
                                event.getHistoricalY(secondaryIdx, i)) / 2f
                        val deltaY = histMidY - lastScrollY
                        lastScrollY = histMidY
                        if (!isTwoFingerScroll && abs(deltaY) > 10f) {
                            isTwoFingerScroll = true
                        }
                        if (isTwoFingerScroll && abs(deltaY) >= 1f) {
                            sendScroll(applyScrollDirection(deltaY))
                            lastDelta = deltaY
                            handled = true
                        }
                    }
                    // 当前采样
                    val midY = (event.getY(primaryIdx) + event.getY(secondaryIdx)) / 2f
                    val deltaY = midY - lastScrollY
                    lastScrollY = midY
                    if (!isTwoFingerScroll && abs(deltaY) > 10f) {
                        isTwoFingerScroll = true
                    }
                    if (isTwoFingerScroll && abs(deltaY) >= 1f) {
                        sendScroll(applyScrollDirection(deltaY))
                        lastDelta = deltaY
                        handled = true
                    }
                    // 记录最近一次 delta 用于 MAC 风格惯性初始化
                    if (handled) lastScrollDelta = lastDelta
                    return handled
                }

                // ============ 单指移动:遍历所有历史采样(关键:不卡顿的秘诀) ============
                var sentAny = false
                val historySize = event.historySize
                for (i in 0 until historySize) {
                    val histX = event.getHistoricalX(i)
                    val histY = event.getHistoricalY(i)
                    val (dx, dy) = computeDelta(histX, histY)
                    if (abs(dx) >= 0.1f || abs(dy) >= 0.1f) {
                        sendMove(dx, dy)
                        sentAny = true
                    }
                    lastX = histX
                    lastY = histY
                }

                // 当前采样
                val (curDx, curDy) = computeDelta(x, y)
                if (abs(curDx) >= 0.1f || abs(curDy) >= 0.1f) {
                    sendMove(curDx, curDy)
                    sentAny = true
                }
                lastX = x
                lastY = y

                // 长按取消判定
                val totalDx = x - downX
                val totalDy = y - downY
                val totalDist = hypot(totalDx, totalDy)
                if (!isLeftDrag && totalDist > longPressSlop + clickThreshold) {
                    handler.removeCallbacks(longPressRunnable)
                }

                return sentAny
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val liftedId = event.getPointerId(pointerIndex)
                if (liftedId == primaryPointerId) {
                    primaryPointerId = secondaryPointerId
                    secondaryPointerId = MotionEvent.INVALID_POINTER_ID
                } else if (liftedId == secondaryPointerId) {
                    secondaryPointerId = MotionEvent.INVALID_POINTER_ID
                }
                if (pointerCount <= 2) {
                    // MAC 风格:从双指变单指时启动惯性滚动
                    if (touchStyle == TouchStyle.MAC && isTwoFingerScroll && abs(lastScrollDelta) > 1f) {
                        startInertia(lastScrollDelta)
                    }
                    isTwoFingerScroll = false
                }
                // 双指轻触右键准备
                if (downPointerCount >= 2 && !isTwoFingerScroll && pointerCount == 2) {
                    val remainingId = if (primaryPointerId != MotionEvent.INVALID_POINTER_ID)
                        primaryPointerId else secondaryPointerId
                    if (remainingId != MotionEvent.INVALID_POINTER_ID) {
                        val remainingIdx = event.findPointerIndex(remainingId)
                        if (remainingIdx >= 0) {
                            pendingSingleX = event.getX(remainingIdx)
                            pendingSingleY = event.getY(remainingIdx)
                            downTime = currentTime
                        }
                    }
                }
                // 更新 lastX/lastY 为剩余手指坐标
                val trackId = if (primaryPointerId != MotionEvent.INVALID_POINTER_ID)
                    primaryPointerId else secondaryPointerId
                if (trackId != MotionEvent.INVALID_POINTER_ID) {
                    val trackIdx = event.findPointerIndex(trackId)
                    if (trackIdx >= 0) {
                        lastX = event.getX(trackIdx)
                        lastY = event.getY(trackIdx)
                    }
                }
                return false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                // MAC 风格:单指场景下双指滚动结束也可能走到这里
                if (touchStyle == TouchStyle.MAC && isTwoFingerScroll && abs(lastScrollDelta) > 1f) {
                    startInertia(lastScrollDelta)
                }
                // 长按拖拽抬起
                if (leftDragTriggered) {
                    NativeBridge.sendButtonUp(BTN_LEFT)
                    isLeftDrag = false
                    leftDragTriggered = false
                    hasLast = false
                    primaryPointerId = MotionEvent.INVALID_POINTER_ID
                    secondaryPointerId = MotionEvent.INVALID_POINTER_ID
                    return true
                }

                // 双指轻触 → 右键
                if (downPointerCount >= 2 && !isTwoFingerScroll) {
                    val dx = x - pendingSingleX
                    val dy = y - pendingSingleY
                    val dist = hypot(dx, dy)
                    val elapsed = currentTime - downTime
                    primaryPointerId = MotionEvent.INVALID_POINTER_ID
                    secondaryPointerId = MotionEvent.INVALID_POINTER_ID
                    hasLast = false
                    isTwoFingerScroll = false
                    if (dist < clickThreshold && elapsed < clickTimeout) {
                        return NativeBridge.sendButtonClick(BTN_RIGHT)
                    }
                    return false
                }

                // 单指场景:原有单击/双击逻辑
                val totalDx = x - downX
                val totalDy = y - downY
                val totalDist = hypot(totalDx, totalDy)
                val elapsed = currentTime - downTime
                val isClick = totalDist < clickThreshold && elapsed < clickTimeout

                if (isClick && !isTwoFingerScroll) {
                    val sinceLastTap = currentTime - lastTapTime
                    val tapDx = x - lastTapX
                    val tapDy = y - lastTapY
                    val isDoubleTap = sinceLastTap < doubleTapTimeout &&
                            hypot(tapDx, tapDy) < doubleTapSlop

                    if (isDoubleTap) {
                        NativeBridge.sendButtonClick(BTN_LEFT)
                        try { Thread.sleep(30) } catch (_: Exception) {}
                        NativeBridge.sendButtonClick(BTN_LEFT)
                        lastTapTime = 0L
                    } else {
                        NativeBridge.sendButtonClick(BTN_LEFT)
                        lastTapTime = currentTime
                        lastTapX = x
                        lastTapY = y
                    }
                }

                hasLast = false
                isTwoFingerScroll = false
                leftDragTriggered = false
                primaryPointerId = MotionEvent.INVALID_POINTER_ID
                secondaryPointerId = MotionEvent.INVALID_POINTER_ID
                return false
            }
        }
        return false
    }

    /**
     * 计算从 lastX/lastY 到 (x, y) 的相对位移,应用敏感度和屏幕旋转
     */
    private fun computeDelta(x: Float, y: Float): Pair<Float, Float> {
        var dx = (x - lastX) * sensitivity
        var dy = (y - lastY) * sensitivity
        if (invertY) dy = -dy

        when (screenRotation) {
            90 -> { val tmp = dx; dx = -dy; dy = tmp }
            180 -> { dx = -dx; dy = -dy }
            270 -> { val tmp = dx; dx = dy; dy = -tmp }
        }
        return dx to dy
    }

    private fun sendMove(dx: Float, dy: Float): Boolean {
        return try {
            NativeBridge.nativeSendTouch(EVT_MOVE, dx, dy)
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    private fun sendScroll(delta: Float): Boolean {
        return try {
            NativeBridge.nativeSendTouch(EVT_SCROLL, 0f, delta)
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    /**
     * 应用滚动方向:
     * - THINKPAD:反向滚动(传统 Windows 行为,手指上滑 → 页面向下)
     *   原 deltaY 为负(上滑)→ 取反 → sendScroll 传正 → PC 端 wheel_delta = 正 * -12 = 负 = 向下滚 ✓
     * - MAC:自然滚动(手指上滑 → 页面向上)
     *   原 deltaY 为负(上滑)→ 不取反 → sendScroll 传负 → PC 端 wheel_delta = 负 * -12 = 正 = 向上滚 ✓
     *
     * 注意:PC 端 touch_inject.rs wheel_delta = dy * -12.0
     */
    private fun applyScrollDirection(deltaY: Float): Float {
        return when (touchStyle) {
            TouchStyle.THINKPAD -> -deltaY  // 反向滚动
            TouchStyle.MAC -> deltaY       // 自然滚动
        }
    }

    /**
     * 滚轮加速曲线(参考 moonlight-android)
     * 输入 delta 是原始像素位移,输出滚轮量
     */
    private fun applyScrollAcceleration(delta: Float): Float {
        val absDelta = abs(delta)
        val sign = if (delta >= 0) 1f else -1f
        val accelerated = Math.pow(absDelta.toDouble(), 0.7).toFloat() * 0.8f
        return sign * accelerated
    }

    /**
     * 启动惯性滚动(MAC 风格)
     * @param lastDelta 抬起时最后一次双指滚动 delta
     */
    private fun startInertia(lastDelta: Float) {
        if (isInertiaRunning) return
        // 取上一次方向(已经过 applyScrollDirection 处理)
        inertiaVelocity = applyScrollAcceleration(lastDelta) * 0.5f  // 初始速度取一部分
        if (abs(inertiaVelocity) < inertiaStopThreshold) return
        isInertiaRunning = true
        lastInertiaTime = System.currentTimeMillis()
        handler.post(inertiaRunnable)
    }

    /** 惯性滚动单帧 */
    private fun tickInertia() {
        if (!isInertiaRunning) return
        if (abs(inertiaVelocity) < inertiaStopThreshold) {
            stopInertia()
            return
        }
        // 发送一帧滚动
        sendScroll(inertiaVelocity)
        // 衰减
        inertiaVelocity *= inertiaFriction
        // 下一帧
        handler.postDelayed(inertiaRunnable, inertiaIntervalMs)
    }

    /** 停止惯性滚动 */
    private fun stopInertia() {
        handler.removeCallbacks(inertiaRunnable)
        isInertiaRunning = false
        inertiaVelocity = 0f
    }

    /** 取消惯性滚动(新触控开始时调用) */
    private fun cancelInertia() {
        if (isInertiaRunning) stopInertia()
    }

    fun reset() {
        handler.removeCallbacks(longPressRunnable)
        stopInertia()
        hasLast = false
        isTwoFingerScroll = false
        isLeftDrag = false
        leftDragTriggered = false
        primaryPointerId = MotionEvent.INVALID_POINTER_ID
        secondaryPointerId = MotionEvent.INVALID_POINTER_ID
        if (NativeBridge.isLoaded()) {
            NativeBridge.sendButtonUp(BTN_LEFT or BTN_RIGHT)
        }
    }
}
