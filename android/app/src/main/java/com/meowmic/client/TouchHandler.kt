package com.meowmic.client

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot

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
 * 事件类型常量: 0x02=Move 0x04=Button 0x05=Scroll
 * 按钮掩码位: bit0=左键 bit1=右键 bit2=中键
 */
class TouchHandler(
    private val sensitivity: Float = 1.0f,
    private val invertY: Boolean = false,
) {
    var screenRotation: Int = 0

    // ============ Handler(主线程,用于定时触发长按) ============
    // 参考 moonlight-android:长按不依赖 ACTION_MOVE(手指静止时不触发),
    // 改用 postDelayed 在 longPressTimeout 后强制触发左键按下。
    private val handler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (!isLeftDrag && !isTwoFingerScroll && downPointerCount == 1) {
            NativeBridge.sendButtonDown(BTN_LEFT)
            isLeftDrag = true
            leftDragTriggered = true
        }
    }

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
    // Android 在某指抬起时 pointerIndex 会重排,必须用 pointerId 稳定跟踪。
    private var primaryPointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var secondaryPointerId: Int = MotionEvent.INVALID_POINTER_ID
    // 双指轻触右键准备:POINTER_UP 时记录剩余指坐标,UP 时用它与抬指坐标算距离
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
                pendingSingleX = x
                pendingSingleY = y
                // 长按由 Runnable 定时触发(手指静止也能在 longPressTimeout 后触发拖拽)
                handler.postDelayed(longPressRunnable, longPressTimeout)
                return false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 双指介入立即取消未触发的长按(避免双指操作时误触发左键拖拽)
                handler.removeCallbacks(longPressRunnable)
                // Bug A:用 pointerId 跟踪新落下的手指
                val newId = event.getPointerId(pointerIndex)
                if (primaryPointerId == MotionEvent.INVALID_POINTER_ID) {
                    primaryPointerId = newId
                } else if (secondaryPointerId == MotionEvent.INVALID_POINTER_ID) {
                    secondaryPointerId = newId
                }
                if (pointerCount == 2) {
                    isTwoFingerScroll = false
                    // Bug B:滚动参考点改为两指中点 Y
                    val primaryIdx = event.findPointerIndex(primaryPointerId)
                    val secondaryIdx = event.findPointerIndex(secondaryPointerId)
                    if (primaryIdx >= 0 && secondaryIdx >= 0) {
                        lastScrollY = (event.getY(primaryIdx) + event.getY(secondaryIdx)) / 2f
                    }
                    // Bug D:取消单指拖拽时,同时清 leftDragTriggered,
                    // 否则 ACTION_UP 会再发一次 button up
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
                    // Bug A + B:双指滚动,用 findPointerIndex 取两指坐标,中点 Y 作参考
                    val primaryIdx = event.findPointerIndex(primaryPointerId)
                    val secondaryIdx = event.findPointerIndex(secondaryPointerId)
                    if (primaryIdx < 0 || secondaryIdx < 0) {
                        return false
                    }
                    var handled = false
                    val historySize = event.historySize
                    for (i in 0 until historySize) {
                        val histMidY = (event.getHistoricalY(primaryIdx, i) +
                                event.getHistoricalY(secondaryIdx, i)) / 2f
                        val deltaY = histMidY - lastScrollY
                        lastScrollY = histMidY
                        // Bug B:触发阈值 10f(参考 Moonlight TWO_FINGER_SCROLL_DEAD_ZONE)
                        if (!isTwoFingerScroll && abs(deltaY) > 10f) {
                            isTwoFingerScroll = true
                        }
                        // Bug B:已触发后最小 delta 1f(不再用 0.5f)
                        if (isTwoFingerScroll && abs(deltaY) >= 1f) {
                            sendScroll(applyScrollAcceleration(deltaY))
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
                        sendScroll(applyScrollAcceleration(deltaY))
                        handled = true
                    }
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

                // 长按取消判定:手指挪出 slop 范围则取消未触发的长按 Runnable
                // (长按触发完全由 longPressRunnable 驱动,不依赖 ACTION_MOVE)
                val totalDx = x - downX
                val totalDy = y - downY
                val totalDist = hypot(totalDx, totalDy)
                if (!isLeftDrag && totalDist > longPressSlop + clickThreshold) {
                    handler.removeCallbacks(longPressRunnable)
                }

                return sentAny
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Bug A:用 pointerId 判定哪根手指抬起,清空对应 id
                val liftedId = event.getPointerId(pointerIndex)
                if (liftedId == primaryPointerId) {
                    // 主指抬起:副指升为主指
                    primaryPointerId = secondaryPointerId
                    secondaryPointerId = MotionEvent.INVALID_POINTER_ID
                } else if (liftedId == secondaryPointerId) {
                    secondaryPointerId = MotionEvent.INVALID_POINTER_ID
                }
                if (pointerCount <= 2) {
                    isTwoFingerScroll = false
                }
                // Bug C:双指场景下抬起一指(pointerCount==2 表示从 2 指变 1 指),
                // 未触发滚动时,记录剩余指当前坐标 + 更新 downTime,准备双指轻触右键判定
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
                // 更新 lastX/lastY 为剩余手指坐标,避免后续单指移动跳变
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
                // 抬起/取消时清理未触发的长按 Runnable
                handler.removeCallbacks(longPressRunnable)
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

                // Bug C:双指轻触 → 右键
                // 距离用 pendingSingleX/Y(POINTER_UP 时剩余指坐标)到当前 x/y(抬指坐标),
                // 时长用 currentTime - downTime(downTime 在 POINTER_UP 时已更新)
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

                // ============ 单指场景:原有单击/双击逻辑(不动) ============
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
     * 滚轮加速曲线(参考 moonlight-android)
     * - 慢速(小 delta):放大,便于精确滚动
     * - 快速(大 delta):抑制,防止过冲
     * 输入 delta 是原始像素位移,输出滚轮量
     *
     * 方向约定(与 PC 端 touch_inject.rs:67 wheel_delta = dy * -12.0 配合):
     * - 双指上滑 → 中点 Y 减小 → deltaY 为负 → sendScroll 传负值
     *   → PC 端 wheel_delta = 负 * -12 = 正 = 向上滚(页面向上滚)✓
     * - 双指下滑 → deltaY 为正 → wheel_delta 为负 = 向下滚(页面向下滚)✓
     * 故本函数保留 sign,不取反。
     */
    private fun applyScrollAcceleration(delta: Float): Float {
        val absDelta = abs(delta)
        val sign = if (delta >= 0) 1f else -1f
        // 指数曲线: pow(absDelta, 0.7) * scale
        // 小 delta(1px): 1^0.7 * 0.8 = 0.8(轻微放大)
        // 中 delta(10px): 10^0.7 * 0.8 ≈ 4.0(适中)
        // 大 delta(50px): 50^0.7 * 0.8 ≈ 13.3(抑制)
        val accelerated = Math.pow(absDelta.toDouble(), 0.7).toFloat() * 0.8f
        return sign * accelerated
    }

    fun reset() {
        handler.removeCallbacks(longPressRunnable)
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
