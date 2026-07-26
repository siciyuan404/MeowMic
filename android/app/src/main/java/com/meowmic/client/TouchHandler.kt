package com.meowmic.client

import android.view.MotionEvent
import kotlin.math.abs

/**
 * 触摸事件处理器
 *
 * 触控板模式:相对位移,手指滑动 → 鼠标移动
 *
 * 手势支持:
 * - 单指滑动:鼠标移动
 * - 单指轻触:鼠标左键单击
 * - 双指滑动:鼠标滚轮滚动(纵向)
 * - 双指轻触:鼠标右键单击
 */
class TouchHandler(
    private val sensitivity: Float = 1.2f,
    private val invertY: Boolean = false,
) {
    var screenRotation: Int = 0

    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var hasLast: Boolean = false
    private var downTime: Long = 0L
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var fingerCount: Int = 0
    private var isTwoFingerScroll: Boolean = false
    private var lastScrollY: Float = 0f

    private val clickThreshold = 20f
    private val clickTimeout = 300L

    fun handle(event: MotionEvent): Boolean {
        if (!NativeBridge.isLoaded()) return false

        val action = event.actionMasked
        val x = event.getX(event.actionIndex)
        val y = event.getY(event.actionIndex)
        val currentTime = event.eventTime

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                fingerCount = event.pointerCount
                lastX = x
                lastY = y
                downX = x
                downY = y
                downTime = currentTime
                hasLast = true
                isTwoFingerScroll = false
                return sendTouch(0x01, 0f, 0f)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!hasLast) {
                    lastX = x
                    lastY = y
                    hasLast = true
                    return false
                }

                fingerCount = event.pointerCount

                if (fingerCount >= 2) {
                    val scrollY = event.getY(1)
                    val deltaY = scrollY - lastScrollY
                    lastScrollY = scrollY

                    if (!isTwoFingerScroll && abs(deltaY) > 2f) {
                        isTwoFingerScroll = true
                    }

                    if (isTwoFingerScroll) {
                        if (abs(deltaY) < 0.5f) return false
                        return sendScroll(deltaY * 0.5f)
                    }
                } else {
                    lastScrollY = y
                }

                var dx = (x - lastX) * sensitivity
                var dy = (y - lastY) * sensitivity
                if (invertY) dy = -dy

                when (screenRotation) {
                    90 -> { val tmp = dx; dx = -dy; dy = tmp }
                    180 -> { dx = -dx; dy = -dy }
                    270 -> { val tmp = dx; dx = dy; dy = -tmp }
                }

                lastX = x
                lastY = y
                if (abs(dx) < 0.1f && abs(dy) < 0.1f) return false
                return sendTouch(0x02, dx, dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                fingerCount = event.pointerCount - 1

                val totalDx = x - downX
                val totalDy = y - downY
                val totalDistance = kotlin.math.sqrt(totalDx * totalDx + totalDy * totalDy)
                val isClick = totalDistance < clickThreshold && (currentTime - downTime) < clickTimeout

                if (isClick && !isTwoFingerScroll) {
                    return if (fingerCount >= 2) {
                        sendButtonClick(0x02)
                    } else {
                        sendButtonClick(0x01)
                    }
                }

                hasLast = false
                fingerCount = 0
                isTwoFingerScroll = false
                return sendTouch(0x03, 0f, 0f)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                fingerCount = event.pointerCount - 1
                val pointerIndex = event.actionIndex
                if (pointerIndex == 0) {
                    val newIndex = if (fingerCount > 0) 1 else 0
                    lastX = event.getX(newIndex)
                    lastY = event.getY(newIndex)
                }
                return false
            }
        }
        return false
    }

    private fun sendScroll(delta: Float): Boolean {
        return try {
            NativeBridge.nativeSendTouch(0x05, 0f, delta)
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    private fun sendButtonClick(button: Int): Boolean {
        return try {
            NativeBridge.nativeSendTouch(button, 0f, 0f)
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    private fun sendTouch(eventType: Int, dx: Float, dy: Float): Boolean {
        return try {
            NativeBridge.nativeSendTouch(eventType, dx, dy)
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    fun reset() {
        hasLast = false
        fingerCount = 0
        isTwoFingerScroll = false
    }
}
