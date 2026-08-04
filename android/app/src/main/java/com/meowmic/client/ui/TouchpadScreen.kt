package com.meowmic.client.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs

// 按钮掩码常量(与 server/protocol 对应)
private const val BTN_LEFT = 0x01
private const val BTN_RIGHT = 0x02
private const val BTN_MIDDLE = 0x04

// ============ Windows VK code 常量(用于模拟键盘) ============
private object VK {
    // 修饰键 L/R 专用 code(借鉴 moonlight-android,用于粘滞/锁定状态)
    // Windows 对部分快捷键区分左右(如右 Shift 长按=筛选键),用专用 code 行为更精确
    const val LCONTROL = 0xA2
    const val RCONTROL = 0xA3
    const val LSHIFT = 0xA0
    const val RSHIFT = 0xA1
    const val LMENU = 0xA4      // Left Alt
    const val RMENU = 0xA5      // Right Alt
    const val LWIN = 0x5B
    // 字母(完整 A-Z,用于虚拟键盘)
    const val A = 0x41; const val B = 0x42; const val C = 0x43; const val D = 0x44
    const val E = 0x45; const val F = 0x46; const val G = 0x47; const val H = 0x48
    const val I = 0x49; const val J = 0x4A; const val K = 0x4B; const val L = 0x4C
    const val M = 0x4D; const val N = 0x4E; const val O = 0x4F; const val P = 0x50
    const val Q = 0x51; const val R = 0x52; const val S = 0x53; const val T = 0x54
    const val U = 0x55; const val V = 0x56; const val W = 0x57; const val X = 0x58
    const val Y = 0x59; const val Z = 0x5A
    // 数字行(0-9 + 符号)
    const val D0 = 0x30; const val D1 = 0x31; const val D2 = 0x32; const val D3 = 0x33
    const val D4 = 0x34; const val D5 = 0x35; const val D6 = 0x36; const val D7 = 0x37
    const val D8 = 0x38; const val D9 = 0x39
    const val OEM_3 = 0xC0    // `
    const val OEM_MINUS = 0xBD // -
    const val OEM_PLUS = 0xBB  // =
    const val OEM_4 = 0xDB    // [
    const val OEM_6 = 0xDD    // ]
    const val OEM_5 = 0xDC    // \
    const val OEM_1 = 0xBA    // ;
    const val OEM_7 = 0xDE    // '
    const val OEM_COMMA = 0xBC // ,
    const val OEM_PERIOD = 0xBE // .
    const val OEM_2 = 0xBF    // /
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
    const val SCROLL = 0x91   // ScrollLock
    const val PAUSE = 0x13    // Pause Break
    // Fn / Menu 没有独立 VK code,用占位值避免误触
    const val FN = 0x7F       // F24(实际不可用,仅占位)
    const val MENU_KEY = 0x5D // AppsKey
}

/** 小尺寸图标按钮(对齐设计稿顶栏 28dp 圆角方块) */
@Composable
private fun IconButtonSmall(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
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
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 8 个可编辑快捷键槽位(对齐设计稿)。
 * - 非编辑模式:点击槽位 → 解析为 VK 序列并下发;有按下视觉反馈
 * - 编辑模式:点击槽位 → 显示输入框,可输入 "Ctrl+C" 等组合,完成时持久化
 * - 空槽位显示 "+" 图标,虚线边框
 */
@Composable
private fun QuickKeySlots(
    vm: MeowMicViewModel,
    showToast: (String) -> Unit,
) {
    val quickKeys by vm.quickKeys.collectAsState()
    var editMode by remember { mutableStateOf(false) }
    // 编辑模式下每个槽位的临时输入值
    val editValues = remember { mutableStateListOf<String>().apply { addAll(quickKeys) } }
    // 进入编辑模式时同步一次
    LaunchedEffect(editMode) {
        if (editMode) {
            editValues.clear()
            editValues.addAll(quickKeys)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 标题
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Default.Keyboard, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
            Text("快捷键", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
        // 编辑/完成按钮
        Box(
            modifier = Modifier
                .height(20.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                .clickable {
                    if (editMode) {
                        // 退出编辑模式:批量保存所有槽位
                        for (i in editValues.indices) {
                            vm.setQuickKey(i, editValues[i])
                        }
                    }
                    editMode = !editMode
                }
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (editMode) Icons.Default.Check else Icons.Default.Edit,
                    null, Modifier.size(10.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    if (editMode) "完成" else "编辑",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    // 槽位网格 4 列 x 2 行
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (row in 0 until 2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                for (col in 0 until 4) {
                    val index = row * 4 + col
                    val value = if (editMode) editValues.getOrNull(index) ?: ""
                    else quickKeys.getOrNull(index) ?: ""
                    QuickKeySlotCell(
                        value = value,
                        editMode = editMode,
                        isLandscape = false,
                        modifier = Modifier.weight(1f),
                        onValueChange = { v ->
                            if (editMode && index < editValues.size) {
                                editValues[index] = v
                            }
                        },
                        onCommit = {
                            if (!editMode) {
                                // 非编辑模式:触发组合键
                                val ok = vm.fireQuickKey(index)
                                if (ok) vm.playFeedbackSound() else showToast("快捷键未设置或格式无效")
                            }
                        },
                        onCommitEdit = {
                            // 完成编辑:写入持久化
                            val v = editValues.getOrNull(index) ?: ""
                            vm.setQuickKey(index, v)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickKeySlotCell(
    value: String,
    editMode: Boolean,
    isLandscape: Boolean,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
    onCommit: () -> Unit,
    onCommitEdit: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val isSet = value.isNotBlank()
    val borderColor = if (editMode) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val borderStyle = if (isSet || editMode) BorderStroke(1.dp, borderColor)
    else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    // 按下反馈色
    val bgColor = when {
        pressed -> MaterialTheme.colorScheme.primary
        editMode -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fgColor = when {
        pressed -> Color.White
        editMode -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .height(if (isLandscape) 24.dp else 30.dp)
            .background(bgColor, RoundedCornerShape(6.dp))
            .border(borderStyle, RoundedCornerShape(6.dp))
            .then(
                if (editMode) Modifier
                else Modifier.clickable {
                    pressed = true
                    onCommit()
                    // 短暂高亮反馈
                    scope.launch {
                        delay(200)
                        pressed = false
                    }
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (editMode) {
            // 编辑模式:输入框
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 10.sp,
                    color = fgColor,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onCommitEdit() }),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            )
        } else {
            if (isSet) {
                Text(
                    value,
                    fontSize = 10.sp,
                    color = fgColor,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Icon(Icons.Default.Add, null, Modifier.size(12.dp), tint = fgColor.copy(alpha = 0.6f))
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TouchpadScreen(
    vm: MeowMicViewModel,
    onDisconnect: () -> Unit,
    onOpenLauncher: () -> Unit = {},
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
    val soundFeedback by vm.soundFeedback.collectAsState()

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
    // showBack/showRotate:横屏布局自己已渲染这些按钮,调用方可设为 false 避免重复
    @Composable
    fun ActionBar(showBack: Boolean = true, showRotate: Boolean = true) {
        var menuExpanded by remember { mutableStateOf(false) }
        val micEnabled by vm.micEnabled.collectAsState()
        val muteSpk by vm.muteSpeaker.collectAsState()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 返回按钮
            if (showBack) {
                IconButtonSmall(
                    icon = Icons.Default.ArrowBack,
                    contentDescription = "返回",
                    onClick = onDisconnect,
                )
            }

            if (showBack || showRotate) Spacer(Modifier.weight(1f))

            // 独立旋转按钮(对齐设计稿横屏操作栏)
            if (showRotate) {
                IconButtonSmall(
                    icon = Icons.Default.ScreenRotation,
                    contentDescription = if (isLandscape) "切换竖屏" else "切换横屏",
                    onClick = { if (isLandscape) requestPortrait() else requestLandscape() },
                )
            }

            // 快捷启动按钮(进入应用库页面)
            IconButtonSmall(
                icon = Icons.Default.Apps,
                contentDescription = "快捷启动",
                onClick = onOpenLauncher,
            )

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
                    // 横屏切换(菜单项保留,与独立按钮等价)
                    DropdownMenuItem(
                        text = { Text(if (isLandscape) "竖屏" else "横屏", fontSize = 12.sp) },
                        onClick = {
                            if (isLandscape) requestPortrait() else requestLandscape()
                            menuExpanded = false
                        },
                        leadingIcon = { Icon(Icons.Default.ScreenRotation, null, Modifier.size(16.dp)) },
                    )
                    HorizontalDivider()
                    // 麦克风开关(常开推流)
                    DropdownMenuItem(
                        text = { Text("麦克风", fontSize = 12.sp) },
                        onClick = { vm.setMicEnabled(!micEnabled) },
                        leadingIcon = { Icon(Icons.Default.Mic, null, Modifier.size(16.dp)) },
                        trailingIcon = {
                            Switch(
                                checked = micEnabled,
                                onCheckedChange = { vm.setMicEnabled(it) },
                                modifier = Modifier.scale(0.7f),
                            )
                        },
                    )
                    // 扬声器开关(静音 PC 扬声器)
                    DropdownMenuItem(
                        text = { Text("扬声器", fontSize = 12.sp) },
                        onClick = { vm.setMuteSpeaker(!muteSpk) },
                        leadingIcon = {
                            Icon(
                                if (muteSpk) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                null, Modifier.size(16.dp),
                            )
                        },
                        trailingIcon = {
                            Switch(
                                checked = !muteSpk,
                                onCheckedChange = { vm.setMuteSpeaker(!it) },
                                modifier = Modifier.scale(0.7f),
                            )
                        },
                    )
                    // 操作反馈音效开关(对齐设计稿"提示音"语义)
                    DropdownMenuItem(
                        text = { Text("提示音", fontSize = 12.sp) },
                        onClick = { vm.setSoundFeedback(!soundFeedback) },
                        leadingIcon = { Icon(Icons.Default.Notifications, null, Modifier.size(16.dp)) },
                        trailingIcon = {
                            Switch(
                                checked = soundFeedback,
                                onCheckedChange = { vm.setSoundFeedback(it) },
                                modifier = Modifier.scale(0.7f),
                            )
                        },
                    )
                    // 操作提示 Toast 开关(独立于提示音)
                    DropdownMenuItem(
                        text = { Text("操作提示", fontSize = 12.sp) },
                        onClick = { showTooltips = !showTooltips },
                        leadingIcon = { Icon(Icons.Default.ChatBubbleOutline, null, Modifier.size(16.dp)) },
                        trailingIcon = {
                            Switch(
                                checked = showTooltips,
                                onCheckedChange = { showTooltips = it },
                                modifier = Modifier.scale(0.7f),
                            )
                        },
                    )
                    HorizontalDivider()
                    // 断开连接
                    DropdownMenuItem(
                        text = { Text("断开连接", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            onDisconnect()
                        },
                        leadingIcon = { Icon(Icons.Default.Logout, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error) },
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

    // ─────────── 麦克风状态指示器(对齐设计稿 mm-mic-orb) ───────────
    // 三态:关闭(灰) / 常开(品牌色+光晕) / 录音中(红色+光晕+波形)
    @Composable
    fun MicOrb(modifier: Modifier = Modifier) {
        val micEnabled by vm.micEnabled.collectAsState()
        val isRecording by vm.isRecording.collectAsState()

        val active = micEnabled || isRecording
        val bgColor = when {
            isRecording -> MaterialTheme.colorScheme.error
            micEnabled -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
        val fgColor = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
        val orbSize = if (isLandscape) 44.dp else 56.dp
        val iconSize = if (isLandscape) 18.dp else 22.dp

        // 光晕缩放与透明度动画(开启/录音时)
        val haloAnim = rememberInfiniteTransition(label = "mic-halo")
        val haloScale by haloAnim.animateFloat(
            initialValue = 1f,
            targetValue = 1.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = { it }),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "halo-scale",
        )
        val haloAlpha by haloAnim.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = { it }),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "halo-alpha",
        )

        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            // 光晕(仅激活态显示)
            if (active) {
                Box(
                    modifier = Modifier
                        .size(orbSize * haloScale)
                        .background(bgColor.copy(alpha = haloAlpha), CircleShape),
                )
            }
            // 主圆
            Box(
                modifier = Modifier
                    .size(orbSize)
                    .background(bgColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                    null,
                    Modifier.size(iconSize),
                    tint = fgColor,
                )
            }
        }
    }

    // ─────────── 脉冲扩散圆环(对齐设计稿 mm-rec-pulse) ───────────
    @Composable
    fun PulseRing(
        modifier: Modifier = Modifier,
        color: Color,
        size: androidx.compose.ui.unit.Dp,
    ) {
        val anim = rememberInfiniteTransition(label = "pulse")
        val scale by anim.animateFloat(
            initialValue = 1f,
            targetValue = 1.8f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200),
                repeatMode = RepeatMode.Restart,
            ),
            label = "pulse-scale",
        )
        val alpha by anim.animateFloat(
            initialValue = 0.4f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200),
                repeatMode = RepeatMode.Restart,
            ),
            label = "pulse-alpha",
        )
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(size)
                    .scale(scale)
                    .background(color.copy(alpha = alpha), CircleShape),
            )
        }
    }

    // ─────────── 录音波形条(对齐设计稿 mm-rec-bars) ───────────
    // 5 条不等高、错相位的柱形,录音时持续抖动
    @Composable
    fun WaveBars(
        modifier: Modifier = Modifier,
        color: Color,
        active: Boolean,
    ) {
        // 设计稿:5 条,相对高度 + 错相位
        val heights = listOf(3f, 8f, 12f, 7f, 5f)
        val phases = listOf(0, 120, 240, 180, 60)  // ms

        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            heights.forEachIndexed { i, h ->
                val anim = rememberInfiniteTransition(label = "bar-$i")
                val animatedH by anim.animateFloat(
                    initialValue = 3f,
                    targetValue = if (active) h else 3f,
                    animationSpec = if (active) {
                        infiniteRepeatable(
                            animation = tween(600, easing = { it }),
                            repeatMode = RepeatMode.Reverse,
                            initialStartOffset = StartOffset(phases[i]),
                        )
                    } else {
                        tween(200)
                    },
                    label = "bar-h-$i",
                )
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(animatedH.dp)
                        .background(color, RoundedCornerShape(50)),
                )
            }
        }
    }

    // ─────────── PTT 长按说话按钮(整合 PPT/实时模式) ───────────
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
        val micEnabled by vm.micEnabled.collectAsState()
        // 麦克风常开时禁用 PTT(避免 MediaRecorder 和 AudioCapture 同时占用麦克风)
        val disabled = micEnabled

        // 用 mutableStateOf 包装当前状态供 pointerInput 读取,避免 key 变化重启
        val stateRef = remember { mutableStateOf("idle") }

        // 实时计时器:基于 vm.isRecording 信号,录音/锁定期间持续累计
        // (recording → locked 切换不会重置计时器,因为 vm.isRecording 保持 true)
        var recSeconds by remember { mutableStateOf(0) }
        val isVmRecording by vm.isRecording.collectAsState()
        LaunchedEffect(isVmRecording) {
            if (isVmRecording) {
                recSeconds = 0
                while (true) {
                    delay(1000)
                    recSeconds++
                }
            }
        }

        val bgColor = when {
            disabled -> MaterialTheme.colorScheme.surfaceVariant
            btnState == "recording" -> MaterialTheme.colorScheme.error
            btnState == "locked" -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        }
        val fgColor = if (disabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else Color.White
        val isActive = btnState == "recording" || btnState == "locked"

        // 计时器格式化
        fun formatTime(s: Int): String {
            val m = s / 60
            val sec = s % 60
            return "$m:${if (sec < 10) "0$sec" else "$sec"}"
        }
        val text = when {
            disabled -> "麦克风常开中"
            btnState == "recording" -> "录音中 ${formatTime(recSeconds)}  松开停止"
            btnState == "locked" -> "已锁定 ${formatTime(recSeconds)}  点取消"
            else -> "按住录音"
        }

        Box(
            modifier = modifier
                .height(64.dp)
                .background(bgColor, RoundedCornerShape(8.dp))
                .pointerInput(disabled) {
                    if (disabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val pressX = down.position.x
                        val pressY = down.position.y
                        val currentState = stateRef.value

                        when (currentState) {
                            "idle" -> {
                                btnState = "recording"
                                stateRef.value = "recording"
                                vm.startRecording()
                                vm.playFeedbackSound()
                                showToast("开始录音(松开结束,上滑锁定)")
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
                                            vm.stopRecording()
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
                                        vm.cancelRecording()
                                        showToast("已取消录音")
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
                                        vm.stopRecording()
                                        break
                                    }
                                }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 左侧:脉冲圆环 + 主按钮(图标)
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isActive) {
                        PulseRing(
                            color = bgColor,
                            size = 40.dp,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(Color.White.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (isActive) Icons.Default.Stop else Icons.Default.Mic,
                            null,
                            Modifier.size(18.dp),
                            tint = fgColor,
                        )
                    }
                }
                // 中间:文字 + 波形条
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(text, fontSize = 12.sp, color = fgColor, fontWeight = FontWeight.Medium)
                    WaveBars(
                        color = fgColor.copy(alpha = 0.8f),
                        active = isActive,
                    )
                }
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
            // ── 音频源切换:录音 / 音频文件(对齐设计稿双 pill) ──
            // 当前模式高亮:录音=品牌填充,音频文件=品牌填充
            val micActive = currentAudioMode == AudioInputManager.InputMode.MICROPHONE
            val fileActive = currentAudioMode == AudioInputManager.InputMode.MUSIC_FILE
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 录音 pill
                Box(
                    modifier = Modifier
                        .height(28.dp)
                        .background(
                            if (micActive) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(50),
                        )
                        .border(
                            1.dp,
                            if (micActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(50),
                        )
                        .clickable {
                            vm.switchAudioMode(AudioInputManager.InputMode.MICROPHONE)
                            showToast("切换到录音模式")
                        }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Mic,
                            null,
                            Modifier.size(12.dp),
                            tint = if (micActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "录音",
                            fontSize = 11.sp,
                            color = if (micActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // 音频文件 pill
                Box(
                    modifier = Modifier
                        .height(28.dp)
                        .background(
                            if (fileActive) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(50),
                        )
                        .border(
                            1.dp,
                            if (fileActive) MaterialTheme.colorScheme.primary
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
                        Icon(
                            Icons.Default.MusicNote,
                            null,
                            Modifier.size(12.dp),
                            tint = if (fileActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "音频文件",
                            fontSize = 11.sp,
                            color = if (fileActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // 停止按钮(MUSIC_FILE 模式下显示)
                if (currentAudioMode == AudioInputManager.InputMode.MUSIC_FILE) {
                    Spacer(Modifier.weight(1f))
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

    // 底部鼠标按键(支持按下/抬起,带图标)
    @Composable
    fun MouseButtonsBar() {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MouseBtn("左键", Icons.Default.Mouse, BTN_LEFT, Modifier.weight(1f).height(44.dp), "鼠标左键:单击/按住拖拽", { showToast(it) }, { vm.playFeedbackSound() })
            MouseBtn("中键", Icons.Default.Mouse, BTN_MIDDLE, Modifier.weight(1f).height(44.dp), "鼠标中键:滚轮按键", { showToast(it) }, { vm.playFeedbackSound() })
            MouseBtn("右键", Icons.Default.Menu, BTN_RIGHT, Modifier.weight(1f).height(44.dp), "鼠标右键:上下文菜单", { showToast(it) }, { vm.playFeedbackSound() })
        }
    }

    // ============ 键盘面板(快捷键 + 三态修饰键) ============
    // 修饰键三态(借鉴 Windows 粘滞键锁定语义):
    //   0 / 不存在 = 未激活
    //   1 = 粘滞: 与下一个普通键组合后自动清除(一次性)
    //   2 = 锁定: 持续生效,连按多个普通键都带此修饰键,再次点击解锁
    // 锁定模式支持「长按 Ctrl+Win 期间连按 ←/→ 切虚拟桌面」等场景。
    val stickyMods = remember { mutableStateMapOf<Int, Int>() }

    // 取所有激活(粘滞或锁定)的修饰键,按 VK code 升序固定下发顺序
    fun activeMods(): List<Int> =
        stickyMods.entries.filter { it.value > 0 }.map { it.key }.sorted()

    // 清除所有粘滞(1)状态,保留锁定(2)状态
    fun clearStickyOnly() {
        val toRemove = stickyMods.entries.filter { it.value == 1 }.map { it.key }
        for (k in toRemove) stickyMods.remove(k)
    }

    fun fireKey(keyCode: Int) {
        val activeMods = activeMods()
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
        // 仅在全部成功时:清除粘滞态,保留锁定态;失败时全保留以允许重试
        if (allSuccess) {
            clearStickyOnly()
            vm.playFeedbackSound()
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun StickyModBtn(label: String, vkCode: Int, modifier: Modifier = Modifier) {
        // state: 0=未激活, 1=粘滞, 2=锁定
        val state = stickyMods[vkCode] ?: 0
        val bgColor = when (state) {
            2 -> MaterialTheme.colorScheme.tertiary         // 锁定:区别色
            1 -> MaterialTheme.colorScheme.primary          // 粘滞:主色
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
        val fgColor = if (state > 0) Color.White
            else MaterialTheme.colorScheme.onSurfaceVariant
        val borderColor = when (state) {
            2 -> MaterialTheme.colorScheme.tertiary
            1 -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        }
        Box(
            modifier = modifier
                .height(36.dp)
                .background(bgColor, RoundedCornerShape(8.dp))
                .border(
                    1.dp,
                    borderColor,
                    RoundedCornerShape(8.dp),
                )
                .combinedClickable(
                    onClick = {
                        // 单击: 未激活→粘滞(1), 粘滞→取消, 锁定→解锁
                        stickyMods[vkCode] = when (state) {
                            0 -> 1
                            1 -> 0
                            else -> 0
                        }
                    },
                    onDoubleClick = {
                        // 双击: 进入锁定(2),支持连按多个主键
                        stickyMods[vkCode] = 2
                    },
                    onLongClick = {
                        // 长按: 清零(取消粘滞或解锁)
                        stickyMods.remove(vkCode)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                // 锁定态追加 🔒 视觉提示
                text = if (state == 2) "$label 🔒" else label,
                fontSize = 11.sp,
                color = fgColor,
                fontWeight = if (state > 0) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }

    /**
     * 通用键位按钮(对齐设计稿 vkb-key):
     * - fn=true: 功能/修饰键,使用次级背景色 + 较小字号
     * - wide/xwide/space: 不同 flex 比例
     * - 点击发送对应 VK code,并清粘滞态
     */
    @Composable
    fun KeyBtn(
        label: String,
        vkCode: Int,
        modifier: Modifier = Modifier,
        fn: Boolean = false,
        small: Boolean = false,
    ) {
        val bgColor = if (fn) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        else MaterialTheme.colorScheme.surfaceVariant
        val fontSize = when {
            small -> 9.sp
            fn -> 10.sp
            else -> 11.sp
        }
        Box(
            modifier = modifier
                .height(if (isLandscape) 24.dp else 30.dp)
                .background(bgColor, RoundedCornerShape(6.dp))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    RoundedCornerShape(6.dp),
                )
                .clickable { fireKey(vkCode) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                fontSize = fontSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }

    /** 占位间隔(对齐设计稿 vkb-gap) */
    @Composable
    fun KeyGap(modifier: Modifier = Modifier) {
        Spacer(modifier = modifier)
    }

    @Composable
    fun KeyboardPanel() {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // 第 1 行:三态修饰键(借鉴 moonlight-android 使用 L/R 专用 VK code)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StickyModBtn("Ctrl", VK.LCONTROL, Modifier.weight(1f))
                StickyModBtn("Shift", VK.LSHIFT, Modifier.weight(1f))
                StickyModBtn("Alt", VK.LMENU, Modifier.weight(1f))
                StickyModBtn("Win", VK.LWIN, Modifier.weight(1f))
            }
            // 第 2 行:功能键 Esc | F1-F4 | F5-F8 | F9-F12(对齐设计稿 vkb-row 功能键行)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                KeyBtn("Esc", VK.ESCAPE, Modifier.weight(1f), fn = true, small = true)
                KeyGap(Modifier.weight(0.4f))
                KeyBtn("F1", VK.F1, Modifier.weight(1f), fn = true, small = true)
                KeyBtn("F2", VK.F2, Modifier.weight(1f), fn = true, small = true)
                KeyBtn("F3", VK.F3, Modifier.weight(1f), fn = true, small = true)
                KeyBtn("F4", VK.F4, Modifier.weight(1f), fn = true, small = true)
                KeyGap(Modifier.weight(0.4f))
                KeyBtn("F5", VK.F5, Modifier.weight(1f), fn = true, small = true)
                KeyBtn("F6", VK.F6, Modifier.weight(1f), fn = true, small = true)
                KeyBtn("F7", VK.F7, Modifier.weight(1f), fn = true, small = true)
                KeyBtn("F8", VK.F8, Modifier.weight(1f), fn = true, small = true)
                KeyGap(Modifier.weight(0.4f))
                KeyBtn("F9", VK.F9, Modifier.weight(1f), fn = true, small = true)
                KeyBtn("F10", VK.F10, Modifier.weight(1f), fn = true, small = true)
                KeyBtn("F11", VK.F11, Modifier.weight(1f), fn = true, small = true)
                KeyBtn("F12", VK.F12, Modifier.weight(1f), fn = true, small = true)
            }
            // 第 3 行:数字行 ` 1-0 - = ⌫
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                KeyBtn("`", VK.OEM_3, Modifier.weight(1f))
                KeyBtn("1", VK.D1, Modifier.weight(1f))
                KeyBtn("2", VK.D2, Modifier.weight(1f))
                KeyBtn("3", VK.D3, Modifier.weight(1f))
                KeyBtn("4", VK.D4, Modifier.weight(1f))
                KeyBtn("5", VK.D5, Modifier.weight(1f))
                KeyBtn("6", VK.D6, Modifier.weight(1f))
                KeyBtn("7", VK.D7, Modifier.weight(1f))
                KeyBtn("8", VK.D8, Modifier.weight(1f))
                KeyBtn("9", VK.D9, Modifier.weight(1f))
                KeyBtn("0", VK.D0, Modifier.weight(1f))
                KeyBtn("-", VK.OEM_MINUS, Modifier.weight(1f))
                KeyBtn("=", VK.OEM_PLUS, Modifier.weight(1f))
                KeyBtn("⌫", VK.BACK, Modifier.weight(1.6f), fn = true)
            }
            // 第 4 行:QWERTY 行 Tab Q-P [ ] \
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                KeyBtn("Tab", VK.TAB, Modifier.weight(1.6f), fn = true)
                KeyBtn("Q", VK.Q, Modifier.weight(1f))
                KeyBtn("W", VK.W, Modifier.weight(1f))
                KeyBtn("E", VK.E, Modifier.weight(1f))
                KeyBtn("R", VK.R, Modifier.weight(1f))
                KeyBtn("T", VK.T, Modifier.weight(1f))
                KeyBtn("Y", VK.Y, Modifier.weight(1f))
                KeyBtn("U", VK.U, Modifier.weight(1f))
                KeyBtn("I", VK.I, Modifier.weight(1f))
                KeyBtn("O", VK.O, Modifier.weight(1f))
                KeyBtn("P", VK.P, Modifier.weight(1f))
                KeyBtn("[", VK.OEM_4, Modifier.weight(1f))
                KeyBtn("]", VK.OEM_6, Modifier.weight(1f))
                KeyBtn("\\", VK.OEM_5, Modifier.weight(1f))
            }
            // 第 5 行:ASDF 行 Caps A-L ; ' ↵
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                KeyBtn("Caps", VK.CAPITAL, Modifier.weight(2.2f), fn = true)
                KeyBtn("A", VK.A, Modifier.weight(1f))
                KeyBtn("S", VK.S, Modifier.weight(1f))
                KeyBtn("D", VK.D, Modifier.weight(1f))
                KeyBtn("F", VK.F, Modifier.weight(1f))
                KeyBtn("G", VK.G, Modifier.weight(1f))
                KeyBtn("H", VK.H, Modifier.weight(1f))
                KeyBtn("J", VK.J, Modifier.weight(1f))
                KeyBtn("K", VK.K, Modifier.weight(1f))
                KeyBtn("L", VK.L, Modifier.weight(1f))
                KeyBtn(";", VK.OEM_1, Modifier.weight(1f))
                KeyBtn("'", VK.OEM_7, Modifier.weight(1f))
                KeyBtn("↵", VK.RETURN, Modifier.weight(2.2f), fn = true)
            }
            // 第 6 行:ZXCV 行 ⇧ Z-/ ⇧
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                KeyBtn("⇧", VK.LSHIFT, Modifier.weight(2.2f), fn = true)
                KeyBtn("Z", VK.Z, Modifier.weight(1f))
                KeyBtn("X", VK.X, Modifier.weight(1f))
                KeyBtn("C", VK.C, Modifier.weight(1f))
                KeyBtn("V", VK.V, Modifier.weight(1f))
                KeyBtn("B", VK.B, Modifier.weight(1f))
                KeyBtn("N", VK.N, Modifier.weight(1f))
                KeyBtn("M", VK.M, Modifier.weight(1f))
                KeyBtn(",", VK.OEM_COMMA, Modifier.weight(1f))
                KeyBtn(".", VK.OEM_PERIOD, Modifier.weight(1f))
                KeyBtn("/", VK.OEM_2, Modifier.weight(1f))
                KeyBtn("⇧", VK.RSHIFT, Modifier.weight(2.2f), fn = true)
            }
            // 第 7 行:修饰键行 Ctrl Win Alt 空格 Alt Fn Menu Ctrl
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                KeyBtn("Ctrl", VK.LCONTROL, Modifier.weight(1f), fn = true)
                KeyBtn("Win", VK.LWIN, Modifier.weight(1f), fn = true)
                KeyBtn("Alt", VK.LMENU, Modifier.weight(1f), fn = true)
                KeyBtn("空格", VK.SPACE, Modifier.weight(5.6f), small = true)
                KeyBtn("Alt", VK.RMENU, Modifier.weight(1f), fn = true)
                KeyBtn("Fn", VK.FN, Modifier.weight(1f), fn = true)
                KeyBtn("Menu", VK.MENU_KEY, Modifier.weight(1f), fn = true)
                KeyBtn("Ctrl", VK.RCONTROL, Modifier.weight(1f), fn = true)
            }
            // 第 8 行:PrtSc ScrLk Pause | Ins Home PgUp | ↑
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                KeyBtn("PrtSc", VK.SNAPSHOT, Modifier.weight(3f), fn = true, small = true)
                KeyBtn("ScrLk", VK.SCROLL, Modifier.weight(3f), fn = true, small = true)
                KeyBtn("Pause", VK.PAUSE, Modifier.weight(3f), fn = true, small = true)
                KeyGap(Modifier.weight(1f))
                KeyBtn("Ins", VK.INSERT, Modifier.weight(1f), fn = true)
                KeyBtn("Home", VK.HOME, Modifier.weight(1f), fn = true)
                KeyBtn("PgUp", VK.PRIOR, Modifier.weight(1f), fn = true)
                KeyGap(Modifier.weight(0.4f))
                KeyBtn("↑", VK.UP, Modifier.weight(1f), fn = true)
            }
            // 第 9 行:Del End PgDn | ← ↓ →
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                KeyBtn("Del", VK.DELETE, Modifier.weight(3f), fn = true, small = true)
                KeyBtn("End", VK.END, Modifier.weight(3f), fn = true, small = true)
                KeyBtn("PgDn", VK.NEXT, Modifier.weight(3f), fn = true, small = true)
                KeyGap(Modifier.weight(1f))
                KeyBtn("←", VK.LEFT, Modifier.weight(1f), fn = true)
                KeyBtn("↓", VK.DOWN, Modifier.weight(1f), fn = true)
                KeyBtn("→", VK.RIGHT, Modifier.weight(1f), fn = true)
            }

            // ── 快捷键槽位区(对齐设计稿 kbd-slot:8 个 + 编辑/完成切换) ──
            QuickKeySlots(vm = vm, showToast = { showToast(it) })
        }
    }


    // 语音/键盘控制面板:Tab 切换(必须在 KeyboardPanel 之后定义,局部函数不能前向引用)
    @Composable
    fun VoicePanel() {
        var selectedTab by remember { mutableStateOf(0) }  // 0=语音, 1=键盘
        val micEnabled by vm.micEnabled.collectAsState()
        val isRecording by vm.isRecording.collectAsState()

        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            // Tab 行(下划线风格)
            TabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.fillMaxWidth(),
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("语音", fontSize = 12.sp) },
                    icon = { Icon(Icons.Default.GraphicEq, null, Modifier.size(14.dp)) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("键盘", fontSize = 12.sp) },
                    icon = { Icon(Icons.Default.Keyboard, null, Modifier.size(14.dp)) },
                )
            }

            Spacer(Modifier.height(10.dp))

            when (selectedTab) {
                0 -> {
                    // 语音 Tab:麦克风状态 orb + PTT 录音 + 历史播放 + 快捷槽位
                    // 顶部:状态指示器 + 状态文字(对齐设计稿 mm-mic-status)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        MicOrb()
                        Spacer(Modifier.height(6.dp))
                        val statusText = when {
                            isRecording -> "录音中"
                            micEnabled -> "麦克风已开启"
                            else -> "麦克风已关闭"
                        }
                        val statusColor = when {
                            isRecording -> MaterialTheme.colorScheme.error
                            micEnabled -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        }
                        Text(statusText, fontSize = 11.sp, color = statusColor, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(10.dp))
                    PttButton(modifier = Modifier.fillMaxWidth())
                    if (micEnabled) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "麦克风常开中,PTT 录音已禁用",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    AudioPanel()
                }
                1 -> {
                    // 键盘 Tab:虚拟键盘 + 快捷键
                    KeyboardPanel()
                }
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
                    // 返回按钮
                    IconButtonSmall(
                        icon = Icons.Default.ArrowBack,
                        contentDescription = "返回",
                        onClick = onDisconnect,
                    )
                    StatusBar()
                    Spacer(Modifier.weight(1f))
                    // 独立旋转按钮(对齐设计稿横屏操作栏)
                    IconButtonSmall(
                        icon = Icons.Default.ScreenRotation,
                        contentDescription = "切换竖屏",
                        onClick = { requestPortrait() },
                    )
                    // 更多菜单(横屏外层已渲染返回/旋转按钮,此处只显示更多)
                    ActionBar(showBack = false, showRotate = false)
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
    onPressed: () -> Unit = {},
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
                        onPressed()
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
