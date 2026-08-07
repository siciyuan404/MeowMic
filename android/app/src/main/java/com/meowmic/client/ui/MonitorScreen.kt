package com.meowmic.client.ui

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.meowmic.client.ConnectionState
import com.meowmic.client.LauncherRepository
import com.meowmic.client.MeowMicViewModel
import com.meowmic.client.NativeBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 远程显示器页面
 *
 * UDP push 模式(借鉴 Sunshine 架构):
 * - PC 服务端持续采集 DXGI → H.264 硬件编码 → UDP 分片推送
 * - Android 端 Rust 核心接收 UDP 分片 → 重组 → MediaCodec 硬解 → Surface 直渲
 * - SurfaceView 零拷贝:解码输出直接渲染到屏幕,不经 CPU
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
    // 目标帧率(也作为轮询间隔依据)
    var frameRate by remember { mutableStateOf(30) }
    // 设置浮层开关
    var showSettings by remember { mutableStateOf(false) }
    // 连接栏展开
    var connExpanded by remember { mutableStateOf(false) }

    // 当前帧是否已收到首帧(用于 UI 状态切换)
    var hasFirstFrame by remember { mutableStateOf(false) }
    // 屏幕分辨率(供 UI 显示)
    var screenInfo by remember { mutableStateOf<LauncherRepository.ScreenInfo?>(null) }
    // 拉取统计
    var frameCount by remember { mutableStateOf(0L) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var decoderStatus by remember { mutableStateOf<String?>(null) }

    // MediaCodec 解码器实例
    var mediaCodec by remember { mutableStateOf<MediaCodec?>(null) }
    // Surface 引用(SurfaceView 就绪后传入 MediaCodec)
    var videoSurface by remember { mutableStateOf<Surface?>(null) }

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

    // 创建 / 销毁 MediaCodec 解码器(随 Surface 生命周期)
    LaunchedEffect(videoSurface, monitorEnabled) {
        if (!monitorEnabled || videoSurface == null) {
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
            hasFirstFrame = false
            decoderStatus = null
            return@LaunchedEffect
        }

        val w = screenInfo?.width ?: 1920
        val h = screenInfo?.height ?: 1080

        decoderStatus = "初始化解码器 ${w}x${h}..."
        try {
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, w * h * 3 / 2)
            }
            codec.configure(format, videoSurface, null, 0)
            codec.start()
            mediaCodec = codec
            decoderStatus = "解码器就绪"
            Log.i(TAG_DEC, "H.264 解码器启动(Surface 直渲) ${w}x${h}")
        } catch (e: Exception) {
            Log.e(TAG_DEC, "解码器启动失败: ${e.message}")
            decoderStatus = "解码器失败: ${e.message}"
        }
    }

    // 启动 / 停止视频推流 + NALU 拉取循环
    LaunchedEffect(mediaCodec, addr, monitorEnabled) {
        val codec = mediaCodec ?: return@LaunchedEffect
        if (addr.isBlank() || !monitorEnabled) return@LaunchedEffect

        val w = screenInfo?.width ?: 1920
        val h = screenInfo?.height ?: 1080
        val fps = frameRate
        val bitrate = 4_000_000

        // 请求服务端开始推流
        if (!NativeBridge.nativeStartVideo(w, h, fps, bitrate)) {
            fetchError = "请求视频推流失败"
            return@LaunchedEffect
        }

        // NALU 拉取循环:从 Rust 队列取完整 NALU → 喂入 MediaCodec
        while (isActive) {
            val nalu = NativeBridge.nativePollVideoFrame()
            if (nalu != null && nalu.isNotEmpty()) {
                try {
                    val idx = codec.dequeueInputBuffer(5_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx) ?: continue
                        buf.clear()
                        if (nalu.size <= buf.capacity()) {
                            buf.put(nalu)
                            codec.queueInputBuffer(idx, 0, nalu.size, System.nanoTime() / 1000, 0)
                        }
                    }
                    // drain output (Surface 自动渲染,无需手动操作)
                    val info = MediaCodec.BufferInfo()
                    while (true) {
                        val outIdx = codec.dequeueOutputBuffer(info, 0)
                        when {
                            outIdx >= 0 -> {
                                codec.releaseOutputBuffer(outIdx, true)
                                if (!hasFirstFrame) {
                                    hasFirstFrame = true
                                    decoderStatus = null
                                }
                                frameCount++
                            }
                            outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                Log.i(TAG_DEC, "输出格式变化: ${codec.outputFormat}")
                            }
                            else -> break
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG_DEC, "解码 feed 失败: ${e.message}")
                }
            } else {
                delay(2) // 无帧时短暂等待,避免 CPU 空转
            }
        }
    }

    // 视频统计上报:每 1 秒取一次快照 → 上报给服务端做自适应码率
    // 视频未启动时 nativePollVideoStats 返回全 0,nativeSendVideoStats 内部也会判空,无副作用
    LaunchedEffect(monitorEnabled, addr) {
        if (!monitorEnabled || addr.isBlank()) return@LaunchedEffect
        while (isActive) {
            delay(1_000)
            val json = NativeBridge.nativePollVideoStats()
            // 简易 JSON 解析(避免引入 org.json 依赖)
            // JSON 格式: {"received":N,"lost":N,"recovered":N}
            val received = Regex("""received"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val lost = Regex("""lost"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val recovered = Regex("""recovered"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            // 三个字段全 0 时跳过上报(无活动)
            if (received == 0 && lost == 0 && recovered == 0) continue
            try {
                NativeBridge.nativeSendVideoStats(received, lost, recovered, 0)
            } catch (e: Exception) {
                Log.w(TAG_DEC, "上报视频统计失败: ${e.message}")
            }
        }
    }

    // 退出页面释放
    DisposableEffect(Unit) {
        onDispose {
            NativeBridge.nativeStopVideo()
            mediaCodec?.stop()
            mediaCodec?.release()
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

            // 3. 视频显示区(SurfaceView 零拷贝直渲)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (monitorEnabled) {
                    // SurfaceView: MediaCodec 解码输出直接渲染到 Surface(GPU 零拷贝)
                    AndroidView(
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        videoSurface = holder.surface
                                    }
                                    override fun surfaceChanged(
                                        holder: SurfaceHolder,
                                        format: Int,
                                        width: Int,
                                        height: Int,
                                    ) {}
                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        videoSurface = null
                                    }
                                })
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // 未收到首帧时显示 loading 覆盖层
                    if (!hasFirstFrame) {
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
                                decoderStatus ?: fetchError ?: "正在连接远程画面...",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 11.sp,
                            )
                        }
                    }
                } else {
                    // 未开启:空状态占位
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

            // 4. 底部状态栏:分辨率 / 帧数 (UDP push 模式无拉取延迟)
            MonitorStatusBar(
                screenInfo = screenInfo,
                frameRate = frameRate,
                frameCount = frameCount,
                fetchError = fetchError,
            )
        }
    }

    // 设置浮层
    if (showSettings) {
        MonitorSettingsDialog(
            frameRate = frameRate,
            onFrameRateChange = { frameRate = it },
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

/** 底部状态栏:分辨率 / 帧数 (UDP push 模式) */
@Composable
private fun MonitorStatusBar(
    screenInfo: LauncherRepository.ScreenInfo?,
    frameRate: Int,
    frameCount: Long,
    fetchError: String?,
) {
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
            icon = Icons.Default.Speed,
            label = "目标 ${frameRate}fps",
        )
        StatusChip(
            icon = Icons.Default.Image,
            label = "帧 $frameCount",
        )
        StatusChip(
            icon = Icons.Default.Bolt,
            label = "UDP",
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

/** 画面设置浮层:帧率选择 */
@Composable
private fun MonitorSettingsDialog(
    frameRate: Int,
    onFrameRateChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("画面设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("目标帧率", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15 to "15fps", 30 to "30fps", 60 to "60fps").forEach { (v, label) ->
                        FilterChip(
                            selected = frameRate == v,
                            onClick = { onFrameRateChange(v) },
                            label = { Text(label, fontSize = 11.sp) },
                        )
                    }
                }
                Text(
                    "提示:码率固定 4Mbps;修改帧率后立即生效,无需重新连接。",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

// ════════════════════════════════════════════════════════════════
// H.264 硬解码器封装
// ════════════════════════════════════════════════════════════════

private const val TAG_DEC = "H264Decoder"

/**
 * H.264 硬解码器(MediaCodec + ImageReader)
 *
 * 流程:
 * - 创建 AVC decoder,输出绑定到 ImageReader(YUV_420_888) 的 Surface
 * - [feed] 把 Annex-B NALU 字节流喂入 decoder 输入队列
 * - ImageReader 回调 acquireLatestImage → 转 Bitmap → onFrame 回调
 *
 * @param onFrame 解码出一帧时回调(在 ImageReader 监听线程触发);bmp=null 表示跳过此帧
 */
private class H264Decoder(
    width: Int,
    height: Int,
    private val onFrame: (Bitmap?) -> Unit,
) {
    private val codec: MediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val mainHandler = Handler(Looper.getMainLooper())
    // ImageReader 回调放在后台 HandlerThread,避免 JPEG 编解码阻塞 UI 主线程
    private val handlerThread = HandlerThread("H264DecoderCallback").apply { start() }
    private val callbackHandler = Handler(handlerThread.looper)
    private val imageReader: ImageReader =
        ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3)
    @Volatile private var released = false

    init {
        imageReader.setOnImageAvailableListener({ reader ->
            if (released) return@setOnImageAvailableListener
            val image = try {
                reader.acquireLatestImage()
            } catch (e: Exception) {
                Log.w(TAG_DEC, "acquireLatestImage 失败: ${e.message}")
                null
            } ?: return@setOnImageAvailableListener
            try {
                val bmp = imageToBitmap(image)
                if (bmp != null) {
                    // 回调切到主线程(Compose state 必须主线程更新)
                    mainHandler.post { onFrame(bmp) }
                }
            } catch (e: Exception) {
                Log.w(TAG_DEC, "imageToBitmap 失败: ${e.message}")
            } finally {
                image.close()
            }
        }, callbackHandler)

        // 配置 MediaCodec:width/height 仅作 hint,实际以 SPS/PPS 为准
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height * 3 / 2)
        }
        try {
            codec.configure(format, imageReader.surface, null, 0)
            codec.start()
            Log.i(TAG_DEC, "H.264 解码器启动 ${width}x${height}")
        } catch (e: Exception) {
            Log.e(TAG_DEC, "解码器启动失败: ${e.message}")
            throw e
        }
    }

    /**
     * 喂入一帧 Annex-B NALU 字节流(可能含 SPS/PPS/IDR 或 P 帧,可一次多 NALU)。
     * @return true 成功入队;false 输入队列满,调用方应丢帧
     */
    fun feed(nalu: ByteArray): Boolean {
        if (released) return false
        return try {
            val idx = codec.dequeueInputBuffer(5_000) // 5ms 等待
            if (idx < 0) return false
            val buf = codec.getInputBuffer(idx) ?: return false
            buf.clear()
            if (nalu.size > buf.capacity()) {
                // 超出 buffer,截断(不应发生,但兜底)
                Log.w(TAG_DEC, "NALU 超大 ${nalu.size} > ${buf.capacity()},截断")
                buf.put(nalu, 0, buf.capacity())
                codec.queueInputBuffer(idx, 0, buf.capacity(), System.nanoTime() / 1000, 0)
            } else {
                buf.put(nalu)
                codec.queueInputBuffer(idx, 0, nalu.size, System.nanoTime() / 1000, 0)
            }
            // 顺带 drain 输出(避免输出队列堆积)
            drainOutput()
            true
        } catch (e: Exception) {
            Log.w(TAG_DEC, "feed 失败: ${e.message}")
            false
        }
    }

    /** 尝试取输出缓冲,渲染到 ImageReader 的 Surface */
    private fun drainOutput() {
        if (released) return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, 0) // 非阻塞
            when {
                idx >= 0 -> {
                    // true = 渲染到 Surface(ImageReader)
                    codec.releaseOutputBuffer(idx, true)
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    Log.i(TAG_DEC, "输出格式变化: $newFormat")
                }
                else -> break // INFO_TRY_AGAIN_LATER 或无可用 buffer
            }
        }
    }

    /** 释放解码器资源 */
    fun release() {
        if (released) return
        released = true
        try {
            codec.stop()
        } catch (_: Exception) {
            // stop 可能抛 IllegalState,忽略
        }
        try {
            codec.release()
        } catch (_: Exception) {}
        try {
            imageReader.close()
        } catch (_: Exception) {}
        try {
            handlerThread.quitSafely()
        } catch (_: Exception) {}
        Log.i(TAG_DEC, "H.264 解码器释放")
    }
}

/**
 * Image(YUV_420_888) → Bitmap 转换
 *
 * 实现步骤:
 * 1. 按 planes[0/1/2] 提取 Y / U / V 字节,处理 rowStride / pixelStride
 * 2. 拼成 NV21(VUVU...交替)
 * 3. YuvImage + JPEG 压缩 → BitmapFactory 解码为 Bitmap
 *
 * 注:用 JPEG 中转比手写 per-pixel YUV→RGB 快几十倍(YuvImage 内部是 native)。
 */
private fun imageToBitmap(image: Image): Bitmap? {
    if (image.format != ImageFormat.YUV_420_888) return null
    val w = image.width
    val h = image.height

    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]

    val yRowStride = yPlane.rowStride
    val uvRowStride = uPlane.rowStride
    val uvPixelStride = uPlane.pixelStride

    val yBuf = yPlane.buffer
    val uBuf = uPlane.buffer
    val vBuf = vPlane.buffer

    val ySize = w * h
    val chromaW = (w + 1) / 2
    val chromaH = (h + 1) / 2
    val chromaSize = chromaW * chromaH
    // NV21: Y(w*h) + VU interleave(w*h/2)
    val nv21 = ByteArray(ySize + chromaSize * 2)

    // 1. Copy Y(处理 rowStride)
    if (yRowStride == w) {
        yBuf.get(nv21, 0, ySize)
    } else {
        var dstPos = 0
        for (row in 0 until h) {
            yBuf.position(row * yRowStride)
            yBuf.get(nv21, dstPos, w)
            dstPos += w
        }
    }

    // 2. Interleave VU(处理 rowStride / pixelStride)
    if (uvPixelStride == 1) {
        // Planar 布局:U 和 V 各自连续
        val u = ByteArray(chromaSize)
        val v = ByteArray(chromaSize)
        if (uvRowStride == chromaW) {
            uBuf.get(u)
            vBuf.get(v)
        } else {
            for (row in 0 until chromaH) {
                uBuf.position(row * uvRowStride)
                uBuf.get(u, row * chromaW, chromaW)
                vBuf.position(row * uvRowStride)
                vBuf.get(v, row * chromaW, chromaW)
            }
        }
        var vuPos = ySize
        for (i in 0 until chromaSize) {
            nv21[vuPos++] = v[i]
            nv21[vuPos++] = u[i]
        }
    } else {
        // Semi-planar(如 NV12):UV 已交替,需要重排为 NV21(VU)
        // 直接按 pixelStride 抽取
        var vuPos = ySize
        for (row in 0 until chromaH) {
            for (col in 0 until chromaW) {
                val uvIdx = row * uvRowStride + col * uvPixelStride
                if (uvIdx + 1 < vBuf.limit()) {
                    nv21[vuPos++] = vBuf.get(uvIdx)
                    nv21[vuPos++] = uBuf.get(uvIdx)
                } else {
                    nv21[vuPos++] = 0
                    nv21[vuPos++] = 0
                }
            }
        }
    }

    // 3. YuvImage → JPEG → Bitmap
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, w, h, null)
    val out = ByteArrayOutputStream(w * h / 4)
    yuvImage.compressToJpeg(Rect(0, 0, w, h), 85, out)
    val jpegBytes = out.toByteArray()
    return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
}
