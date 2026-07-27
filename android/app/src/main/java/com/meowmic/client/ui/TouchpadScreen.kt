package com.meowmic.client.ui

import android.content.res.Configuration
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
    var muteSpeaker by remember { mutableStateOf(false) }
    var showBottomButtons by remember { mutableStateOf(true) }
    var showTooltips by remember { mutableStateOf(false) }  // 泡泡提示开关,默认关闭
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

    LaunchedEffect(configuration.orientation, configuration.screenWidthDp, configuration.screenHeightDp) {
        val width = configuration.screenWidthDp
        val height = configuration.screenHeightDp
        val rotation = if (width > height) 90 else 0
        vm.setScreenRotation(rotation)
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

    // 顶部状态条(紧凑)
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
                    Text("已连接 · $serverAddr", fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text("T:$touchSent  A:$audioSent  $touchMode·${pointerCount}指", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (isLandscape) "横屏" else "竖屏", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    // 触控区域
    @Composable
    fun TouchArea(modifier: Modifier) {
        Box(
            modifier = modifier
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .pointerInteropFilter { event ->
                    pointerCount = event.pointerCount
                    touchMode = when {
                        event.pointerCount >= 3 -> "三指"
                        event.pointerCount == 2 -> "双指"
                        else -> when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> "按下"
                            MotionEvent.ACTION_MOVE -> "移动"
                            MotionEvent.ACTION_UP -> "抬起"
                            else -> "移动"
                        }
                    }
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
                    "滑动移动·轻触左键·双指右键·双指滑动滚动",
                    fontSize = if (isLandscape) 10.sp else 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
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
        // idle → 按下 → recording → 松开 → idle
        // idle → 按下 → recording → 滑动超过阈值 → locked
        // locked → 点击 → idle(取消锁定)
        var btnState by remember { mutableStateOf("idle") }
        val density = LocalDensity.current
        val lockThresholdPx = with(density) { 60.dp.toPx() }
        val scope = rememberCoroutineScope()

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
     * 这是对原来"音频源切换"的整合升级,不再切换整个语音控制面板。
     */
    @Composable
    fun AudioPanel() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LibraryMusic, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text("音频面板", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    // 音频源切换:简化为切换图标
                    FilterChip(
                        selected = currentAudioMode == AudioInputManager.InputMode.MICROPHONE,
                        onClick = {
                            vm.switchAudioMode(AudioInputManager.InputMode.MICROPHONE)
                            showToast("切换到麦克风源")
                        },
                        label = { Text("麦克风", fontSize = 10.sp) },
                        leadingIcon = { Icon(Icons.Default.Mic, null, Modifier.size(12.dp)) },
                        modifier = Modifier.scale(0.85f),
                    )
                    Spacer(Modifier.width(4.dp))
                    FilterChip(
                        selected = currentAudioMode == AudioInputManager.InputMode.MUSIC_FILE,
                        onClick = {
                            // 直接打开文件选择器(加入历史)
                            vm.cancelEditQuickSlot()
                            filePickerLauncher.launch(arrayOf("audio/*"))
                            showToast("选择音频文件")
                        },
                        label = { Text("音频", fontSize = 10.sp) },
                        leadingIcon = { Icon(Icons.Default.MusicNote, null, Modifier.size(12.dp)) },
                        modifier = Modifier.scale(0.85f),
                    )
                    if (currentAudioMode == AudioInputManager.InputMode.MUSIC_FILE) {
                        IconButton(
                            onClick = {
                                vm.stopMusicPlayback()
                                showToast("停止音频播放")
                            },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(Icons.Default.Stop, "停止", Modifier.size(16.dp))
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 历史区
                Text("历史", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                Spacer(Modifier.height(4.dp))
                if (audioHistory.isEmpty()) {
                    Text(
                        "暂无历史·点击「音频」选择文件",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(audioHistory) { item ->
                            AssistChip(
                                onClick = {
                                    scope.launch {
                                        vm.playMusicFile(item.path)
                                        showToast("播放:${item.name}")
                                    }
                                },
                                label = { Text(item.name, fontSize = 10.sp, maxLines = 1) },
                                leadingIcon = { Icon(Icons.Default.History, null, Modifier.size(12.dp)) },
                                modifier = Modifier.scale(0.9f),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 快捷 slot 区:4 列 x 2 行 = 8 个
                Text("快捷", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                Spacer(Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (row in 0 until 2) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
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
    }

    // 语音控制面板:Tab 切换 麦克风 / 音频
    @Composable
    fun VoicePanel() {
        var selectedTab by remember { mutableStateOf(0) }  // 0=麦克风, 1=音频
        val micEnabled by vm.micEnabled.collectAsState()
        val muteSpk by vm.muteSpeaker.collectAsState()
        val currentMode by vm.currentAudioMode.collectAsState()

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🎙️ 语音", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text("提示", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Switch(
                        checked = showTooltips,
                        onCheckedChange = { showTooltips = it },
                        modifier = Modifier.scale(0.7f),
                    )
                }
                Spacer(Modifier.height(8.dp))

                // Tab 行
                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.fillMaxWidth(),
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = {
                            selectedTab = 0
                            // 切到麦克风 Tab,停止音频文件播放
                            if (currentMode == AudioInputManager.InputMode.MUSIC_FILE) {
                                vm.stopMusicPlayback()
                            }
                            showToast("麦克风 Tab")
                        },
                        text = { Text("麦克风", fontSize = 11.sp) },
                        icon = { Icon(Icons.Default.Mic, null, Modifier.size(14.dp)) },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = {
                            selectedTab = 1
                            // 切到音频 Tab,停止麦克风
                            if (micEnabled) vm.setMicEnabled(false)
                            showToast("音频 Tab")
                        },
                        text = { Text("音频", fontSize = 11.sp) },
                        icon = { Icon(Icons.Default.MusicNote, null, Modifier.size(14.dp)) },
                    )
                }

                Spacer(Modifier.height(8.dp))

                when (selectedTab) {
                    0 -> {
                        // 麦克风 Tab:PTT 长按说话 + 静音外放
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PttButton(modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    val newMuted = !muteSpk
                                    vm.setMuteSpeaker(newMuted)
                                    showToast(if (newMuted) "已静音外放" else "已开启外放")
                                },
                                modifier = Modifier.size(48.dp),
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
    }

    // 底部鼠标按键(支持按下/抬起,可隐藏,长按泡泡提示)
    @Composable
    fun MouseButtonsBar() {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MouseBtn("左键", BTN_LEFT, Modifier.weight(1f).height(44.dp), "鼠标左键:单击/按住拖拽") { showToast(it) }
            MouseBtn("中键", BTN_MIDDLE, Modifier.weight(1f).height(44.dp), "鼠标中键:滚轮按键") { showToast(it) }
            MouseBtn("右键", BTN_RIGHT, Modifier.weight(1f).height(44.dp), "鼠标右键:上下文菜单") { showToast(it) }
            IconButton(
                onClick = { showBottomButtons = false },
                modifier = Modifier.size(44.dp),
            ) {
                Icon(Icons.Default.ExpandMore, "隐藏按键栏", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (isLandscape) {
        // 横屏:触控区域占主导,右侧固定宽度控制面板
        Row(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                StatusBar()
                Spacer(Modifier.height(6.dp))
                TouchArea(modifier = Modifier.weight(1f).fillMaxWidth())
                AnimatedVisibility(
                    visible = !showBottomButtons,
                    enter = fadeIn(), exit = fadeOut(),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    TextButton(onClick = { showBottomButtons = true }) {
                        Icon(Icons.Default.ExpandLess, null, Modifier.size(16.dp))
                        Text("显示按键", fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(
                modifier = Modifier.width(240.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                VoicePanel()
                if (showBottomButtons) {
                    MouseButtonsBar()
                }
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("断开", fontSize = 12.sp) }
            }
        }
    } else {
        // 竖屏:自上而下
        Column(modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
            StatusBar()
            Spacer(Modifier.height(8.dp))
            TouchArea(modifier = Modifier.weight(1f).fillMaxWidth().heightIn(min = 200.dp))
            Spacer(Modifier.height(8.dp))
            VoicePanel()
            Spacer(Modifier.height(8.dp))
            if (showBottomButtons) {
                MouseButtonsBar()
            } else {
                TextButton(
                    onClick = { showBottomButtons = true },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Icon(Icons.Default.ExpandLess, null, Modifier.size(16.dp))
                    Text("显示按键", fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth().height(40.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("断开", fontSize = 12.sp) }
        }
    }
}

/**
 * 鼠标按键按钮(支持按下/抬起,实现按住拖拽等场景)
 * 长按显示功能泡泡提示(由 showTooltips 控制)
 *
 * 使用 Box + detectTapGestures.onPress 实现可靠的按下/抬起事件,
 * 避免与 Button 自身的点击处理冲突。
 */
@Composable
private fun MouseBtn(
    label: String,
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
            .background(bgColor, RoundedCornerShape(10.dp))
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
        Text(label, fontSize = 12.sp, color = fgColor, fontWeight = FontWeight.Medium)
    }
}
