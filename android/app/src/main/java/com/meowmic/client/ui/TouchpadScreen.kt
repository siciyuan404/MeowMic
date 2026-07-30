package com.meowmic.client.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowmic.client.AudioInputManager
import com.meowmic.client.ConnectionState
import com.meowmic.client.MeowMicViewModel
import com.meowmic.client.NativeBridge
import com.meowmic.client.QuickAudioSlot
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs

// 按钮掩码常量(与 server/protocol 对应)
private const val BTN_LEFT = 0x01
private const val BTN_RIGHT = 0x02
private const val BTN_MIDDLE = 0x04

// ============ Windows VK code 常量(用于模拟键盘) ============
private object VK {
    // 修饰键
    const val CONTROL = 0x11
    const val SHIFT = 0x10
    const val MENU = 0x12       // Alt
    const val LWIN = 0x5B
    // 字母
    const val C = 0x43; const val V = 0x56; const val X = 0x58
    const val Z = 0x5A; const val A = 0x41; const val S = 0x53
    const val F = 0x46; const val W = 0x57; const val R = 0x52
    const val T = 0x54; const val D = 0x44
    // 功能键
    const val F1 = 0x70; const val F2 = 0x71; const val F3 = 0x72
    const val F4 = 0x73; const val F5 = 0x74; const val F6 = 0x75
    const val F7 = 0x76; const val F8 = 0x77; const val F9 = 0x78
    const val F10 = 0x79; const val F11 = 0x7A; const val F12 = 0x7B
    // 编辑键
    const val TAB = 0x09; const val RETURN = 0x0D; const val ESCAPE = 0x1B
    const val SPACE = 0x20; const val BACK = 0x08; const val DELETE = 0x2E
    const val INSERT = 0x2D; const val HOME = 0x24; const val END = 0x23
    const val PRIOR = 0x21  // PageUp
    const val NEXT = 0x22   // PageDown
    // 方向键
    const val LEFT = 0x25; const val UP = 0x26
    const val RIGHT = 0x27; const val DOWN = 0x28
    // 其他
    const val SNAPSHOT = 0x2C  // PrtScn
    const val CAPITAL = 0x14   // CapsLock
    const val NUMLOCK = 0x90
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TouchpadScreen(
    vm: MeowMicViewModel,
    onDisconnect: () -> Unit,
) {
    val connectionState by vm.connectionState.collectAsState()
    val stats by vm.stats.collectAsState()
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val connected = connectionState as? ConnectionState.Connected
    val serverAddr = connected?.serverAddr ?: "未知"
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // UI 状态
    var touchMode by remember { mutableStateOf("移动") }
    var pointerCount by remember { mutableStateOf(0) }
    var showTooltips by remember { mutableStateOf(false) }  // 泡泡提示开关,默认关闭
    var drawerExpanded by remember { mutableStateOf(false) }  // 底部抽屉展开状态,默认收起
    val currentAudioMode by vm.currentAudioMode.collectAsState()
    val audioHistory by vm.audioHistory.collectAsState()
    val quickSlots by vm.quickSlots.collectAsState()

    // 统计数据
    var touchSent = 0L
    var audioSent = 0L
    try {
        val json = JSONObject(stats)
        touchSent = json.optLong("touch_sent", 0)
        audioSent = json.optLong("audio_sent", 0)
    } catch (_: Exception) { }

    // 统一的文件选择器:由 vm.onAudioFilePicked 决定是绑定到 slot 还是加入历史
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { vm.onAudioFilePicked(it) }
    }

    // 屏幕方向控制:进入时锁定竖屏,离开时恢复
    val activity = context as? Activity
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Disconnected || connectionState is ConnectionState.Error) {
            onDisconnect()
        }
    }

    // 泡泡提示辅助函数
    fun showToast(msg: String) {
        if (showTooltips) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    // 请求横屏
    fun requestLandscape() {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    // 请求竖屏
    fun requestPortrait() {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    // ── 顶部状态栏(紧凑,带品牌标签) ──
    @Composable
    fun StatusBar() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Mouse, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("已连接 · $serverAddr", fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("T:$touchSent  A:$audioSent  $touchMode·${pointerCount}指", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // 品牌标签:竖屏/横屏
                Box(
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        if (isLandscape) "横屏" else "竖屏",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }

    // ── 顶部操作栏(返回 / 旋转 / 更多) ──
    @Composable
    fun ActionBar() {
        var menuExpanded by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 返回按钮
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp),
                    )
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(8.dp),
                    )
                    .clickable { onDisconnect() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.ArrowBack, "返回", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // 旋转按钮(占满剩余空间)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(8.dp),
                    )
                    .clickable {
                        if (isLandscape) requestPortrait() else requestLandscape()
                        showToast(if (isLandscape) "切换到竖屏" else "切换到横屏")
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ScreenRotation, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (isLandscape) "竖屏" else "横屏",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 更多按钮
            Box {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clickable { menuExpanded = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.MoreHoriz, "更多", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("提示开关", fontSize = 12.sp) },
                        onClick = {
                            showTooltips = !showTooltips
                            menuExpanded = false
                        },
                        trailingIcon = {
                            Switch(
                                checked = showTooltips,
                                onCheckedChange = null,
                                modifier = Modifier.scale(0.7f),
                            )
                        },
                    )
                }
            }
        }
    }

    // ── 触控区域(虚线边框占位风格) ──
    @Composable
    fun TouchArea(modifier: Modifier) {
        Box(
            modifier = modifier
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    RoundedCornerShape(16.dp),
                )
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    RoundedCornerShape(16.dp),
                )
                .pointerInteropFilter { event ->
                    // 仅在值变化时更新 state,避免每个 ACTION_MOVE 触发重组
                    val newCount = event.pointerCount
                    val newMode = when {
                        newCount >= 3 -> "三指"
                        newCount == 2 -> "双指"
                        else -> when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> "按下"
                            MotionEvent.ACTION_MOVE -> "移动"
                            MotionEvent.ACTION_UP -> "抬起"
                            else -> "移动"
                        }
                    }
                    if (newCount != pointerCount) pointerCount = newCount
                    if (newMode != touchMode) touchMode = newMode
                    vm.handleTouch(event)
                    true
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Mouse, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                    modifier = Modifier.size(if (isLandscape) 48.dp else 64.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "滑动移动 · 轻触左键 · 双指右键 · 双指滚动",
                    fontSize = if (isLandscape) 10.sp else 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
    }

    // ── 底部抽屉手柄(点击展开/收起) ──
    @Composable
    fun DrawerHandle() {
        val chevronRotation by animateFloatAsState(
            targetValue = if (drawerExpanded) 180f else 0f,
            animationSpec = tween(300),
            label = "chevron",
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { drawerExpanded = !drawerExpanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 抓取条
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            RoundedCornerShape(50),
                        ),
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.ExpandLess,
                        null,
                        Modifier.size(14.dp).rotate(chevronRotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (drawerExpanded) "点击收起" else "上滑显示控制",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }

    // PTT 长按说话按钮(整合 PPT/实时模式)
    // state: idle / recording / locked
    // idle → 按下 → recording → 抬起 → idle
    // idle → 按下 → recording → 滑动超过阈值 → locked
    // locked → 点击 → idle
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    fun PttButton(modifier: Modifier) {
        // 状态: idle(空闲) / recording(录音中) / locked(锁定录音)
        var btnState by remember { mutableStateOf("idle") }
        val density = LocalDensity.current
        val lockThresholdPx = with(density) { 60.dp.toPx() }

        // 用 mutableStateOf 包装当前状态供 pointerInput 读取,避免 key 变化重启
        val stateRef = remember { mutableStateOf("idle") }

        val bgColor = when (btnState) {
            "recording" -> MaterialTheme.colorScheme.error
            "locked" -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        }
        val icon = if (btnState == "idle") Icons.Default.MicOff else Icons.Default.Mic
        val text = when (btnState) {
            "recording" -> "录音中"
            "locked" -> "已锁定·点取消"
            else -> "长按说话"
        }

        Box(
            modifier = modifier
                .height(48.dp)
                .background(bgColor, RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val pressX = down.position.x
                        val pressY = down.position.y
                        val currentState = stateRef.value

                        when (currentState) {
                            "idle" -> {
                                btnState = "recording"
                                stateRef.value = "recording"
                                vm.setMicEnabled(true)
                                showToast("开始录音(松开结束,滑动锁定)")
                                var locked = false
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    val change = event.changes.firstOrNull() ?: break
                                    if (!locked) {
                                        val dx: Float = change.position.x - pressX
                                        val dy: Float = change.position.y - pressY
                                        if (abs(dx) > lockThresholdPx || abs(dy) > lockThresholdPx) {
                                            locked = true
                                            btnState = "locked"
                                            stateRef.value = "locked"
                                            showToast("已锁定录音(点击取消)")
                                        }
                                    }
                                    if (change.changedToUp()) {
                                        if (!locked) {
                                            btnState = "idle"
                                            stateRef.value = "idle"
                                            vm.setMicEnabled(false)
                                        }
                                        break
                                    }
                                }
                            }
                            "locked" -> {
                                // 点击取消锁定 - 等待抬起后再切换状态
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    val change = event.changes.firstOrNull() ?: break
                                    if (change.changedToUp()) {
                                        btnState = "idle"
                                        stateRef.value = "idle"
                                        vm.setMicEnabled(false)
                                        showToast("已取消锁定录音")
                                        break
                                    }
                                }
                            }
                            else -> {
                                // recording 状态防御:等待抬起
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    val change = event.changes.firstOrNull() ?: break
                                    if (change.changedToUp()) {
                                        btnState = "idle"
                                        stateRef.value = "idle"
                                        vm.setMicEnabled(false)
                                        break
                                    }
                                }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(18.dp), tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text(text, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
            }
        }
    }

    /**
     * 快捷音频 slot 小块
     * - 未设置:显示 "+",点击触发文件选择
     * - 已设置:显示名称 tag,点击播放,长按弹出菜单(编辑/清空/移动)
     */
    @Composable
    fun QuickSlotCell(
        slot: QuickAudioSlot?,
        index: Int,
        onPickFileFor: (Int) -> Unit,
        onPlay: (Int) -> Unit,
        onClear: (Int) -> Unit,
        onMoveTo: (Int, Int) -> Unit,
    ) {
        var menuExpanded by remember { mutableStateOf(false) }
        var showMoveDialog by remember { mutableStateOf(false) }

        val isSet = slot != null
        val bgColor = if (isSet) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
        val fgColor = if (isSet) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant

        Box {
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .fillMaxWidth()
                    .background(bgColor, RoundedCornerShape(8.dp))
                    .border(
                        width = if (isSet) 0.dp else 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .pointerInput(slot, index) {
                        detectTapGestures(
                            onTap = {
                                if (isSet) {
                                    onPlay(index)
                                    showToast("播放:${slot?.name}")
                                } else {
                                    onPickFileFor(index)
                                    showToast("选择音频绑定到位置 ${index + 1}")
                                }
                            },
                            onLongPress = {
                                if (isSet) {
                                    menuExpanded = true
                                    showToast("长按:编辑/清空/移动")
                                } else {
                                    onPickFileFor(index)
                                    showToast("选择音频绑定到位置 ${index + 1}")
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (isSet) {
                    Text(
                        slot!!.name,
                        fontSize = 10.sp,
                        color = fgColor,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                } else {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp), tint = fgColor)
                }
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("编辑(重新选择)") },
                    onClick = {
                        menuExpanded = false
                        onPickFileFor(index)
                    },
                    leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(16.dp)) },
                )
                DropdownMenuItem(
                    text = { Text("移动到...") },
                    onClick = {
                        menuExpanded = false
                        showMoveDialog = true
                    },
                    leadingIcon = { Icon(Icons.Default.SwapHoriz, null, Modifier.size(16.dp)) },
                )
                DropdownMenuItem(
                    text = { Text("清空") },
                    onClick = {
                        menuExpanded = false
                        onClear(index)
                    },
                    leadingIcon = { Icon(Icons.Default.Delete, null, Modifier.size(16.dp)) },
                )
            }
        }

        if (showMoveDialog) {
            AlertDialog(
                onDismissRequest = { showMoveDialog = false },
                title = { Text("移动到位置") },
                text = {
                    Column {
                        (0 until MeowMicViewModel.QUICK_SLOT_COUNT).forEach { target ->
                            TextButton(
                                onClick = {
                                    showMoveDialog = false
                                    onMoveTo(index, target)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("位置 ${target + 1}" + if (target == index) "(当前)" else "")
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showMoveDialog = false }) { Text("取消") }
                },
            )
        }
    }

    /**
     * 音频面板:历史 + 快捷 slot
     */
    @Composable
    fun AudioPanel() {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            // 音频源切换
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 麦克风源 pill
                Box(
                    modifier = Modifier
                        .height(28.dp)
                        .background(
                            if (currentAudioMode == AudioInputManager.InputMode.MICROPHONE)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(50),
                        )
                        .border(
                            1.dp,
                            if (currentAudioMode == AudioInputManager.InputMode.MICROPHONE)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(50),
                        )
                        .clickable {
                            vm.switchAudioMode(AudioInputManager.InputMode.MICROPHONE)
                            showToast("切换到麦克风源")
                        }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Mic, null, Modifier.size(12.dp), tint = if (currentAudioMode == AudioInputManager.InputMode.MICROPHONE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "麦克风源",
                            fontSize = 11.sp,
                            color = if (currentAudioMode == AudioInputManager.InputMode.MICROPHONE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // 音频文件 pill
                Box(
                    modifier = Modifier
                        .height(28.dp)
                        .background(
                            if (currentAudioMode == AudioInputManager.InputMode.MUSIC_FILE)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(50),
                        )
                        .border(
                            1.dp,
                            if (currentAudioMode == AudioInputManager.InputMode.MUSIC_FILE)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(50),
                        )
                        .clickable {
                            vm.cancelEditQuickSlot()
                            filePickerLauncher.launch(arrayOf("audio/*"))
                            showToast("选择音频文件")
                        }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.MusicNote, null, Modifier.size(12.dp), tint = if (currentAudioMode == AudioInputManager.InputMode.MUSIC_FILE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "音频文件",
                            fontSize = 11.sp,
                            color = if (currentAudioMode == AudioInputManager.InputMode.MUSIC_FILE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (currentAudioMode == AudioInputManager.InputMode.MUSIC_FILE) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .clickable {
                                vm.stopMusicPlayback()
                                showToast("停止音频播放")
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Stop, "停止", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 历史区
            Text("历史", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            Spacer(Modifier.height(4.dp))
            if (audioHistory.isEmpty()) {
                Text(
                    "暂无历史·点击「音频文件」选择",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(audioHistory) { item ->
                        Box(
                            modifier = Modifier
                                .height(20.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(6.dp),
                                )
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                    RoundedCornerShape(6.dp),
                                )
                                .clickable {
                                    scope.launch {
                                        vm.playMusicFile(item.path)
                                        showToast("播放:${item.name}")
                                    }
                                }
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(item.name, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 快捷 slot 区:4 列 x 2 行 = 8 个
            Text("快捷", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            Spacer(Modifier.height(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (row in 0 until 2) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        for (col in 0 until 4) {
                            val index = row * 4 + col
                            val slot = quickSlots.getOrNull(index)
                            Box(modifier = Modifier.weight(1f)) {
                                QuickSlotCell(
                                    slot = slot,
                                    index = index,
                                    onPickFileFor = { i ->
                                        vm.startEditQuickSlot(i)
                                        filePickerLauncher.launch(arrayOf("audio/*"))
                                    },
                                    onPlay = { i ->
                                        scope.launch {
                                            val ok = vm.playQuickSlot(i)
                                            if (!ok) showToast("播放失败")
                                        }
                                    },
                                    onClear = { i ->
                                        vm.clearQuickSlot(i)
                                        showToast("已清空位置 ${i + 1}")
                                    },
                                    onMoveTo = { from, to ->
                                        vm.moveQuickSlot(from, to)
                                        showToast("已移动 ${from + 1} → ${to + 1}")
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 语音控制面板:Tab 切换 麦克风 / 音频
    @Composable
    fun VoicePanel() {
        var selectedTab by remember { mutableStateOf(0) }  // 0=麦克风, 1=音频
        val micEnabled by vm.micEnabled.collectAsState()
        val muteSpk by vm.muteSpeaker.collectAsState()
        val currentMode by vm.currentAudioMode.collectAsState()

        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            // 面板标题行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Mic, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text("语音", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                Text("提示", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                Spacer(Modifier.width(6.dp))
                Switch(
                    checked = showTooltips,
                    onCheckedChange = { showTooltips = it },
                    modifier = Modifier.scale(0.7f),
                )
            }

            Spacer(Modifier.height(6.dp))

            // Tab 行(下划线风格)
            TabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.fillMaxWidth(),
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = {
                        selectedTab = 0
                        if (currentMode == AudioInputManager.InputMode.MUSIC_FILE) {
                            vm.stopMusicPlayback()
                        }
                        showToast("麦克风 Tab")
                    },
                    text = { Text("麦克风", fontSize = 12.sp) },
                    icon = { Icon(Icons.Default.Mic, null, Modifier.size(14.dp)) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        if (micEnabled) vm.setMicEnabled(false)
                        showToast("音频 Tab")
                    },
                    text = { Text("音频", fontSize = 12.sp) },
                    icon = { Icon(Icons.Default.MusicNote, null, Modifier.size(14.dp)) },
                )
            }

            Spacer(Modifier.height(10.dp))

            when (selectedTab) {
                0 -> {
                    // 麦克风 Tab:PTT 长按说话 + 静音外放
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PttButton(modifier = Modifier.weight(1f))
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable {
                                    val newMuted = !muteSpk
                                    vm.setMuteSpeaker(newMuted)
                                    showToast(if (newMuted) "已静音外放" else "已开启外放")
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (muteSpk) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                contentDescription = "静音外放",
                                tint = if (muteSpk) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                1 -> {
                    // 音频 Tab:音频面板(历史 + 快捷 slot)
                    AudioPanel()
                }
            }
        }
    }

    // 底部鼠标按键(支持按下/抬起,带图标)
    @Composable
    fun MouseButtonsBar() {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MouseBtn("左键", Icons.Default.Mouse, BTN_LEFT, Modifier.weight(1f).height(44.dp), "鼠标左键:单击/按住拖拽") { showToast(it) }
            MouseBtn("中键", Icons.Default.Mouse, BTN_MIDDLE, Modifier.weight(1f).height(44.dp), "鼠标中键:滚轮按键") { showToast(it) }
            MouseBtn("右键", Icons.Default.Menu, BTN_RIGHT, Modifier.weight(1f).height(44.dp), "鼠标右键:上下文菜单") { showToast(it) }
        }
    }

    // ============ 键盘面板(快捷键 + 粘滞修饰键) ============
    // 粘滞修饰键状态:点一下高亮保持,再点普通键时自动组合并清除
    val stickyMods = remember { mutableStateMapOf<Int, Boolean>() }

    fun fireKey(keyCode: Int) {
        // 按 VK code 升序固定下发顺序,避免哈希表迭代顺序不确定
        val activeMods = stickyMods.keys.filter { stickyMods[it] == true }.sorted()
        val pressedMods = mutableListOf<Int>()
        var allSuccess = true
        // 顺序按下修饰键,记录已成功的以便失败时回滚
        for (mod in activeMods) {
            if (NativeBridge.sendKeyDown(mod)) {
                pressedMods.add(mod)
            } else {
                allSuccess = false
                break
            }
        }
        // 仅在修饰键全部下发成功时才发主键,避免发出语义错误的裸键
        if (allSuccess && !NativeBridge.sendKeyPress(keyCode)) {
            allSuccess = false
        }
        // 逆序抬起已按下的修饰键,避免按键粘住
        for (mod in pressedMods.asReversed()) {
            NativeBridge.sendKeyUp(mod)
        }
        // 仅在全部成功时清空 stickyMods,失败时保留以允许重试
        if (allSuccess) {
            stickyMods.clear()
        }
    }

    @Composable
    fun StickyModBtn(label: String, vkCode: Int, modifier: Modifier = Modifier) {
        val active = stickyMods[vkCode] == true
        val bgColor = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant
        val fgColor = if (active) Color.White
            else MaterialTheme.colorScheme.onSurfaceVariant
        Box(
            modifier = modifier
                .height(36.dp)
                .background(bgColor, RoundedCornerShape(8.dp))
                .border(
                    1.dp,
                    if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    RoundedCornerShape(8.dp),
                )
                .clickable {
                    stickyMods[vkCode] = !(stickyMods[vkCode] ?: false)
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                fontSize = 11.sp,
                color = fgColor,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }

    @Composable
    fun ComboBtn(label: String, modifier: Modifier = Modifier, vararg keyCodes: Int) {
        Box(
            modifier = modifier
                .height(36.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(8.dp),
                )
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    RoundedCornerShape(8.dp),
                )
                .clickable {
                    NativeBridge.sendKeyCombo(*keyCodes)
                    // 组合键按钮自带修饰键,发送后清空粘滞状态,
                    // 避免与后续按键叠加(与 KeyBtn → fireKey 路径行为一致)
                    stickyMods.clear()
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
        }
    }

    @Composable
    fun KeyBtn(
        label: String,
        vkCode: Int,
        modifier: Modifier = Modifier,
    ) {
        Box(
            modifier = modifier
                .height(36.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(8.dp),
                )
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    RoundedCornerShape(8.dp),
                )
                .clickable { fireKey(vkCode) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
        }
    }

    @Composable
    fun KeyboardPanel() {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 第 1 行:粘滞修饰键
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StickyModBtn("Ctrl", VK.CONTROL, Modifier.weight(1f))
                StickyModBtn("Shift", VK.SHIFT, Modifier.weight(1f))
                StickyModBtn("Alt", VK.MENU, Modifier.weight(1f))
                StickyModBtn("Win", VK.LWIN, Modifier.weight(1f))
            }
            // 第 2 行:常用快捷组合(Ctrl 系列)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ComboBtn("复制", Modifier.weight(1f), VK.CONTROL, VK.C)
                ComboBtn("粘贴", Modifier.weight(1f), VK.CONTROL, VK.V)
                ComboBtn("剪切", Modifier.weight(1f), VK.CONTROL, VK.X)
                ComboBtn("撤销", Modifier.weight(1f), VK.CONTROL, VK.Z)
                ComboBtn("全选", Modifier.weight(1f), VK.CONTROL, VK.A)
                ComboBtn("保存", Modifier.weight(1f), VK.CONTROL, VK.S)
            }
            // 第 3 行:系统快捷键
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ComboBtn("⇄Tab", Modifier.weight(1f), VK.MENU, VK.TAB)
                ComboBtn("Win", Modifier.weight(1f), VK.LWIN)
                ComboBtn("AltF4", Modifier.weight(1f), VK.MENU, VK.F4)
                ComboBtn("刷新", Modifier.weight(1f), VK.CONTROL, VK.F5)
                ComboBtn("截屏", Modifier.weight(1f), VK.SNAPSHOT)
            }
            // 第 4 行:功能键 F1-F12(横向滚动)
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item { KeyBtn("F1", VK.F1, Modifier.width(48.dp)) }
                item { KeyBtn("F2", VK.F2, Modifier.width(48.dp)) }
                item { KeyBtn("F3", VK.F3, Modifier.width(48.dp)) }
                item { KeyBtn("F4", VK.F4, Modifier.width(48.dp)) }
                item { KeyBtn("F5", VK.F5, Modifier.width(48.dp)) }
                item { KeyBtn("F6", VK.F6, Modifier.width(48.dp)) }
                item { KeyBtn("F7", VK.F7, Modifier.width(48.dp)) }
                item { KeyBtn("F8", VK.F8, Modifier.width(48.dp)) }
                item { KeyBtn("F9", VK.F9, Modifier.width(48.dp)) }
                item { KeyBtn("F10", VK.F10, Modifier.width(48.dp)) }
                item { KeyBtn("F11", VK.F11, Modifier.width(48.dp)) }
                item { KeyBtn("F12", VK.F12, Modifier.width(48.dp)) }
            }
            // 第 5 行:方向键 + 编辑键
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                KeyBtn("←", VK.LEFT, Modifier.weight(1f))
                KeyBtn("↑", VK.UP, Modifier.weight(1f))
                KeyBtn("↓", VK.DOWN, Modifier.weight(1f))
                KeyBtn("→", VK.RIGHT, Modifier.weight(1f))
                KeyBtn("Enter", VK.RETURN, Modifier.weight(1.4f))
                KeyBtn("Esc", VK.ESCAPE, Modifier.weight(1f))
                KeyBtn("Tab", VK.TAB, Modifier.weight(1f))
            }
            // 第 6 行:编辑键
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                KeyBtn("Space", VK.SPACE, Modifier.weight(1.4f))
                KeyBtn("Back", VK.BACK, Modifier.weight(1f))
                KeyBtn("Del", VK.DELETE, Modifier.weight(1f))
                KeyBtn("Home", VK.HOME, Modifier.weight(1f))
                KeyBtn("End", VK.END, Modifier.weight(1f))
                KeyBtn("PgUp", VK.PRIOR, Modifier.weight(1f))
                KeyBtn("PgDn", VK.NEXT, Modifier.weight(1f))
            }
        }
    }

    if (isLandscape) {
        // 横屏:触控区域占主导,右侧固定宽度控制面板
        Row(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .clickable { onDisconnect() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.ArrowBack, "返回", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusBar()
                }
                Spacer(Modifier.height(6.dp))
                TouchArea(modifier = Modifier.weight(1f).fillMaxWidth())
            }
            Spacer(Modifier.width(8.dp))
            Column(
                modifier = Modifier.width(240.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                VoicePanel()
                MouseButtonsBar()
                KeyboardPanel()
                // 断开按钮
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .padding(horizontal = 12.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp),
                        )
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { onDisconnect() },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Logout, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text("断开", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    } else {
        // 竖屏:自上而下 — 状态栏 → 操作栏 → 触控区域 → 底部抽屉
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            StatusBar()
            Spacer(Modifier.height(6.dp))
            ActionBar()
            Spacer(Modifier.height(8.dp))
            // 触控区域占满剩余空间
            TouchArea(modifier = Modifier.weight(1f).fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            // 底部抽屉(可展开/收起)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = if (drawerExpanded) 4.dp else 0.dp,
                ),
            ) {
                Column {
                    DrawerHandle()
                    AnimatedVisibility(
                        visible = drawerExpanded,
                        enter = expandVertically(animationSpec = tween(350)),
                        exit = shrinkVertically(animationSpec = tween(350)),
                    ) {
                        Column(
                            modifier = Modifier.padding(bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            VoicePanel()
                            MouseButtonsBar()
                            KeyboardPanel()
                            // 断开按钮
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .padding(horizontal = 12.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(8.dp),
                                    )
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
                                        RoundedCornerShape(8.dp),
                                    )
                                    .clickable { onDisconnect() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Logout, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                    Spacer(Modifier.width(6.dp))
                                    Text("断开", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 鼠标按键按钮(支持按下/抬起,实现按住拖拽等场景)
 * 长按显示功能泡泡提示(由 showTooltips 控制)
 */
@Composable
private fun MouseBtn(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    buttonMask: Int,
    modifier: Modifier,
    tooltipText: String,
    onTooltip: (String) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }

    val bgColor = if (pressed) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val fgColor = if (pressed) Color.White
    else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .background(bgColor, RoundedCornerShape(8.dp))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                RoundedCornerShape(8.dp),
            )
            .pointerInput(buttonMask) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        NativeBridge.sendButtonDown(buttonMask)
                        try {
                            tryAwaitRelease()
                        } finally {
                            NativeBridge.sendButtonUp(buttonMask)
                            pressed = false
                        }
                    },
                    onLongPress = { onTooltip(tooltipText) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null, Modifier.size(16.dp), tint = fgColor)
            Text(label, fontSize = 12.sp, color = fgColor, fontWeight = FontWeight.Medium)
        }
    }
}
