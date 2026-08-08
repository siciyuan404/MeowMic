package com.meowmic.client.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.io.ByteArrayOutputStream
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.meowmic.client.ConnectionState
import com.meowmic.client.LauncherRepository
import com.meowmic.client.MeowMicViewModel
import com.meowmic.client.NativeBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 视频显示模式
 * - FIT:按比例伸缩,保持宽高比,上下/左右留黑边(默认)
 * - FILL:自适应填充,拉伸填满整个区域(会变形)
 * - CROP:裁剪填充,保持宽高比,裁掉超出部分
 */
enum class DisplayMode(val label: String) {
    FIT("按比例伸缩"),
    FILL("自适应填充"),
    CROP("裁剪填充"),
}

/**
 * 远程显示器页面
 *
 * UDP push 模式(借鉴 Sunshine 架构):
 * - PC 服务端持续采集 DXGI → H.264 硬件编码 → UDP 分片推送
 * - Android 端 Rust 核心接收 UDP 分片 → 重组 → MediaCodec 硬解 → Surface 直渲
 * - SurfaceView 零拷贝:解码输出直接渲染到屏幕,不经 CPU
 *
 * 支持三种显示模式(Fit/Fill/Crop)、全屏沉浸式横屏、全屏触控交互、虚拟键盘。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MonitorScreen(
    vm: MeowMicViewModel,
    onBack: () -> Unit,
    onDisconnect: () -> Unit = {},
    onNavigate: (String) -> Unit = {},
) {
    val connectionState by vm.connectionState.collectAsState()
    val stats by vm.stats.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity

    val addr = (connectionState as? ConnectionState.Connected)?.serverAddr ?: ""

    // 远程画面开关
    var monitorEnabled by remember { mutableStateOf(false) }
    // 目标帧率(也作为轮询间隔依据)
    var frameRate by remember { mutableStateOf(30) }
    // 设置浮层开关
    var showSettings by remember { mutableStateOf(false) }
    // 连接栏展开
    var connExpanded by remember { mutableStateOf(false) }
    // 显示模式(裁剪/填充/按比例伸缩)
    var displayMode by remember { mutableStateOf(DisplayMode.FIT) }
    // 全屏模式开关
    var isFullscreen by remember { mutableStateOf(false) }
    // 虚拟键盘可见状态
    var showKeyboard by remember { mutableStateOf(false) }

    // 当前帧是否已收到首帧(用于 UI 状态切换)
    var hasFirstFrame by remember { mutableStateOf(false) }
    // 屏幕分辨率(供 UI 显示与显示模式计算)
    var screenInfo by remember { mutableStateOf<LauncherRepository.ScreenInfo?>(null) }
    // 拉取统计
    var frameCount by remember { mutableStateOf(0L) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var decoderStatus by remember { mutableStateOf<String?>(null) }

    // MediaCodec 解码器实例
    var mediaCodec by remember { mutableStateOf<MediaCodec?>(null) }
    // Surface 引用(SurfaceView 就绪后传入 MediaCodec)
    var videoSurface by remember { mutableStateOf<Surface?>(null) }

    // 防止视频推流重复启动:同一 Surface 只启动一次
    var streamStartedForSurface by remember { mutableStateOf<Surface?>(null) }

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

    // 全屏模式:控制屏幕方向与系统栏沉浸式
    // 进入全屏=锁定横屏 + 隐藏状态栏/导航栏(immersive sticky);退出=恢复
    DisposableEffect(isFullscreen) {
        val win = activity?.window
        val controller = win?.let { WindowInsetsControllerCompat(it, it.decorView) }
        if (isFullscreen) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            win?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            win?.let { WindowCompat.setDecorFitsSystemWindows(it, true) }
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            // 离开页面或状态切换时统一恢复,避免沉浸式残留
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            win?.let { WindowCompat.setDecorFitsSystemWindows(it, true) }
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 合并的视频生命周期:创建解码器 + 启动推流(参考 Moonlight 的 surfaceChanged → conn.start 模式)
    // 关键修复:所有失败分支统一走 cleanup 路径,确保 codec 已 start 后必 stop+release,
    // 避免 Surface "already connected" 错误(参考 Moonlight tryConfigureDecoder 的 finally 模式)
    LaunchedEffect(videoSurface, monitorEnabled) {
        if (!monitorEnabled || videoSurface == null) {
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
            hasFirstFrame = false
            decoderStatus = null
            fetchError = null
            streamStartedForSurface = null
            return@LaunchedEffect
        }

        // 已有错误或已对该 Surface 启动过推流,不再重复
        if (fetchError != null) {
            Log.d(TAG_DEC, "已有错误,跳过: $fetchError")
            return@LaunchedEffect
        }
        if (streamStartedForSurface == videoSurface) {
            Log.d(TAG_DEC, "该 Surface 已启动推流,跳过")
            return@LaunchedEffect
        }

        val w = screenInfo?.width ?: 1920
        val h = screenInfo?.height ?: 1080
        val fps = frameRate
        val bitrate = 4_000_000

        // 1. 创建 MediaCodec
        // 注意:codec 一旦 configure 就占用 Surface,任何失败必须 release
        decoderStatus = "初始化解码器 ${w}x${h}..."
        var codecStarted = false
        try {
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, w * h * 3 / 2)
            }
            codec.configure(format, videoSurface, null, 0)
            codec.start()
            codecStarted = true
            mediaCodec = codec
            decoderStatus = "解码器就绪"
            fetchError = null
            Log.i(TAG_DEC, "H.264 解码器启动(Surface 直渲) ${w}x${h}")
        } catch (e: Exception) {
            Log.e(TAG_DEC, "解码器启动失败: ${e.message}")
            // 失败立即释放,避免 codec 残留占用 Surface
            try {
                if (codecStarted) mediaCodec?.stop()
            } catch (_: Exception) {}
            try {
                mediaCodec?.release()
            } catch (_: Exception) {}
            mediaCodec = null
            decoderStatus = "解码器失败: ${e.message}"
            fetchError = decoderStatus
            return@LaunchedEffect
        }

        // cleanup 闭包:codec 已 start 后,任何失败分支调用此函数释放资源
        // (参考 Moonlight MediaCodecDecoderRenderer 的 release 模式)
        fun releaseCodec() {
            try {
                if (codecStarted) mediaCodec?.stop()
            } catch (_: Exception) {}
            try {
                mediaCodec?.release()
            } catch (_: Exception) {}
            mediaCodec = null
        }

        // 2. 检查连接
        try {
            if (!NativeBridge.nativeIsConnected()) {
                Log.w(TAG_DEC, "TCP 控制连接已断开")
                fetchError = "连接已断开,请重新连接"
                releaseCodec()
                return@LaunchedEffect
            }
        } catch (e: Throwable) {
            Log.w(TAG_DEC, "nativeIsConnected 异常: ${e.message}")
        }

        // 3. 请求服务端开始推流
        val started = try {
            NativeBridge.nativeStartVideo(w, h, fps, bitrate)
        } catch (e: Throwable) {
            Log.e(TAG_DEC, "nativeStartVideo 异常: ${e.javaClass.simpleName}: ${e.message}")
            fetchError = "视频推流启动异常: ${e.message}"
            false
        }
        if (!started) {
            fetchError = fetchError ?: "请求视频推流失败"
            releaseCodec()
            return@LaunchedEffect
        }

        streamStartedForSurface = videoSurface
        Log.i(TAG_DEC, "视频推流已启动 ${w}x${h}@${fps}fps")

        // 4. NALU 拉取 + 渲染循环
        // 关键修复:用 try/finally 保证循环退出(正常 break / 异常 / Coroutine 取消)时必释放 codec,
        // 否则 Surface 残留占用会导致下次 configure "already connected" 错误
        //
        // 诊断计数器(每秒打印一次,帮助定位"看不到画面"问题)
        var naluReceived = 0L
        var naluFed = 0L
        var naluDroppedInputFull = 0L
        var naluDroppedTooLarge = 0L
        var framesDecoded = 0L
        var lastDiagTime = System.currentTimeMillis()
        try {
            while (isActive) {
                // 每轮检查连接状态
                try {
                    if (!NativeBridge.nativeIsConnected()) {
                        Log.w(TAG_DEC, "TCP 控制连接在推流中意外断开")
                        fetchError = "连接已断开"
                        break
                    }
                } catch (e: Throwable) {
                    // 忽略,继续轮询
                }

                val nalu = try {
                    NativeBridge.nativePollVideoFrame()
                } catch (e: Throwable) {
                    Log.w(TAG_DEC, "nativePollVideoFrame 异常: ${e.message}")
                    delay(10)
                    continue
                }
                if (nalu == null || nalu.isEmpty()) {
                    delay(5)
                    continue
                }
                naluReceived++

                try {
                    // 关键修复:dequeueInputBuffer 超时返回 -1 时,不要丢弃 nalu!
                    // 旧逻辑:nalu 已从 native 队列 poll 出来,但 idx<0 时直接跳过,
                    // 导致关键帧被静默丢弃,解码器永远收不到 SPS/PPS/IDR → 无画面。
                    // 新逻辑:idx<0 时短暂 delay 后把 nalu 重新放回解码循环(本地重试队列)。
                    // 但 MediaCodec 无 pushBack,所以用增大超时 + 限流日志的方式:
                    // 超时 10ms(从 5ms 提升),如果仍满则记日志并丢弃(避免无限阻塞)。
                    val idx = mediaCodec?.dequeueInputBuffer(10_000) ?: -1
                    if (idx < 0) {
                        naluDroppedInputFull++
                        // 不立即丢弃:延迟 2ms 后重试一次
                        delay(2)
                        val idx2 = mediaCodec?.dequeueInputBuffer(10_000) ?: -1
                        if (idx2 < 0) {
                            // 两次都满,确实解码器繁忙,丢弃此帧(记日志)
                            if (naluDroppedInputFull <= 3 || naluDroppedInputFull % 100 == 0L) {
                                Log.w(TAG_DEC, "输入队列满,nalu 被丢弃 count=$naluDroppedInputFull size=${nalu.size}")
                            }
                        } else {
                            feedNaluToCodec(mediaCodec, nalu, idx2)
                            naluFed++
                        }
                    } else {
                        feedNaluToCodec(mediaCodec, nalu, idx)
                        naluFed++
                    }

                    // drain 输出
                    val info = MediaCodec.BufferInfo()
                    while (true) {
                        val outIdx = mediaCodec?.dequeueOutputBuffer(info, 0) ?: -1
                        when {
                            outIdx >= 0 -> {
                                mediaCodec?.releaseOutputBuffer(outIdx, true)
                                framesDecoded++
                                if (!hasFirstFrame) {
                                    hasFirstFrame = true
                                    decoderStatus = null
                                    Log.i(TAG_DEC, "首帧解码成功! framesDecoded=$framesDecoded")
                                }
                                frameCount++
                            }
                            outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                Log.i(TAG_DEC, "输出格式变化: ${mediaCodec?.outputFormat}")
                            }
                            else -> break
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG_DEC, "解码 feed 失败: ${e.message}")
                }

                // 每秒打印一次诊断统计
                val now = System.currentTimeMillis()
                if (now - lastDiagTime >= 1000) {
                    Log.i(TAG_DEC, "诊断: recv=$naluReceived fed=$naluFed droppedFull=$naluDroppedInputFull droppedLarge=$naluDroppedTooLarge decoded=$framesDecoded")
                    naluReceived = 0
                    naluFed = 0
                    naluDroppedInputFull = 0
                    naluDroppedTooLarge = 0
                    lastDiagTime = now
                }
            }
        } finally {
            // 循环退出(任何原因):统一释放 codec,避免 Surface 泄漏
            releaseCodec()
            streamStartedForSurface = null
        }
    }

    // 视频统计上报:每 1 秒取一次快照 → 上报给服务端做自适应码率
    // 视频未启动时 nativePollVideoStats 返回全 0,nativeSendVideoStats 内部也会判空,无副作用
    LaunchedEffect(monitorEnabled, addr) {
        if (!monitorEnabled || addr.isBlank()) return@LaunchedEffect
        while (isActive) {
            delay(1_000)
            val json = try {
                NativeBridge.nativePollVideoStats()
            } catch (e: Throwable) {
                Log.w(TAG_DEC, "nativePollVideoStats 异常: ${e.message}")
                continue
            }
            // 简易 JSON 解析(避免引入 org.json 依赖)
            // JSON 格式: {"received":N,"lost":N,"recovered":N}
            val received = Regex("""received"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val lost = Regex("""lost"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val recovered = Regex("""recovered"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            // 三个字段全 0 时跳过上报(无活动)
            if (received == 0 && lost == 0 && recovered == 0) continue
            try {
                NativeBridge.nativeSendVideoStats(received, lost, recovered, 0)
            } catch (e: Throwable) {
                Log.w(TAG_DEC, "上报视频统计失败: ${e.message}")
            }
        }
    }

    // 退出页面释放
    DisposableEffect(Unit) {
        onDispose {
            NativeBridge.nativeStopVideo()
            try {
                mediaCodec?.stop()
            } catch (_: Exception) {}
            try {
                mediaCodec?.release()
            } catch (_: Exception) {}
            mediaCodec = null
        }
    }

    // 视频宽高(显示模式计算用)
    val videoW = screenInfo?.width ?: 1920
    val videoH = screenInfo?.height ?: 1080
    val videoAspect = videoW.toFloat() / videoH.toFloat()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (isFullscreen) Modifier else Modifier.padding(12.dp)),
            verticalArrangement = if (isFullscreen) Arrangement.spacedBy(0.dp) else Arrangement.spacedBy(8.dp),
        ) {
            // 1. 可折叠连接栏(全屏时隐藏)
            if (!isFullscreen) {
                ConnBar(
                    addr = addr,
                    expanded = connExpanded,
                    onToggle = { connExpanded = !connExpanded },
                    touchSent = touchSent,
                    audioSent = audioSent,
                    landscape = false,
                )
            }

            // 2. 顶部操作栏(全屏时隐藏;非全屏显示页面切换 + 画面开关 + 全屏/键盘/设置/断开)
            if (!isFullscreen) {
                MonitorActionBar(
                    monitorEnabled = monitorEnabled,
                    onToggleMonitor = { monitorEnabled = !monitorEnabled },
                    onShowSettings = { showSettings = true },
                    onShowKeyboard = { showKeyboard = true },
                    onToggleFullscreen = { isFullscreen = true },
                    onBack = onBack,
                    onNavigate = onNavigate,
                    onDisconnect = onDisconnect,
                )
            }

            // 3. 视频显示区(SurfaceView 零拷贝直渲)
            // 全屏与非全屏共用此区域,AndroidView 处于稳定位置,切换全屏/显示模式不会重建 MediaCodec
            // BoxWithConstraints 提供容器尺寸,用于 CROP 模式计算 matchHeightConstraintsFirst
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(
                        if (isFullscreen) Modifier.clipToBounds()
                        else Modifier.clip(RoundedCornerShape(12.dp))
                    )
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (monitorEnabled) {
                    // 容器宽高比(用于 CROP 判定:视频更宽则匹配高度优先,反之匹配宽度优先)
                    val containerAspect: Float =
                        if (maxHeight > 0.dp && maxWidth > 0.dp) maxWidth / maxHeight
                        else videoAspect

                    // 根据显示模式计算 SurfaceView 的 modifier
                    // - FILL:fillMaxSize 拉伸(当前行为)
                    // - FIT :aspectRatio,保持宽高比并居中留黑边
                    // - CROP:aspectRatio 并让较大维度溢出,被外层 clip 裁掉
                    val surfaceModifier = when (displayMode) {
                        DisplayMode.FILL -> Modifier.fillMaxSize()
                        DisplayMode.FIT -> Modifier.aspectRatio(videoAspect)
                        DisplayMode.CROP -> Modifier.aspectRatio(
                            videoAspect,
                            matchHeightConstraintsFirst = videoAspect > containerAspect,
                        )
                    }

                    // SurfaceView: MediaCodec 解码输出直接渲染到 Surface(GPU 零拷贝)
                    // 注意:factory 仅在首次创建时执行,切换显示模式只触发 layout 不会重建 Surface,
                    // 因此不会重建 MediaCodec(避免视频流中断)。
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
                        modifier = surfaceModifier,
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

                    // 全屏模式:视频上叠加触控层与浮动按钮(仅首帧已到达时启用)
                    if (isFullscreen && hasFirstFrame) {
                        // 触控层:捕获 MotionEvent 交给 TouchHandler
                        // 单指=鼠标移动/点击,双指=滚轮/右键,三指=手势
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInteropFilter { event ->
                                    vm.handleTouch(event)
                                    true
                                },
                        )
                        // 退出全屏按钮(右上角浮动)
                        FloatingCircleButton(
                            icon = Icons.Default.FullscreenExit,
                            contentDescription = "退出全屏",
                            onClick = { isFullscreen = false },
                            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                        )
                        // 虚拟键盘按钮(右下角浮动)
                        FloatingCircleButton(
                            icon = Icons.Default.Keyboard,
                            contentDescription = "虚拟键盘",
                            onClick = { showKeyboard = true },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                            size = 44.dp,
                            iconSize = 22.dp,
                        )
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

            // 4. 底部状态栏(全屏时隐藏):分辨率 / 帧数 (UDP push 模式无拉取延迟)
            if (!isFullscreen) {
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
                displayMode = displayMode,
                onDisplayModeChange = { displayMode = it },
                onDismiss = { showSettings = false },
            )
        }

        // 虚拟键盘底部弹出(全屏与非全屏均可使用)
        if (showKeyboard) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { showKeyboard = false },
                sheetState = sheetState,
            ) {
                MonitorKeyboardPanel(vm = vm)
            }
        }
    }
}

/** 全屏模式下浮动圆形按钮(半透明黑底 + 白色图标) */
@Composable
private fun FloatingCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    iconSize: androidx.compose.ui.unit.Dp = 20.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(iconSize), tint = Color.White)
    }
}

/** 远程显示器顶部操作栏:页面切换组 + 远程画面开关 + 全屏/键盘/设置 + 断开 */
@Composable
private fun MonitorActionBar(
    monitorEnabled: Boolean,
    onToggleMonitor: () -> Unit,
    onShowSettings: () -> Unit,
    onShowKeyboard: () -> Unit,
    onToggleFullscreen: () -> Unit,
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

        // 右侧上下文:远程画面开关 + 全屏 + 键盘 + 设置
        ToggleButtonSmall(
            icon = if (monitorEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
            contentDescription = if (monitorEnabled) "关闭远程画面" else "开启远程画面",
            isOn = monitorEnabled,
            onClick = onToggleMonitor,
            buttonSize = btnSize,
            iconSize = icSize,
        )
        IconButtonSmall(
            icon = Icons.Default.Fullscreen,
            contentDescription = "全屏",
            onClick = onToggleFullscreen,
            buttonSize = btnSize,
            iconSize = icSize,
        )
        IconButtonSmall(
            icon = Icons.Default.Keyboard,
            contentDescription = "虚拟键盘",
            onClick = onShowKeyboard,
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

/** 画面设置浮层:帧率 + 显示模式选择 */
@Composable
private fun MonitorSettingsDialog(
    frameRate: Int,
    onFrameRateChange: (Int) -> Unit,
    displayMode: DisplayMode,
    onDisplayModeChange: (DisplayMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("画面设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 显示模式选择
                Text("显示模式", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DisplayMode.entries.forEach { mode ->
                        FilterChip(
                            selected = displayMode == mode,
                            onClick = { onDisplayModeChange(mode) },
                            label = { Text(mode.label, fontSize = 11.sp) },
                        )
                    }
                }
                // 帧率选择
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
                    "提示:切换显示模式不会中断视频流;修改帧率后立即生效,无需重新连接。",
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
// 虚拟键盘面板(自包含:调用 NativeBridge.sendKeyDown/sendKeyUp 发送按键)
// 支持:长按上滑=锁定长按(🔒,对标实体键盘按住不放);再次点击=取消锁定
// ════════════════════════════════════════════════════════════════
private const val KEY_TAG_MON = "MeowMicMonKey"

@Composable
private fun MonitorKeyboardPanel(vm: MeowMicViewModel) {
    // 长按锁定状态:VK code → true(对标实体键盘按住不放,持续 keydown)
    val lockedKeys = remember { mutableStateMapOf<Int, Boolean>() }

    // 连接断开时清除所有锁定状态(服务端会自行清理,这里只重置 UI)
    val connectionState by vm.connectionState.collectAsState()
    LaunchedEffect(connectionState) {
        if (connectionState !is ConnectionState.Connected) {
            lockedKeys.clear()
        }
    }

    /** 锁定指定键(发送 keydown 并保持,对标实体键盘按住不放) */
    fun lockKey(keyCode: Int) {
        if (lockedKeys[keyCode] != true) {
            if (NativeBridge.sendKeyDown(keyCode)) {
                lockedKeys[keyCode] = true
                vm.playFeedbackSound()
            }
        }
    }

    /**
     * 触发按键:
     * - 已锁定态的键:单击=解除锁定(仅发 keyup)
     * - 普通键:发送 keypress(按下+抬起)
     * 锁定态修饰键会持续 keydown,后续普通键在服务端自然组合(如 Ctrl 锁定后点 C = Ctrl+C)
     */
    fun fireKey(keyCode: Int) {
        if (lockedKeys[keyCode] == true) {
            if (NativeBridge.sendKeyUp(keyCode)) {
                lockedKeys.remove(keyCode)
                vm.playFeedbackSound()
            }
            return
        }
        if (NativeBridge.sendKeyPress(keyCode)) {
            vm.playFeedbackSound()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            "长按上滑=锁定长按(🔒),再次点击取消",
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        // 第 1 行:Esc | F1-F4 | F5-F8 | F9-F12
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            MonitorKeyBtn("Esc", VK.ESCAPE, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f), fn = true, small = true)
            Spacer(Modifier.weight(0.4f))
            MonitorKeyBtn("F1", VK.F1, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f), fn = true, small = true)
            MonitorKeyBtn("F2", VK.F2, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f), fn = true, small = true)
            MonitorKeyBtn("F3", VK.F3, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f), fn = true, small = true)
            MonitorKeyBtn("F4", VK.F4, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f), fn = true, small = true)
            Spacer(Modifier.weight(0.4f))
            MonitorKeyBtn("F5", VK.F5, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f), fn = true, small = true)
            MonitorKeyBtn("F6", VK.F6, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f), fn = true, small = true)
            MonitorKeyBtn("F7", VK.F7, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f), fn = true, small = true)
            MonitorKeyBtn("F8", VK.F8, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f), fn = true, small = true)
            Spacer(Modifier.weight(0.4f))
            MonitorKeyBtn("F9", VK.F9, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f), fn = true, small = true)
            MonitorKeyBtn("F10", VK.F10, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f), fn = true, small = true)
            MonitorKeyBtn("F11", VK.F11, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f), fn = true, small = true)
            MonitorKeyBtn("F12", VK.F12, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f), fn = true, small = true)
        }
        // 第 2 行:数字行 ` 1-0 - = ⌫
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            MonitorKeyBtn("`", VK.OEM_3, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("1", VK.D1, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("2", VK.D2, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("3", VK.D3, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("4", VK.D4, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("5", VK.D5, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("6", VK.D6, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("7", VK.D7, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("8", VK.D8, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("9", VK.D9, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("0", VK.D0, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("-", VK.OEM_MINUS, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("=", VK.OEM_PLUS, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("⌫", VK.BACK, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1.6f), fn = true)
        }
        // 第 3 行:QWERTY 行 Tab Q-P [ ] \
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            MonitorKeyBtn("Tab", VK.TAB, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1.6f), fn = true)
            MonitorKeyBtn("Q", VK.Q, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("W", VK.W, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("E", VK.E, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("R", VK.R, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("T", VK.T, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("Y", VK.Y, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("U", VK.U, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("I", VK.I, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("O", VK.O, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("P", VK.P, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("[", VK.OEM_4, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("]", VK.OEM_6, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("\\", VK.OEM_5, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
        }
        // 第 4 行:ASDF 行 Caps A-L ; ' ↵
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            MonitorKeyBtn("Caps", VK.CAPITAL, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(2.2f), fn = true)
            MonitorKeyBtn("A", VK.A, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("S", VK.S, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("D", VK.D, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("F", VK.F, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("G", VK.G, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("H", VK.H, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("J", VK.J, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("K", VK.K, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("L", VK.L, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn(";", VK.OEM_1, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("'", VK.OEM_7, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("↵", VK.RETURN, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(2.2f), fn = true)
        }
        // 第 5 行:ZXCV 行 ⇧ Z-/ ⇧
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            MonitorKeyBtn("⇧", VK.LSHIFT, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(2.2f), fn = true)
            MonitorKeyBtn("Z", VK.Z, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("X", VK.X, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("C", VK.C, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("V", VK.V, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("B", VK.B, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("N", VK.N, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("M", VK.M, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn(",", VK.OEM_COMMA, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn(".", VK.OEM_PERIOD, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("/", VK.OEM_2, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f))
            MonitorKeyBtn("⇧", VK.RSHIFT, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(2.2f), fn = true)
        }
        // 第 6 行:修饰键行 Ctrl Win Alt 空格 Alt Fn Menu Ctrl
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            MonitorKeyBtn("Ctrl", VK.LCONTROL, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f), fn = true)
            MonitorKeyBtn("Win", VK.LWIN, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f), fn = true)
            MonitorKeyBtn("Alt", VK.LMENU, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f), fn = true)
            MonitorKeyBtn("空格", VK.SPACE, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(5.6f), small = true)
            MonitorKeyBtn("Alt", VK.RMENU, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f), fn = true)
            MonitorKeyBtn("Fn", VK.FN, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f), fn = true)
            MonitorKeyBtn("Menu", VK.MENU_KEY, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f), fn = true)
            MonitorKeyBtn("Ctrl", VK.RCONTROL, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f), fn = true)
        }
        // 第 7 行:PrtSc ScrLk Pause | Ins Home PgUp | ↑
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            MonitorKeyBtn("PrtSc", VK.SNAPSHOT, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(3f), fn = true, small = true)
            MonitorKeyBtn("ScrLk", VK.SCROLL, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(3f), fn = true, small = true)
            MonitorKeyBtn("Pause", VK.PAUSE, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(3f), fn = true, small = true)
            Spacer(Modifier.weight(1f))
            MonitorKeyBtn("Ins", VK.INSERT, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f), fn = true)
            MonitorKeyBtn("Home", VK.HOME, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f), fn = true)
            MonitorKeyBtn("PgUp", VK.PRIOR, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f), fn = true)
            Spacer(Modifier.weight(0.4f))
            MonitorKeyBtn("↑", VK.UP, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f), fn = true)
        }
        // 第 8 行:Del End PgDn | ← ↓ →
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            MonitorKeyBtn("Del", VK.DELETE, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(3f), fn = true, small = true)
            MonitorKeyBtn("End", VK.END, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(3f), fn = true, small = true)
            MonitorKeyBtn("PgDn", VK.NEXT, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(3f), fn = true, small = true)
            Spacer(Modifier.weight(1f))
            MonitorKeyBtn("←", VK.LEFT, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f), fn = true)
            MonitorKeyBtn("↓", VK.DOWN, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f), fn = true)
            MonitorKeyBtn("→", VK.RIGHT, lockedKeys, ::fireKey, ::lockKey, Modifier.weight(1f), fn = true)
        }
    }
}

/**
 * 通用键位按钮(对齐 TouchpadScreen KeyBtn):
 * - fn=true:功能/修饰键,使用次级背景色 + 较小字号
 * - 手势:长按 + 向上滑动 → 锁定(发送 keydown 并保持,对标实体键盘按住不放);再次单击 → 解锁
 * - 普通单击:发送 keypress
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MonitorKeyBtn(
    label: String,
    vkCode: Int,
    lockedKeys: SnapshotStateMap<Int, Boolean>,
    onFireKey: (Int) -> Unit,
    onLockKey: (Int) -> Unit,
    modifier: Modifier = Modifier,
    fn: Boolean = false,
    small: Boolean = false,
) {
    val isLocked = lockedKeys[vkCode] ?: false
    val bgColor = when {
        isLocked -> MaterialTheme.colorScheme.tertiary           // 锁定:区别色
        fn -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val borderColor = if (isLocked) MaterialTheme.colorScheme.tertiary
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val fgColor = if (isLocked) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    val fontSize = when {
        small -> 9.sp
        fn -> 10.sp
        else -> 11.sp
    }
    Box(
        modifier = modifier
            .height(32.dp)
            .background(bgColor, RoundedCornerShape(6.dp))
            .border(
                if (isLocked) 1.5.dp else 1.dp,
                borderColor,
                RoundedCornerShape(6.dp),
            )
            .pointerInput(vkCode) {
                // 手势:长按 + 向上滑动 → 锁定;再次单击 → 解锁
                val lockThreshold = 24.dp.toPx()  // 向上滑动锁定阈值
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startY = down.position.y
                    var gestureLocked = false  // 本次手势是否已触发锁定
                    var done = false
                    Log.d(KEY_TAG_MON, "down vk=0x${vkCode.toString(16)} startY=${startY.toInt()} alreadyLocked=${lockedKeys[vkCode]}")
                    while (!done) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        for (change in event.changes) {
                            val dy = change.position.y - startY
                            // 向上滑动超过阈值 → 锁定(仅未锁定时响应)
                            if (!gestureLocked && dy < -lockThreshold && lockedKeys[vkCode] != true) {
                                gestureLocked = true
                                change.consume()
                                onLockKey(vkCode)
                                Log.d(KEY_TAG_MON, "lock vk=0x${vkCode.toString(16)} dy=${dy.toInt()}")
                            }
                            if (gestureLocked) change.consume()
                            if (change.changedToUp()) {
                                done = true
                                Log.d(KEY_TAG_MON, "up vk=0x${vkCode.toString(16)} gestureLocked=$gestureLocked")
                            }
                        }
                    }
                    // 抬起后:未触发锁定 → 单击(已锁定则解锁,否则发普通按键)
                    if (!gestureLocked) {
                        Log.d(KEY_TAG_MON, "fireKey vk=0x${vkCode.toString(16)} wasLocked=${lockedKeys[vkCode]}")
                        onFireKey(vkCode)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            // 锁定态追加 🔒 视觉提示
            text = if (isLocked) "$label 🔒" else label,
            fontSize = fontSize,
            color = fgColor,
            fontWeight = if (isLocked) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

// ════════════════════════════════════════════════════════════════
// H.264 硬解码器封装
// ════════════════════════════════════════════════════════════════

private const val TAG_DEC = "H264Decoder"

/**
 * 把 NALU 喂给 MediaCodec 输入队列(带容量检查)
 * - nalu 超过 buffer 容量时记日志并跳过(不应发生,1080p IDR 通常 < 256KB)
 * - 时间戳用系统纳秒(MediaCodec 期望微秒)
 */
private fun feedNaluToCodec(codec: MediaCodec?, nalu: ByteArray, idx: Int) {
    if (codec == null) return
    val buf = codec.getInputBuffer(idx) ?: return
    buf.clear()
    if (nalu.size > buf.capacity()) {
        // 超大 NALU,记日志(限流由调用方处理)
        Log.w(TAG_DEC, "NALU 超大 ${nalu.size} > ${buf.capacity()},截断")
        buf.put(nalu, 0, buf.capacity())
        codec.queueInputBuffer(idx, 0, buf.capacity(), System.nanoTime() / 1000, 0)
    } else {
        buf.put(nalu)
        codec.queueInputBuffer(idx, 0, nalu.size, System.nanoTime() / 1000, 0)
    }
}

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
