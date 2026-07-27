package com.meowmic.client

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
    private val sensitivity: Float = 1.2f,
    private val invertY: Boolean = false,
) {
    var screenRotation: Int = 0

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
                return false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerCount == 2) {
                    isTwoFingerScroll = false
                    lastScrollY = event.getY(1)
                    // 取消单指拖拽
                    if (isLeftDrag) {
                        NativeBridge.sendButtonUp(BTN_LEFT)
                        isLeftDrag = false
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
                    // 双指滚动 - 也遍历历史采样
                    var handled = false
                    val historySize = event.historySize
                    for (i in 0 until historySize) {
                        val histScrollY = event.getHistoricalY(1, i)
                        val deltaY = histScrollY - lastScrollY
                        lastScrollY = histScrollY
                        if (!isTwoFingerScroll && abs(deltaY) > 2f) {
                            isTwoFingerScroll = true
                        }
                        if (isTwoFingerScroll && abs(deltaY) >= 0.5f) {
                            sendScroll(deltaY * 0.5f)
                            handled = true
                        }
                    }
                    // 当前采样
                    val scrollY = event.getY(1)
                    val deltaY = scrollY - lastScrollY
                    lastScrollY = scrollY
                    if (!isTwoFingerScroll && abs(deltaY) > 2f) {
                        isTwoFingerScroll = true
                    }
                    if (isTwoFingerScroll && abs(deltaY) >= 0.5f) {
                        sendScroll(deltaY * 0.5f)
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

                // 长按拖拽判定
                val totalDx = x - downX
                val totalDy = y - downY
                val totalDist = hypot(totalDx, totalDy)
                val elapsed = currentTime - downTime

                // 出了死区且未触发长按
                if (!isLeftDrag && !isTwoFingerScroll && elapsed > longPressTimeout && totalDist < longPressSlop + clickThreshold) {
                    NativeBridge.sendButtonDown(BTN_LEFT)
                    isLeftDrag = true
                    leftDragTriggered = true
                }

                return sentAny
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (pointerCount <= 2) {
                    isTwoFingerScroll = false
                }
                val newIndex = if (pointerIndex == 0) 1 else 0
                if (newIndex < event.pointerCount - 1) {
                    lastX = event.getX(newIndex)
                    lastY = event.getY(newIndex)
                }
                return false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val totalDx = x - downX
                val totalDy = y - downY
                val totalDist = hypot(totalDx, totalDy)
                val elapsed = currentTime - downTime
                val isClick = totalDist < clickThreshold && elapsed < clickTimeout

                // 长按拖拽抬起
                if (leftDragTriggered) {
                    NativeBridge.sendButtonUp(BTN_LEFT)
                    isLeftDrag = false
                    leftDragTriggered = false
                    hasLast = false
                    return true
                }

                if (isClick && !isTwoFingerScroll) {
                    // 双指轻触 → 右键
                    if (downPointerCount >= 2) {
                        return NativeBridge.sendButtonClick(BTN_RIGHT)
                    }

                    // 单指轻触 → 判断单击/双击
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

    fun reset() {
        hasLast = false
        isTwoFingerScroll = false
        isLeftDrag = false
        leftDragTriggered = false
        if (NativeBridge.isLoaded()) {
            NativeBridge.sendButtonUp(BTN_LEFT or BTN_RIGHT)
        }
    }
}
