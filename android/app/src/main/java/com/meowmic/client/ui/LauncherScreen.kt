package com.meowmic.client.ui

import android.graphics.Bitmap
import org.json.JSONObject
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.meowmic.client.AppListState
import com.meowmic.client.ConnectionState
import com.meowmic.client.DirListing
import com.meowmic.client.MeowMicViewModel
import com.meowmic.client.RunningApp
import com.meowmic.client.WindowInfo
import kotlin.math.abs

// 网格规格:竖屏 5×6=30,横屏 8×3=24(横向更宽,行数减少)
private val GRID_COLUMNS_PORTRAIT = 5
private val GRID_ROWS_PORTRAIT = 6
private val GRID_COLUMNS_LANDSCAPE = 8
private val GRID_ROWS_LANDSCAPE = 3
private fun gridColumns(landscape: Boolean) =
    if (landscape) GRID_COLUMNS_LANDSCAPE else GRID_COLUMNS_PORTRAIT
private fun gridRows(landscape: Boolean) =
    if (landscape) GRID_ROWS_LANDSCAPE else GRID_ROWS_PORTRAIT
private fun pageSize(landscape: Boolean) = gridColumns(landscape) * gridRows(landscape)

/**
 * 快捷启动页面(借鉴 Sunshine/Moonlight 的 applist + launch 形态)
 *
 * 数据流:
 * - PC 端 /applist 提供完整应用库 → [MeowMicViewModel.appListState]
 * - 用户挑选应用加入快捷启动页 → [MeowMicViewModel.quickAppIds](持久化)
 * - 点击格子 → POST /launch 触发 PC 启动
 * - 应用图标从 PC /app_icon 拉取 → [MeowMicViewModel.iconCache]
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LauncherScreen(
    vm: MeowMicViewModel,
    onBack: () -> Unit,
    onDisconnect: () -> Unit = {},
    onNavigate: (String) -> Unit = {},
) {
    val connectionState by vm.connectionState.collectAsState()
    val appListState by vm.appListState.collectAsState()
    val quickAppIds by vm.quickAppIds.collectAsState()
    val launchFeedback by vm.launchFeedback.collectAsState()
    val stats by vm.stats.collectAsState()

    var editMode by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var landscape by remember { mutableStateOf(false) }
    var connExpanded by remember { mutableStateOf(false) }

    // 进入页面时拉取应用库(仅一次,且需已连接)
    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected && appListState is AppListState.Idle) {
            vm.loadAppList()
        }
    }

    // 任务栏轮询:进入页面启动,离开页面停止(借鉴 Windows 任务栏的实时窗口列表)
    DisposableEffect(connectionState) {
        if (connectionState is ConnectionState.Connected) {
            vm.startRunningAppsPolling()
        }
        onDispose {
            vm.stopRunningAppsPolling()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(launchFeedback) {
        launchFeedback?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearLaunchFeedback()
        }
    }

    val addr = (connectionState as? ConnectionState.Connected)?.serverAddr ?: ""

    // 统计数据(用于连接栏展开详情)
    var touchSent = 0L
    var audioSent = 0L
    try {
        val json = JSONObject(stats)
        touchSent = json.optLong("touch_sent", 0)
        audioSent = json.optLong("audio_sent", 0)
    } catch (_: Exception) { }

    // 分页 pagerState(提升到此处,PageIndicator 与 PagerGrid 共享)
    // 横竖屏切换时 pageSize 变化,pageCount 随之重组
    val currentPageSize = pageSize(landscape)
    val pageCount = if (quickAppIds.isEmpty()) 1 else
        (quickAppIds.size + currentPageSize - 1) / currentPageSize
    val pagerState = rememberPagerState(pageCount = { pageCount })

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 1. 可折叠连接栏(默认收起,点击展开详情)
            ConnBar(
                addr = addr,
                expanded = connExpanded,
                onToggle = { connExpanded = !connExpanded },
                touchSent = touchSent,
                audioSent = audioSent,
                landscape = landscape,
            )

            // 2. 顶部操作栏:页面切换组 + 上下文开关组 + 断开按钮
            ActionBar(
                landscape = landscape,
                onBack = onBack,
                onNavigate = onNavigate,
                onDisconnect = onDisconnect,
                onRefresh = { vm.loadAppList() },
            )

            // 3. 快捷启动编辑工具栏(编辑/添加/锁定/翻转)
            EditToolbar(
                locked = locked,
                editMode = editMode,
                landscape = landscape,
                onToggleEdit = { editMode = !editMode },
                onAdd = { showAddDialog = true },
                onToggleLock = {
                    if (!locked) editMode = false
                    locked = !locked
                },
                onToggleLandscape = { landscape = !landscape },
            )

            // 4. 分页网格
            Box(modifier = Modifier.weight(1f)) {
                when (appListState) {
                    is AppListState.Loading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    is AppListState.Error -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                (appListState as AppListState.Error).message,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                            )
                            TextButton(onClick = { vm.loadAppList() }) { Text("重试") }
                        }
                    }
                    else -> {
                        // 无论是否有应用,都显示 PagerGrid(空位全部显示"添加"格子)
                        PagerGrid(
                            pagerState = pagerState,
                            quickAppIds = quickAppIds,
                            vm = vm,
                            editMode = editMode,
                            locked = locked,
                            landscape = landscape,
                            onLaunch = { id -> vm.launchApp(id) },
                            onRemove = { id -> vm.removeQuickApp(id) },
                            onAdd = { showAddDialog = true },
                            onMove = { from, to -> vm.moveQuickApp(from, to) },
                        )
                    }
                }
            }

            // 5. 页面指示器(多页时显示)
            if (pageCount > 1) {
                PageIndicator(pagerState = pagerState, pageCount = pageCount)
            }

            // 6. 底部任务栏:运行中应用窗口 + 服务开关(借鉴 Windows 任务栏)
            Taskbar(vm = vm)
        }

        // Snackbar 固定底部
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    // 添加应用对话框
    if (showAddDialog) {
        AddAppDialog(
            appListState = appListState,
            quickAppIds = quickAppIds,
            vm = vm,
            onAdd = { id -> vm.addQuickApp(id) },
            onDismiss = { showAddDialog = false },
        )
    }
}

/**
 * 可折叠连接栏(默认收起,点击展开详情)
 *
 * 对齐设计稿 mm-conn-bar:紧凑行(绿点+已连接+箭头),展开后显示地址/统计/方向标签
 */
@Composable
internal fun ConnBar(
    addr: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    touchSent: Long,
    audioSent: Long,
    landscape: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
    ) {
        // 紧凑行:绿点 + 已连接 + 箭头
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "已连接",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier
                    .size(14.dp)
                    .then(if (expanded) Modifier.graphicsLayer { scaleY = -1f } else Modifier),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        // 展开详情
        if (expanded) {
            Column(modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 6.dp)) {
                Text(
                    addr.ifBlank { "未连接" },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "T:$touchSent  A:$audioSent  快捷启动",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (landscape) "横屏" else "竖屏",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 顶部操作栏:页面切换组 + 上下文开关组 + 断开按钮
 *
 * 对齐设计稿 mm-actionbar:
 * - 左侧:返回 + 分隔线 + 触控/快捷启动/语音/键盘/显示器/文件 6 个页面按钮
 * - 弹性间距
 * - 右侧上下文(快捷启动页 = 刷新)+ 分隔线 + 断开(红色)
 */
@Composable
private fun ActionBar(
    landscape: Boolean,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
) {
    val btnSize = if (landscape) 22.dp else 24.dp
    val icSize = if (landscape) 12.dp else 14.dp
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ── 左侧:页面切换组(复用共享组件) ──
        PageSwitcher(
            currentView = "launcher",
            onBack = onBack,
            onNavigate = onNavigate,
            btnSize = btnSize,
            iconSize = icSize,
        )

        Spacer(Modifier.weight(1f))

        // ── 右侧上下文:快捷启动页 = 刷新 ──
        IconButtonSmall(
            icon = Icons.Default.Refresh,
            contentDescription = "刷新应用库",
            onClick = onRefresh,
            buttonSize = btnSize,
            iconSize = icSize,
        )
        ActionBarDivider()
        // 断开连接(红色)
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

/**
 * 快捷启动编辑工具栏:编辑/添加/锁定/翻转
 *
 * 设计稿 mm-style-toggle 风格的小尺寸图标按钮组,横向排列
 */
@Composable
private fun EditToolbar(
    locked: Boolean,
    editMode: Boolean,
    landscape: Boolean,
    onToggleEdit: () -> Unit,
    onAdd: () -> Unit,
    onToggleLock: () -> Unit,
    onToggleLandscape: () -> Unit,
) {
    val btnSize = if (landscape) 22.dp else 24.dp
    val icSize = if (landscape) 12.dp else 14.dp
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 编辑按钮:ghost 风格,激活时主色,锁定时禁用
        ToggleButtonSmall(
            icon = if (editMode) Icons.Default.Check else Icons.Default.Edit,
            contentDescription = "编辑",
            isOn = editMode,
            onClick = onToggleEdit,
            tintOff = if (locked) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.onSurfaceVariant,
            buttonSize = btnSize,
            iconSize = icSize,
        )
        // 添加按钮:ghost 风格,锁定时禁用
        IconButtonSmall(
            icon = Icons.Default.Add,
            contentDescription = "添加",
            onClick = onAdd,
            buttonSize = btnSize,
            iconSize = icSize,
        )
        Spacer(Modifier.width(2.dp))
        // 锁定按钮:激活时品牌色高亮
        ToggleButtonSmall(
            icon = if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
            contentDescription = if (locked) "解锁" else "锁定",
            isOn = locked,
            onClick = onToggleLock,
            buttonSize = btnSize,
            iconSize = icSize,
        )
        Spacer(Modifier.weight(1f))
        // 翻转按钮:单一 RotateCw 图标,横屏时品牌色高亮(对齐设计稿 rotate-cw)
        ToggleButtonSmall(
            icon = Icons.Default.RotateRight,
            contentDescription = if (landscape) "切回竖屏" else "切换横屏",
            isOn = landscape,
            onClick = onToggleLandscape,
            buttonSize = btnSize,
            iconSize = icSize,
        )
    }
}



/** 空状态:已弃用,统一用 PagerGrid 显示空位"添加"格子 */

/** 分页网格:HorizontalPager,竖屏 5×6 / 横屏 8×3,空位全部显示"添加" */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PagerGrid(
    pagerState: androidx.compose.foundation.pager.PagerState,
    quickAppIds: List<String>,
    vm: MeowMicViewModel,
    editMode: Boolean,
    locked: Boolean,
    landscape: Boolean,
    onLaunch: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
) {
    val columns = gridColumns(landscape)
    val rows = gridRows(landscape)
    val currentPageSize = pageSize(landscape)

    // 拖动排序状态
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    // 每个 cell 的屏幕位置(用于拖动时命中检测)
    val cellBounds = remember { mutableStateMapOf<Int, Rect>() }
    // 网格区域在 root 中的原点(用于将局部坐标转为 root 坐标)
    var gridOrigin by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { gridOrigin = it.positionInRoot() }
            // 长按拖动排序(锁定时禁用);长按前不消费事件,不影响 Pager 横向滑动
            .pointerInput(locked, columns, rows) {
                if (locked) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val globalPos = offset + gridOrigin
                        val hit = cellBounds.entries.firstOrNull { it.value.contains(globalPos) }?.key
                        if (hit != null && hit < quickAppIds.size) {
                            draggingIndex = hit
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val current = draggingIndex ?: return@detectDragGesturesAfterLongPress
                        val globalPos = change.position + gridOrigin
                        val target = cellBounds.entries.firstOrNull { it.value.contains(globalPos) }?.key
                        if (target != null && target != current && target < quickAppIds.size) {
                            onMove(current, target)
                            draggingIndex = target
                        }
                    },
                    onDragEnd = { draggingIndex = null },
                    onDragCancel = { draggingIndex = null },
                )
            }
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 8.dp,
        ) { pageIndex ->
            val start = pageIndex * currentPageSize
            val end = minOf(start + currentPageSize, quickAppIds.size)
            val pageItems = quickAppIds.subList(start, end)

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (row in 0 until rows) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (col in 0 until columns) {
                            val indexInPage = row * columns + col
                            val globalIndex = start + indexInPage
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .onGloballyPositioned { coords ->
                                        cellBounds[globalIndex] = coords.boundsInRoot()
                                    }
                            ) {
                                val appId = pageItems.getOrNull(indexInPage)
                                if (appId != null) {
                                    QuickAppCell(
                                        appId = appId,
                                        name = vm.findApp(appId)?.name ?: appId,
                                        vm = vm,
                                        editMode = editMode,
                                        isDragging = draggingIndex == globalIndex,
                                        onLaunch = onLaunch,
                                        onRemove = onRemove,
                                    )
                                } else {
                                    // 所有空位都显示"添加"(对齐设计稿)
                                    AddCell(onClick = onAdd, locked = locked)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 快捷启动格子:图标 + 名称,点击启动,长按拖动排序,编辑模式右上角 X 删除 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickAppCell(
    appId: String,
    name: String,
    vm: MeowMicViewModel,
    editMode: Boolean,
    isDragging: Boolean,
    onLaunch: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                // 拖动中:轻微放大 + 提高透明度(视觉反馈)
                if (isDragging) {
                    scaleX = 1.1f
                    scaleY = 1.1f
                    alpha = 0.85f
                }
            }
            .clickable { onLaunch(appId) }
            .padding(start = 2.dp, end = 2.dp, top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            AppIconBox(appId = appId, vm = vm)
            // 编辑模式:右上角 X 删除按钮
            if (editMode) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-2).dp)
                        .size(16.dp)
                        .clickable { onRemove(appId) },
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "移除",
                        modifier = Modifier.size(10.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
        Text(
            name,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** 应用图标容器:56×56 圆角方块,显示 PC 真实图标或占位 */
@Composable
private fun AppIconBox(appId: String, vm: MeowMicViewModel) {
    val v by vm.iconVersion.collectAsState() // 订阅图标更新
    val bmp: Bitmap? = vm.iconCache[appId]
    Box(
        modifier = Modifier
            .size(56.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                RoundedCornerShape(14.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            // ContentScale.Fit 保持比例,不拉伸;图标填满容器(留少量内边距)
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = appId,
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.size(44.dp),
            )
        } else {
            // 未加载或加载失败:触发加载 + 占位图标
            LaunchedEffect(appId) { vm.loadIcon(appId) }
            Icon(
                Icons.Default.Apps,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            )
        }
    }
}

/** 虚线边框 modifier(对齐设计稿 dashed border,仅支持 RoundedCornerShape) */
private fun Modifier.dashedBorder(
    width: androidx.compose.ui.unit.Dp,
    color: androidx.compose.ui.graphics.Color,
    cornerRadius: androidx.compose.ui.unit.Dp,
    dashWidth: Float = 6f,
    gapWidth: Float = 4f,
): Modifier = this.then(
    Modifier.drawBehind {
        val w = width.toPx()
        val r = cornerRadius.toPx()
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
            size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
            style = Stroke(
                width = w,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashWidth, gapWidth), 0f),
            ),
        )
    }
)

/** 空位"添加"格子(虚线边框 + 加号图标),锁定时禁用 */
@Composable
private fun AddCell(onClick: () -> Unit, locked: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (locked) 0.3f else 1f)
            .clickable(enabled = !locked) { onClick() }
            .padding(start = 2.dp, end = 2.dp, top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .dashedBorder(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
                    cornerRadius = 12.dp,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "添加",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
        Text(
            "添加",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            maxLines = 1,
        )
    }
}

/** 页面指示器:圆点,active 拉长(与 PagerGrid 共享 pagerState) */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageIndicator(
    pagerState: androidx.compose.foundation.pager.PagerState,
    pageCount: Int,
) {
    val currentPage = pagerState.currentPage
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { i ->
            val active = i == currentPage
            Box(
                modifier = Modifier
                    .height(6.dp)
                    .width(if (active) 16.dp else 6.dp)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    )
            )
        }
    }
}

/**
 * 底部任务栏:运行中应用窗口 + 服务开关(借鉴 Windows 任务栏)
 *
 * 结构:
 * - 左侧:运行中应用列表(横向滚动),每组按 exe 分组,前台应用高亮
 *   - 单窗口:点击直接激活;右下角小圆点标记前台
 *   - 多窗口:点击弹出窗口列表;右上角堆叠徽标显示窗口数
 * - 分隔线
 * - 右侧:服务开关(麦克风 / 外放静音 / 提示音)
 */
@Composable
private fun Taskbar(vm: MeowMicViewModel) {
    val runningApps by vm.runningApps.collectAsState()
    val micEnabled by vm.micEnabled.collectAsState()
    val muteSpeaker by vm.muteSpeaker.collectAsState()
    val soundFeedback by vm.soundFeedback.collectAsState()
    val exeIconVersion by vm.exeIconVersion.collectAsState() // 驱动图标加载后重组

    // 当前展开多窗口弹出的应用 key(exePath);null=无弹出
    var expandedAppKey by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 1. 运行中应用(横向滚动)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (runningApps.isEmpty()) {
                    Text(
                        "无运行中应用",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                } else {
                    runningApps.forEach { app ->
                        TaskAppItem(
                            app = app,
                            exeIconVersion = exeIconVersion,
                            vm = vm,
                            isExpanded = expandedAppKey == app.exePath,
                            onToggleExpand = {
                                expandedAppKey = if (expandedAppKey == app.exePath) null else app.exePath
                            },
                            onDismissExpand = { expandedAppKey = null },
                        )
                    }
                }
            }

            // 2. 分隔线
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(24.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            )

            // 3. 服务开关(麦克风 / 外放 / 提示音)
            Row(
                modifier = Modifier.padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TaskServiceButton(
                    icon = if (micEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                    isOn = micEnabled,
                    contentDescription = if (micEnabled) "关闭麦克风" else "开启麦克风",
                    onClick = { vm.setMicEnabled(!micEnabled) },
                )
                TaskServiceButton(
                    icon = if (muteSpeaker) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    isOn = !muteSpeaker,
                    contentDescription = if (muteSpeaker) "取消静音" else "静音外放",
                    onClick = { vm.setMuteSpeaker(!muteSpeaker) },
                )
                TaskServiceButton(
                    icon = Icons.Default.GraphicEq,
                    isOn = soundFeedback,
                    contentDescription = if (soundFeedback) "关闭提示音" else "开启提示音",
                    onClick = { vm.setSoundFeedback(!soundFeedback) },
                )
            }
        }
    }
}

/**
 * 任务栏单个应用按钮
 *
 * - 单窗口:点击直接 focusWindow;前台窗口右下角显示小圆点
 * - 多窗口:点击弹出窗口列表;右上角显示窗口数徽标
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskAppItem(
    app: RunningApp,
    exeIconVersion: Int,
    vm: MeowMicViewModel,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onDismissExpand: () -> Unit,
) {
    // exeIconVersion 参数变化时触发重组,从而重新读取 exeIconCache
    val bmp: Bitmap? = vm.exeIconCache[app.exePath]
    val hasMultipleWindows = app.windows.size > 1
    val isActive = app.windows.any { it.isActive }
    val haptic = LocalHapticFeedback.current

    // 长按弹出的关闭按钮弹出窗(null=未展开)
    var showClosePopup by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .combinedClickable(
                    onClick = {
                        if (hasMultipleWindows) {
                            onToggleExpand()
                        } else {
                            app.windows.firstOrNull()?.let { vm.focusWindow(it.hwnd) }
                        }
                    },
                    onLongClick = {
                        // 长按:震动反馈 + 弹出关闭按钮
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showClosePopup = true
                    },
                )
                .background(
                    if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else Color.Transparent,
                    RoundedCornerShape(6.dp),
                )
                .padding(horizontal = 5.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // 应用图标(18×18 圆角方块)
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = app.name,
                        modifier = Modifier.size(12.dp),
                    )
                } else {
                    if (app.exePath.isNotBlank()) {
                        LaunchedEffect(app.exePath) { vm.loadExeIcon(app.exePath) }
                    }
                    Icon(
                        Icons.Default.Apps,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // 应用名(最多 32dp 宽,超出省略)
            Text(
                app.name,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 32.dp),
            )
            // 徽标:多窗口显示堆叠计数,单窗口前台显示小圆点
            if (hasMultipleWindows) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${app.windows.size}",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            } else if (isActive) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }

        // 多窗口弹出列表
        if (isExpanded && hasMultipleWindows) {
            Popup(
                popupPositionProvider = AboveAnchorPositionProvider,
                onDismissRequest = onDismissExpand,
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                ),
            ) {
                TaskPopWindow(app = app, vm = vm, onDismiss = onDismissExpand)
            }
        }

        // 长按弹出的关闭按钮(单窗口直接关闭,多窗口关闭全部)
        if (showClosePopup) {
            Popup(
                popupPositionProvider = AboveAnchorPositionProvider,
                onDismissRequest = { showClosePopup = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                ),
            ) {
                TaskClosePopup(
                    app = app,
                    onClose = {
                        if (hasMultipleWindows) {
                            // 多窗口:逐个关闭所有窗口
                            app.windows.forEach { vm.closeWindow(it.hwnd) }
                        } else {
                            app.windows.firstOrNull()?.let { vm.closeWindow(it.hwnd) }
                        }
                        showClosePopup = false
                    },
                    onDismiss = { showClosePopup = false },
                )
            }
        }
    }
}

/**
 * 弹出位置提供者:将 Popup 放置在锚点(任务栏按钮)正上方,水平居中,留 10px 间距
 */
private object AboveAnchorPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val y = anchorBounds.top - popupContentSize.height - 10
        return IntOffset(x, y)
    }
}

/**
 * 多窗口弹出列表(气泡卡片)
 *
 * 结构:标题栏(应用名 · N 个窗口) + 窗口列表(可滚动)
 * 每个窗口条目:图标 + 标题 + 关闭按钮;当前前台窗口高亮
 */
@Composable
private fun TaskPopWindow(
    app: RunningApp,
    vm: MeowMicViewModel,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier
            .widthIn(min = 190.dp, max = 240.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column {
            // 标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    Icons.Default.Apps,
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "${app.name} · ${app.windows.size} 个窗口",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(thickness = 0.5.dp)
            // 窗口列表(可滚动,最高 160dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                app.windows.forEach { window ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.focusWindow(window.hwnd)
                                onDismiss()
                            }
                            .background(
                                if (window.isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else Color.Transparent,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // 窗口图标(复用应用图标占位)
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(4.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Apps,
                                contentDescription = null,
                                modifier = Modifier.size(10.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        // 窗口标题
                        Text(
                            window.title.ifBlank { "未命名窗口" },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // 关闭按钮
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { vm.closeWindow(window.hwnd) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "关闭窗口",
                                modifier = Modifier.size(9.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 长按任务栏任务弹出的关闭按钮卡片
 *
 * 单窗口:显示"关闭"按钮,关闭该窗口
 * 多窗口:显示"关闭全部(N)"按钮,逐个关闭所有窗口
 */
@Composable
private fun TaskClosePopup(
    app: RunningApp,
    onClose: () -> Unit,
    onDismiss: () -> Unit,
) {
    val hasMultipleWindows = app.windows.size > 1
    val closeLabel = if (hasMultipleWindows) "关闭全部(${app.windows.size})" else "关闭"

    Card(
        modifier = Modifier.widthIn(min = 90.dp, max = 140.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onClose()
                    onDismiss()
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                closeLabel,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 服务开关按钮(麦克风 / 外放 / 提示音)
 *
 * 22×22 方形按钮,开启时图标为主色,关闭时为灰色
 */
@Composable
private fun TaskServiceButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isOn: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(12.dp),
            tint = if (isOn) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 添加应用对话框:从 PC 应用库挑选 + 手动添加自定义应用 */
@Composable
private fun AddAppDialog(
    appListState: AppListState,
    quickAppIds: List<String>,
    vm: MeowMicViewModel,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val apps = (appListState as? AppListState.Loaded)?.apps ?: emptyList()
    val available = apps.filter { it.id !in quickAppIds }
    var showManualForm by remember { mutableStateOf(false) }
    var manualName by remember { mutableStateOf("") }
    var manualPath by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var showDirBrowser by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("添加应用", modifier = Modifier.weight(1f))
                if (!showManualForm) {
                    TextButton(onClick = { showManualForm = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("手动添加", fontSize = 11.sp)
                    }
                }
            }
        },
        text = {
            if (showManualForm) {
                // 手动添加表单
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = manualName,
                        onValueChange = { manualName = it },
                        label = { Text("应用名称", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    )
                    // exe 路径 + 浏览按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = manualPath,
                            onValueChange = { manualPath = it },
                            label = { Text("exe 路径", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                            placeholder = { Text("C:\\Program Files\\App\\app.exe", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                        )
                        Button(
                            onClick = { showDirBrowser = true },
                            modifier = Modifier.height(48.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = "浏览", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("浏览", fontSize = 12.sp)
                        }
                    }
                    Text(
                        "支持 %APPDATA%、%LOCALAPPDATA% 等环境变量",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                    if (submitting) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text("添加中...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                if (apps.isEmpty()) {
                    Text(
                        if (appListState is AppListState.Loading) "加载中..."
                        else "应用库为空,可点击\"手动添加\"自定义",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                } else if (available.isEmpty()) {
                    Text("已添加全部应用", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        available.forEach { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAdd(app.id) }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    Icons.Default.Apps,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(app.name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.weight(1f))
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (showManualForm) {
                Row {
                    TextButton(onClick = { showManualForm = false }, enabled = !submitting) { Text("返回") }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = {
                            if (manualName.isNotBlank() && manualPath.isNotBlank()) {
                                submitting = true
                                vm.addCustomApp(manualName.trim(), manualPath.trim()) { ok ->
                                    submitting = false
                                    if (ok) {
                                        showManualForm = false
                                        manualName = ""
                                        manualPath = ""
                                    }
                                }
                            }
                        },
                        enabled = manualName.isNotBlank() && manualPath.isNotBlank() && !submitting,
                    ) { Text("添加") }
                }
            } else {
                TextButton(onClick = onDismiss) { Text("完成") }
            }
        },
    )

    // 目录浏览器对话框
    if (showDirBrowser) {
        DirBrowserDialog(
            vm = vm,
            onPick = { path ->
                manualPath = path
                // 如果名称为空,用文件名(去 .exe)自动填充
                if (manualName.isBlank()) {
                    manualName = path.substringAfterLast('\\').substringAfterLast('/')
                        .removeSuffix(".exe").removeSuffix(".EXE")
                }
                showDirBrowser = false
            },
            onDismiss = { showDirBrowser = false },
        )
    }
}

/**
 * PC 端目录浏览器(用于选择 exe 路径)
 *
 * - 首次打开显示盘符列表(Windows)
 * - 点击目录进入子目录
 * - 点击 exe 文件选中并返回
 * - 顶部"返回上一级"按钮
 */
@Composable
private fun DirBrowserDialog(
    vm: MeowMicViewModel,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var currentPath by remember { mutableStateOf("") }
    var listing by remember { mutableStateOf<DirListing?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // 加载目录内容
    LaunchedEffect(currentPath) {
        loading = true
        error = null
        val result = vm.browseDir(currentPath)
        if (result != null) {
            listing = result
        } else {
            error = "无法读取目录"
        }
        loading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 标题栏
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("选择应用", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(18.dp))
                    }
                }

                // 当前路径栏
                listing?.let { lst ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (lst.current.isBlank()) "我的电脑" else lst.current,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // 返回上一级
                        if (lst.parent != null) {
                            TextButton(
                                onClick = { currentPath = lst.parent!! },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                            ) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = "上一级", modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(2.dp))
                                Text("上级", fontSize = 11.sp)
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // 目录列表
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        loading -> {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        error != null -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(error!!, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = { currentPath = currentPath }) { Text("重试") }
                            }
                        }
                        listing != null -> {
                            val items = listing!!.items
                            if (items.isEmpty()) {
                                Text(
                                    "空目录",
                                    modifier = Modifier.align(Alignment.Center),
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(items) { item ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    if (item.isDir) {
                                                        currentPath = item.path
                                                    } else if (item.isExe) {
                                                        onPick(item.path)
                                                    }
                                                }
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            Icon(
                                                if (item.isDir) Icons.Default.Folder
                                                else Icons.Default.Apps,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                                tint = if (item.isDir) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(item.name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                                                Text(item.path, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            if (item.isExe) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = "选择",
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
