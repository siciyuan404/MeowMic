package com.meowmic.client

import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 触摸事件处理器(参考 moonlight-android 的触控板逻辑)
 *
 * 手势支持:
 * - 单指滑动:鼠标移动
 * - 单指轻触:鼠标左键单击
 * - 单指双击:鼠标左键双击
 * - 双指滑动:鼠标滚轮滚动(纵向)
 * - 双指轻触:鼠标右键单击
 * - 三指滑动:鼠标中键(暂未实现,预留)
 *
 * 事件类型常量(与 protocol::TouchEventType 对应):
 * - 0x01=Down 0x02=Move 0x03=Up 0x04=Button 0x05=Scroll
 *
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
    private val BTN_MIDDLE = 0x04

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
    private val doubleTapSlop = 40f

    // ============ 双指滚动状态 ============
    private var lastScrollY: Float = 0f
    private var isTwoFingerScroll: Boolean = false

    // ============ 点击判定阈值 ============
    private val clickThreshold = 24f      // 移动距离阈值(像素)
    private val clickTimeout = 200L       // 点击时长阈值(毫秒)

    // ============ 长按拖拽(左键按住拖动) ============
    private var isLeftDrag: Boolean = false
    private val longPressTimeout = 400L   // 长按阈值
    private val longPressSlop = 16f       // 长按后允许的轻微移动

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
                lastScrollY = y
                return false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 第二根手指按下,进入双指模式
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
                    // 双指滚动
                    val scrollY = event.getY(1)
                    val deltaY = scrollY - lastScrollY
                    lastScrollY = scrollY

                    if (!isTwoFingerScroll && abs(deltaY) > 2f) {
                        isTwoFingerScroll = true
                    }

                    if (isTwoFingerScroll && abs(deltaY) >= 0.5f) {
                        // 滚动:向下拖动 → 页面向上滚(正值)
                        return sendScroll(deltaY * 0.5f)
                    }
                    return false
                }

                // 单指移动
                var dx = (x - lastX) * sensitivity
                var dy = (y - lastY) * sensitivity
                if (invertY) dy = -dy

                // 屏幕旋转适配
                when (screenRotation) {
                    90 -> { val tmp = dx; dx = -dy; dy = tmp }
                    180 -> { dx = -dx; dy = -dy }
                    270 -> { val tmp = dx; dx = dy; dy = -tmp }
                }

                lastX = x
                lastY = y

                // 长按拖拽判定:按下后超过 longPressTimeout 且移动很小,触发左键按住
                val totalDx = x - downX
                val totalDy = y - downY
                val totalDist = hypot(totalDx, totalDy)
                val elapsed = currentTime - downTime

                if (!isLeftDrag && !isTwoFingerScroll && elapsed > longPressTimeout && totalDist < longPressSlop + clickThreshold) {
                    // 触发左键按住(拖拽模式)
                    NativeBridge.sendButtonDown(BTN_LEFT)
                    isLeftDrag = true
                }

                if (abs(dx) < 0.1f && abs(dy) < 0.1f) return false
                return sendMove(dx, dy)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 双指中一根抬起
                if (pointerCount <= 2) {
                    // 退出双指模式
                    isTwoFingerScroll = false
                }
                // 更新 lastX/lastY 为剩余手指位置
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

                // 如果之前处于左键拖拽状态,抬起左键
                if (isLeftDrag) {
                    NativeBridge.sendButtonUp(BTN_LEFT)
                    isLeftDrag = false
                    hasLast = false
                    return true
                }

                if (isClick && !isTwoFingerScroll) {
                    // 判断双指轻触 → 右键
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
                        // 双击 → 双击左键
                        NativeBridge.sendButtonClick(BTN_LEFT)
                        NativeBridge.sendButtonClick(BTN_LEFT)
                        lastTapTime = 0L  // 重置,避免三击被识别为双击
                    } else {
                        // 单击 → 左键单击
                        NativeBridge.sendButtonClick(BTN_LEFT)
                        lastTapTime = currentTime
                        lastTapX = x
                        lastTapY = y
                    }
                }

                hasLast = false
                isTwoFingerScroll = false
                return false
            }
        }
        return false
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
        if (NativeBridge.isLoaded()) {
            // 确保抬起所有可能按下的键
            NativeBridge.sendButtonUp(BTN_LEFT or BTN_RIGHT or BTN_MIDDLE)
        }
    }
}
