package com.meowmic.client

import android.view.MotionEvent
import kotlin.math.abs

/**
 * 触摸事件处理器
 *
 * 触控板模式:相对位移,手指滑动 → 鼠标移动
 *
 * 坐标系:
 * - Android MotionEvent 原始坐标(屏幕像素)
 * - 转换为相对位移后发给 PC
 *
 * 灵敏度:
 * - 触控板像素 → 鼠标像素的映射
 * - 可配置,默认 1.0 (1:1)
 */
class TouchHandler(
    private val sensitivity: Float = 1.2f,
    private val invertY: Boolean = false,
) {
    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var hasLast: Boolean = false

    /**
     * 处理触摸事件,返回是否已消费并发送
     */
    fun handle(event: MotionEvent): Boolean {
        if (!NativeBridge.isLoaded()) return false

        val action = event.actionMasked
        val x = event.x
        val y = event.y

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = x
                lastY = y
                hasLast = true
                // 不发送位移,仅状态
                return sendTouch(0x01, 0f, 0f)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!hasLast) {
                    lastX = x
                    lastY = y
                    hasLast = true
                    return false
                }
                var dx = (x - lastX) * sensitivity
                var dy = (y - lastY) * sensitivity
                if (invertY) dy = -dy
                lastX = x
                lastY = y
                // 过滤微小抖动
                if (abs(dx) < 0.1f && abs(dy) < 0.1f) return false
                return sendTouch(0x02, dx, dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                hasLast = false
                return sendTouch(0x03, 0f, 0f)
            }
        }
        return false
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
    }
}
