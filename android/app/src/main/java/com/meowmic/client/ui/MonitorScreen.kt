package com.meowmic.client.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowmic.client.ConnectionState
import com.meowmic.client.MeowMicViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 远程显示器页面
 *
 * 数据流:
 * - 进入页面后开启画面开关 → 周期拉取 /screen/capture PNG 显示
 * - 设置浮层:缩放比例(0.25/0.5/1.0)、帧率间隔
 * - 关闭开关停止拉取
 *
 * 当前实现为低帧率(2-5fps)JPEG/PNG 轮询,后续可升级为视频流。
 */
@Composable
fun MonitorScreen(
    vm: MeowMicViewModel,
    onBack: () -> Unit,
    onDisconnect: () -> Unit = {},
    onNavigate: (String) -> Unit = {},
) {
    val connectionState by vm.connectionState.collectAsState()
    val stats by vm.stats.collectAsState()

    val addr = (connectionState as? ConnectionState.Connected)?.serverAddr ?: ""

    // 远程画面开关
    var monitorEnabled by remember { mutableStateOf(false) }
    // 缩放比例(0.25 = 1/4 分辨率,0.5 = 半分辨率,1.0 = 原始)
    var scale by remember { mutableStateOf(0.5f) }
    // 帧率间隔(ms)
    var intervalMs by remember { mutableStateOf(200L) }
    // 设置浮层开关
    var showSettings by remember { mutableStateOf(false) }
    // 连接栏展开
    var connExpanded by remember { mutableStateOf(false) }

    // 当前帧 bitmap
    var currentFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    // 屏幕分辨率(供 UI 显示)
    var screenInfo by remember { mutableStateOf<LauncherRepository.ScreenInfo?>(null) }
    // 拉取统计
    var frameCount by remember { mutableStateOf(0L) }
    var lastFetchMs by remember { mutableStateOf(0L) }
    var fetchError by remember { mutableStateOf<String?>(null) }

    // 拉取协程
    var captureJob by remember { mutableStateOf<Job?>(null) }

    val scope = rememberCoroutineScope()
    val (touchSent, audioSent) = parseStats(stats)

    // 进入页面拉取屏幕分辨率
    LaunchedEffect(addr) {
        if (addr.isNotBlank()) {
            val pk = vm.clientPubkeyB64()
            if (pk.isNotBlank()) {
                LauncherRepository.fetchScreenInfo(addr, pk)?.let { screenInfo = it }
            }
        }
    }

    // 启动/停止画面拉取
    LaunchedEffect(monitorEnabled, addr, scale, intervalMs) {
        if (!monitorEnabled || addr.isBlank()) return@LaunchedEffect
        val pk = vm.clientPubkeyB64()
        if (pk.isBlank()) return@LaunchedEffect

        while (isActive) {
            val start = System.currentTimeMillis()
            val png = LauncherRepository.fetchScreenCapture(addr, pk, quality = 70, scale = scale)
            val cost = System.currentTimeMillis() - start
            if (png != null) {
                val bmp = BitmapFactory.decodeByteArray(png, 0, png.size)
                if (bmp != null) {
                    currentFrame = bmp.asImageBitmap()
                    frameCount++
                    lastFetchMs = cost
                    fetchError = null
                }
            } else {
                fetchError = "拉取失败"
            }
            // 间隔(减去本次耗时,保证目标帧率)
            val wait = (intervalMs - cost).coerceAtLeast(50L)
            delay(wait)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 1. 可折叠连接栏
            ConnBar(
                addr = addr,
                expanded = connExpanded,
                onToggle = { connExpanded = !connExpanded },
                touchSent = touchSent,
                audioSent = audioSent,
                landscape = false,
            )

            // 2. 顶部操作栏:页面切换组 + 远程画面开关 + 设置 + 断开
            MonitorActionBar(
                monitorEnabled = monitorEnabled,
                onToggleMonitor = { monitorEnabled = !monitorEnabled },
                onShowSettings = { showSettings = true },
                onBack = onBack,
                onNavigate = onNavigate,
                onDisconnect = onDisconnect,
            )

            // 3. 视频显示区(占满剩余空间)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = currentFrame
                if (monitorEnabled && bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = "远程画面",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else if (monitorEnabled) {
                    // 已开启但未拉到首帧
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                        Text(
                            fetchError ?: "正在连接远程画面...",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                        )
                    }
                } else {
                    // 未开启:空状态占位(对齐设计稿 view-monitor 空状态)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Default.Monitor,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                        )
                        Text(
                            "远程画面未开启",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                        Text(
                            "点击右上角摄像头开关即可实时查看电脑桌面",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            // 4. 底部状态栏:分辨率 / 帧数 / 延迟
            MonitorStatusBar(
                screenInfo = screenInfo,
                scale = scale,
                frameCount = frameCount,
                lastFetchMs = lastFetchMs,
                fetchError = fetchError,
            )
        }
    }

    // 设置浮层
    if (showSettings) {
        MonitorSettingsDialog(
            scale = scale,
            onScaleChange = { scale = it },
            intervalMs = intervalMs,
            onIntervalChange = { intervalMs = it },
            onDismiss = { showSettings = false },
        )
    }
}

/** 远程显示器顶部操作栏:页面切换组 + 远程画面开关 + 设置 + 断开 */
@Composable
private fun MonitorActionBar(
    monitorEnabled: Boolean,
    onToggleMonitor: () -> Unit,
    onShowSettings: () -> Unit,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    val btnSize = 24.dp
    val icSize = 14.dp
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧:页面切换组(激活 = monitor)
        PageSwitcher(
            currentView = "monitor",
            onBack = onBack,
            onNavigate = onNavigate,
            btnSize = btnSize,
            iconSize = icSize,
        )

        Spacer(Modifier.weight(1f))

        // 右侧上下文:远程画面开关 + 设置
        ToggleButtonSmall(
            icon = if (monitorEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
            contentDescription = if (monitorEnabled) "关闭远程画面" else "开启远程画面",
            isOn = monitorEnabled,
            onClick = onToggleMonitor,
            buttonSize = btnSize,
            iconSize = icSize,
        )
        IconButtonSmall(
            icon = Icons.Default.Settings,
            contentDescription = "画面设置",
            onClick = onShowSettings,
            buttonSize = btnSize,
            iconSize = icSize,
        )
        ActionBarDivider()
        IconButtonSmall(
            icon = Icons.Default.Logout,
            contentDescription = "断开连接",
            onClick = onDisconnect,
            isDanger = true,
            buttonSize = btnSize,
            iconSize = icSize,
        )
    }
}

/** 底部状态栏:分辨率 / 帧数 / 延迟 */
@Composable
private fun MonitorStatusBar(
    screenInfo: LauncherRepository.ScreenInfo?,
    scale: Float,
    frameCount: Long,
    lastFetchMs: Long,
    fetchError: String?,
) {
    val fps = if (lastFetchMs > 0) (1000.0 / lastFetchMs).toInt() else 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusChip(
            icon = Icons.Default.Monitor,
            label = if (screenInfo != null) "${screenInfo.width}×${screenInfo.height}" else "—",
        )
        StatusChip(
            icon = Icons.Default.AspectRatio,
            label = "缩放 ${(scale * 100).toInt()}%",
        )
        StatusChip(
            icon = Icons.Default.Image,
            label = "帧 $frameCount",
        )
        StatusChip(
            icon = Icons.Default.Speed,
            label = "${lastFetchMs}ms",
        )
        if (fetchError != null) {
            Text(
                fetchError,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun StatusChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(11.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 画面设置浮层:缩放比例 + 帧率间隔 */
@Composable
private fun MonitorSettingsDialog(
    scale: Float,
    onScaleChange: (Float) -> Unit,
    intervalMs: Long,
    onIntervalChange: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("画面设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("缩放比例", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.25f to "25%", 0.5f to "50%", 1.0f to "100%").forEach { (v, label) ->
                        FilterChip(
                            selected = scale == v,
                            onClick = { onScaleChange(v) },
                            label = { Text(label, fontSize = 11.sp) },
                        )
                    }
                }

                Text("刷新间隔", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(100L to "10fps", 200L to "5fps", 500L to "2fps").forEach { (ms, label) ->
                        FilterChip(
                            selected = intervalMs == ms,
                            onClick = { onIntervalChange(ms) },
                            label = { Text(label, fontSize = 11.sp) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}
