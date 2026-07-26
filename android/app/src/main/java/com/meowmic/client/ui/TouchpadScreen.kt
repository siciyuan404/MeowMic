package com.meowmic.client.ui

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowmic.client.ConnectionState
import com.meowmic.client.MeowMicViewModel
import org.json.JSONObject

/**
 * 触控板主页面
 *
 * 布局:
 * - 顶部状态栏(连接地址 + 统计)
 * - 中间大面积触摸区
 * - 底部左右键 + 断开按钮
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TouchpadScreen(
    vm: MeowMicViewModel,
    onDisconnect: () -> Unit,
) {
    val connectionState by vm.connectionState.collectAsState()
    val stats by vm.stats.collectAsState()

    val connected = connectionState as? ConnectionState.Connected
    val serverAddr = connected?.serverAddr ?: "未知"

    // 解析统计 JSON
    var touchSent = 0L
    var audioSent = 0L
    try {
        val json = JSONObject(stats)
        touchSent = json.optLong("touch_sent", 0)
        audioSent = json.optLong("audio_sent", 0)
    } catch (_: Exception) { }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        // 顶部状态栏
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Mouse,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        "已连接",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                    Text(
                        serverAddr,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text("Touch: $touchSent", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Audio: $audioSent", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 中间触摸区
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                )
                .pointerInteropFilter { event ->
                    // 复制 MotionEvent 给 ViewModel 处理(注意:Compose 中拿到的 MotionEvent 来自 pointerInteropFilter)
                    val motionEvent = event as? MotionEvent
                    if (motionEvent != null) {
                        vm.handleTouch(motionEvent)
                    }
                    true
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Default.Mouse,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.size(80.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "滑动此处移动鼠标",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 底部按钮:左键 / 右键 / 断开
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { vm.handleTouch(fakeDownEvent()) },
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("左键")
            }
            Button(
                onClick = { vm.handleTouch(fakeUpEvent()) },
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("右键")
            }
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("断开")
            }
        }
    }
}

// 临时模拟事件用于按键(P0 简化,后续接入真正的按钮状态协议)
private fun fakeDownEvent(): MotionEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
private fun fakeUpEvent(): MotionEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0f, 0f, 0)
