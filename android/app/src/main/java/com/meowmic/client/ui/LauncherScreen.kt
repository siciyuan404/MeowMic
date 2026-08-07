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
import com.meowmic.client.QuickItem
import com.meowmic.client.QuickItemType
import com.meowmic.client.MeowMicViewModel
import com.meowmic.client.RunningApp
import com.meowmic.client.WindowInfo
import kotlinx.coroutines.launch
import kotlin.math.abs

// 网格规格:竖屏 5×7=35,横屏 8×4=32(对齐设计稿 v2 自由布局)
private val GRID_COLUMNS_PORTRAIT = 5
private val GRID_ROWS_PORTRAIT = 7
private val GRID_COLUMNS_LANDSCAPE = 8
private val GRID_ROWS_LANDSCAPE = 4

/** 排列方式:自适应(按方向默认) / 固定列数(5/6/7) */
enum class GridCols(val label: String, val cols: Int?) {
    AUTO("自适应", null),
    COLS_5("5 列", 5),
    COLS_6("6 列", 6),
    COLS_7("7 列", 7),
}

private fun gridColumns(landscape: Boolean, gridCols: GridCols = GridCols.AUTO): Int =
    gridCols.cols ?: if (landscape) GRID_COLUMNS_LANDSCAPE else GRID_COLUMNS_PORTRAIT
private fun gridRows(landscape: Boolean) =
    if (landscape) GRID_ROWS_LANDSCAPE else GRID_ROWS_PORTRAIT
private fun pageSize(landscape: Boolean, gridCols: GridCols = GridCols.AUTO) =
    gridColumns(landscape, gridCols) * gridRows(landscape)

/**
 * 快捷启动页面(借鉴 Sunshine/Moonlight 的 applist + launch 形态)
 *
 * 数据流:
 * - PC 端 /applist 提供完整应用库 → [MeowMicViewModel.appListState]
 * - 用户挑选应用加入快捷启动页 → [MeowMicViewModel.quickItems](持久化)
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
    val quickItems by vm.quickItems.collectAsState()
    val launchFeedback by vm.launchFeedback.collectAsState()
    val stats by vm.stats.collectAsState()

    var editMode by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var landscape by remember { mutableStateOf(false) }
    var connExpanded by remember { mutableStateOf(false) }
    var gridCols by remember { mutableStateOf(GridCols.AUTO) }

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
    // 自由布局:pageCount = 最大 item.page + 1(内容页)+ 1(末尾"新建页面"占位页)
    val maxItemPage = quickItems.maxOfOrNull { it.page } ?: -1
    val pageCount = maxOf(maxItemPage + 2, 2) // 至少 1 内容页 + 1 新建页
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
                gridCols = gridCols,
                onToggleEdit = { editMode = !editMode },
                onAdd = { showAddDialog = true },
                onToggleLock = {
                    if (!locked) editMode = false
                    locked = !locked
                },
                onToggleLandscape = { landscape = !landscape },
                onGridColsChange = { gridCols = it },
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
                        // 自由布局:按 (page,col,row) 定位渲染,末页为"新建页面"占位
                        PagerGrid(
                            pagerState = pagerState,
                            quickItems = quickItems,
                            vm = vm,
                            editMode = editMode,
                            locked = locked,
                            landscape = landscape,
                            gridCols = gridCols,
                            onLaunch = { item -> vm.launchQuickItem(item) },
                            onRemove = { id -> vm.removeQuickItem(id) },
                            onAdd = { showAddDialog = true },
                            onMove = { itemId, page, col, row -> vm.moveQuickItem(itemId, page, col, row) },
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

    // 添加应用对话框(4 类型选择器:APP/SCRIPT/WEBSITE/OBSIDIAN)
    if (showAddDialog) {
        AddAppDialog(
            appListState = appListState,
            quickItems = quickItems,
            vm = vm,
            onAddApp = { id -> vm.addQuickApp(id) },
            onAddCustom = { type, name, target -> vm.addCustomQuickItem(type, name, target) },
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
    gridCols: GridCols,
    onToggleEdit: () -> Unit,
    onAdd: () -> Unit,
    onToggleLock: () -> Unit,
    onToggleLandscape: () -> Unit,
    onGridColsChange: (GridCols) -> Unit,
) {
    val btnSize = if (landscape) 22.dp else 24.dp
    val icSize = if (landscape) 12.dp else 14.dp
    var showColsMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ── 左侧:翻转 + 列数下拉(对齐设计稿 mm-actionbar 右侧布局) ──
        // 翻转按钮:RotateCw 图标,横屏时品牌色高亮
        ToggleButtonSmall(
            icon = Icons.Default.RotateRight,
            contentDescription = if (landscape) "切回竖屏" else "切换横屏",
            isOn = landscape,
            onClick = onToggleLandscape,
            buttonSize = btnSize,
            iconSize = icSize,
        )
        // 列数下拉:GridView 图标 + DropdownMenu(自适应/5/6/7列)
        Box {
            IconButtonSmall(
                icon = Icons.Default.GridView,
                contentDescription = "列数",
                onClick = { showColsMenu = !showColsMenu },
                buttonSize = btnSize,
                iconSize = icSize,
            )
            DropdownMenu(
                expanded = showColsMenu,
                onDismissRequest = { showColsMenu = false },
            ) {
                GridCols.values().forEach { col ->
                    DropdownMenuItem(
                        text = { Text(col.label) },
                        onClick = {
                            onGridColsChange(col)
                            showColsMenu = false
                        },
                        trailingIcon = if (gridCols == col) {
                            { Icon(Icons.Default.Check, null, Modifier.size(14.dp)) }
                        } else null,
                    )
                }
            }
        }
        ActionBarDivider()
        // ── 右侧:编辑/添加/锁定 ──
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
    }
}



/**
 * 分页网格(自由布局 v2):HorizontalPager,每页按 (page,col,row) 定位渲染。
 *
 * - 内容页:遍历 rows×cols,找到该位置的 QuickItem 则渲染格子,否则渲染空位 AddCell
 * - 末页:「拖到此处新建页面」虚线占位区(is-drop-target 高亮态)
 * - 长按拖动:空位 is-drop-target 高亮;拖到边缘自动翻页;拖到末页占位区 → 新建页面
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PagerGrid(
    pagerState: androidx.compose.foundation.pager.PagerState,
    quickItems: List<QuickItem>,
    vm: MeowMicViewModel,
    editMode: Boolean,
    locked: Boolean,
    landscape: Boolean,
    gridCols: GridCols = GridCols.AUTO,
    onLaunch: (QuickItem) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
    onMove: (itemId: String, page: Int, col: Int, row: Int) -> Unit,
) {
    val columns = gridColumns(landscape, gridCols)
    val rows = gridRows(landscape)

    // 拖动状态:当前拖动的 item id
    var draggingItemId by remember { mutableStateOf<String?>(null) }
    // 当前拖动悬停的位置(page, col, row);page=-1 表示"新建页面"占位区
    var dropTarget by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    // 每个 cell 的屏幕位置,key = "page:col:row"(1-based col/row)
    val cellBounds = remember { mutableStateMapOf<String, Rect>() }
    // "新建页面"占位区的屏幕位置
    var newPageBounds by remember { mutableStateOf<Rect?>(null) }
    // 网格区域在 root 中的原点
    var gridOrigin by remember { mutableStateOf(Offset.Zero) }
    // 边缘自动翻页:记录上次翻页时间,避免抖动
    var lastEdgeScrollMs by remember { mutableStateOf(0L) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { gridOrigin = it.positionInRoot() }
            // 长按拖动排序(锁定时禁用);长按前不消费事件,不影响 Pager 横向滑动
            .pointerInput(locked) {
                if (locked) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val globalPos = offset + gridOrigin
                        val hitKey = cellBounds.entries.firstOrNull { it.value.contains(globalPos) }?.key
                        if (hitKey != null) {
                            val parts = hitKey.split(":")
                            val (hp, hc, hr) = Triple(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
                            val item = quickItems.firstOrNull { it.page == hp && it.col == hc && it.row == hr }
                            if (item != null) draggingItemId = item.id
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        if (draggingItemId == null) return@detectDragGesturesAfterLongPress
                        val globalPos = change.position + gridOrigin
                        // 命中检测:优先匹配格子,其次匹配"新建页面"占位区
                        val hitKey = cellBounds.entries.firstOrNull { it.value.contains(globalPos) }?.key
                        dropTarget = if (hitKey != null) {
                            val parts = hitKey.split(":")
                            Triple(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
                        } else if (newPageBounds?.contains(globalPos) == true) {
                            Triple(-1, 1, 1) // page=-1 标记"新建页面"
                        } else null
                        // 边缘自动翻页(拖到左右边缘 12% 区域时翻页,500ms 防抖)
                        val w = this.size.width.toFloat()
                        val now = System.currentTimeMillis()
                        if (now - lastEdgeScrollMs > 500) {
                            val cur = pagerState.currentPage
                            if (change.position.x > w * 0.88f && cur < pagerState.pageCount - 1) {
                                lastEdgeScrollMs = now
                                scope.launch { pagerState.animateScrollToPage(cur + 1) }
                            } else if (change.position.x < w * 0.12f && cur > 0) {
                                lastEdgeScrollMs = now
                                scope.launch { pagerState.animateScrollToPage(cur - 1) }
                            }
                        }
                    },
                    onDragEnd = {
                        val (page, col, row) = dropTarget ?: Triple(0, 0, 0)
                        val id = draggingItemId
                        if (id != null && page != 0) {
                            if (page == -1) {
                                // 拖到"新建页面"占位区 → 在新页面 (maxPage+1) 放置
                                val newPageIdx = (quickItems.maxOfOrNull { it.page } ?: -1) + 1
                                onMove(id, newPageIdx, 1, 1)
                            } else {
                                onMove(id, page, col, row)
                            }
                        }
                        draggingItemId = null
                        dropTarget = null
                    },
                    onDragCancel = {
                        draggingItemId = null
                        dropTarget = null
                    },
                )
            }
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 8.dp,
        ) { pageIndex ->
            val isLastPage = pageIndex == pagerState.pageCount - 1
            if (isLastPage) {
                // 末页:"拖到此处新建页面"虚线占位区
                NewPagePlaceholder(
                    isDropTarget = dropTarget?.first == -1,
                    onTap = onAdd,
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { newPageBounds = it.boundsInRoot() },
                )
            } else {
                // 内容页:按 (page, col, row) 定位渲染
                val pageItems = quickItems.filter { it.page == pageIndex }
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (row in 1..rows) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            for (col in 1..columns) {
                                val item = pageItems.firstOrNull { it.col == col && it.row == row }
                                val posKey = "$pageIndex:$col:$row"
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .onGloballyPositioned { coords ->
                                            cellBounds[posKey] = coords.boundsInRoot()
                                        }
                                ) {
                                    if (item != null) {
                                        QuickItemCell(
                                            item = item,
                                            vm = vm,
                                            editMode = editMode,
                                            isDragging = draggingItemId == item.id,
                                            isDropTarget = false,
                                            onLaunch = onLaunch,
                                            onRemove = onRemove,
                                        )
                                    } else {
                                        // 空位:显示"添加",拖动悬停时 is-drop-target 高亮
                                        AddCell(
                                            onClick = onAdd,
                                            locked = locked,
                                            isDropTarget = dropTarget == Triple(pageIndex, col, row),
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

/** 快捷启动格子:图标 + 名称,点击启动,长按拖动排序,编辑模式右上角 X 删除 */
/**
 * 快捷启动格子(自由布局 v2):图标 + 名称 + 类型徽标色点。
 *
 * 类型徽标(右上角小圆点,对齐设计稿):
 * - APP:      无色点(默认)
 * - SCRIPT:   品牌色(primary)小圆点
 * - WEBSITE:  中性色(outline)小圆点
 * - OBSIDIAN: 灰色(onSurfaceVariant)小圆点
 *
 * 非空格子带 draggable=true 语义(实际拖拽由 PagerGrid 的 pointerInput 统一处理)。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickItemCell(
    item: QuickItem,
    vm: MeowMicViewModel,
    editMode: Boolean,
    isDragging: Boolean,
    isDropTarget: Boolean,
    onLaunch: (QuickItem) -> Unit,
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
            .clickable { onLaunch(item) }
            .padding(start = 2.dp, end = 2.dp, top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            // 图标容器:APP 类型用 PC 真实图标;其他类型用类型对应图标
            TypeIconBox(item = item, vm = vm)
            // 类型徽标色点(右上角,仅非 APP 类型显示)
            if (item.type != QuickItemType.APP) {
                TypeBadgeDot(type = item.type)
            }
            // 编辑模式:右上角 X 删除按钮(覆盖在色点上方)
            if (editMode) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-2).dp)
                        .size(16.dp)
                        .clickable { onRemove(item.id) },
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
            item.name,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 类型图标容器:APP 用 PC 真实图标;SCRIPT/WEBSITE/OBSIDIAN 用对应 Material 图标。
 *
 * 图标颜色(对齐设计稿):
 * - SCRIPT:   品牌色(primary)
 * - WEBSITE:  蓝色(accent-blue 替代用 primary)
 * - OBSIDIAN: 紫色(用 secondary)
 */
@Composable
private fun TypeIconBox(item: QuickItem, vm: MeowMicViewModel) {
    val iconTint = when (item.type) {
        QuickItemType.APP -> MaterialTheme.colorScheme.primary
        QuickItemType.SCRIPT -> MaterialTheme.colorScheme.primary
        QuickItemType.WEBSITE -> MaterialTheme.colorScheme.primary
        QuickItemType.OBSIDIAN -> MaterialTheme.colorScheme.secondary
    }
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
        when (item.type) {
            QuickItemType.APP -> AppIconContent(appId = item.appId, vm = vm)
            QuickItemType.SCRIPT -> Icon(
                Icons.Default.Terminal,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = iconTint,
            )
            QuickItemType.WEBSITE -> Icon(
                Icons.Default.Public,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = iconTint,
            )
            QuickItemType.OBSIDIAN -> Icon(
                Icons.Default.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = iconTint,
            )
        }
    }
}

/** APP 类型图标内容:PC 真实图标或占位 */
@Composable
private fun AppIconContent(appId: String, vm: MeowMicViewModel) {
    val v by vm.iconVersion.collectAsState() // 订阅图标更新
    val bmp: Bitmap? = vm.iconCache[appId]
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = appId,
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.size(44.dp),
        )
    } else {
        LaunchedEffect(appId) { vm.loadIcon(appId) }
        Icon(
            Icons.Default.Apps,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
        )
    }
}

/**
 * 类型徽标色点(右上角,对齐设计稿 ::after 伪元素)
 *
 * - SCRIPT:   品牌色(primary),白边
 * - WEBSITE:  中性色(outline),灰边
 * - OBSIDIAN: 灰色(onSurfaceVariant),白边
 */
@Composable
private fun BoxScope.TypeBadgeDot(type: QuickItemType) {
    val (bg, border) = when (type) {
        QuickItemType.SCRIPT ->
            MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.surface
        QuickItemType.WEBSITE ->
            MaterialTheme.colorScheme.outline to MaterialTheme.colorScheme.outlineVariant
        QuickItemType.OBSIDIAN ->
            MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surface
        else -> return
    }
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .offset(x = 2.dp, y = 3.dp)
            .size(10.dp)
            .background(bg, CircleShape)
            .border(1.5.dp, border, CircleShape),
    )
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

/**
 * 空位"添加"格子(虚线边框 + 加号图标)。
 * 拖动悬停时 is-drop-target 高亮(品牌色边框 + 品牌色淡背景)。
 */
@Composable
private fun AddCell(
    onClick: () -> Unit,
    locked: Boolean = false,
    isDropTarget: Boolean = false,
) {
    val borderColor by animateColorAsState(
        targetValue = if (isDropTarget) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
        label = "addBorder",
    )
    val bgColor by animateColorAsState(
        targetValue = if (isDropTarget) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        else Color.Transparent,
        label = "addBg",
    )
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
                .background(bgColor, RoundedCornerShape(12.dp))
                .dashedBorder(
                    width = 1.dp,
                    color = borderColor,
                    cornerRadius = 12.dp,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "添加",
                modifier = Modifier.size(18.dp),
                tint = if (isDropTarget) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
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

/**
 * 末页「拖到此处新建页面」虚线占位区。
 *
 * - hover/拖入时品牌色高亮(is-drop-target 态)
 * - 点击触发 onAdd(打开添加对话框,添加后自动出现新内容页)
 */
@Composable
private fun NewPagePlaceholder(
    isDropTarget: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor by animateColorAsState(
        targetValue = if (isDropTarget) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
        label = "newPageBorder",
    )
    val bgColor by animateColorAsState(
        targetValue = if (isDropTarget) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        else Color.Transparent,
        label = "newPageBg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isDropTarget) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        label = "newPageContent",
    )
    Box(
        modifier = modifier
            .clickable { onTap() }
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor, RoundedCornerShape(12.dp))
                .dashedBorder(
                    width = 2.dp,
                    color = borderColor,
                    cornerRadius = 12.dp,
                    dashWidth = 8f,
                    gapWidth = 5f,
                )
                .padding(vertical = 24.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = contentColor,
            )
            Text(
                "拖到此处新建页面",
                fontSize = 10.sp,
                color = contentColor,
                maxLines = 1,
            )
        }
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

/**
 * 添加快捷启动对话框(4 类型选择器:APP/SCRIPT/WEBSITE/OBSIDIAN)
 *
 * 对齐设计稿 v2:
 * - 顶部 4 个类型 Tab(APP=应用 / SCRIPT=脚本 / WEBSITE=网站 / OBSIDIAN=Obsidian)
 * - APP 类型:从 PC 应用库挑选 + 手动添加 exe(走旧 addCustomApp 接口)
 * - SCRIPT/WEBSITE/OBSIDIAN:名称 + target 输入框,提交时调用 addCustomQuickItem
 * - 各类型独立表单,target 提示文案随类型变化
 */
@Composable
private fun AddAppDialog(
    appListState: AppListState,
    quickItems: List<QuickItem>,
    vm: MeowMicViewModel,
    onAddApp: (String) -> Unit,
    onAddCustom: (QuickItemType, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 当前选中的类型 Tab(默认 APP)
    var selectedType by remember { mutableStateOf(QuickItemType.APP) }
    // 手动表单状态(SCRIPT/WEBSITE/OBSIDIAN 共用;APP 类型有独立表单)
    var manualName by remember { mutableStateOf("") }
    var manualTarget by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    // APP 类型:是否显示手动添加表单(否则显示应用库列表)
    var showAppManualForm by remember { mutableStateOf(false) }
    var showDirBrowser by remember { mutableStateOf(false) }

    // 类型切换时清空表单(避免残留)
    LaunchedEffect(selectedType) {
        if (selectedType != QuickItemType.APP) {
            manualName = ""
            manualTarget = ""
            showAppManualForm = false
        }
    }

    val apps = (appListState as? AppListState.Loaded)?.apps ?: emptyList()
    val addedAppIds = quickItems.filter { it.type == QuickItemType.APP }.map { it.appId }.toSet()
    val availableApps = apps.filter { it.id !in addedAppIds }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("添加快捷启动", modifier = Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 类型 Tab 行(4 个,均分宽度)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    QuickItemType.values().forEach { type ->
                        TypeTab(
                            type = type,
                            selected = selectedType == type,
                            onClick = { selectedType = type },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                when (selectedType) {
                    QuickItemType.APP -> {
                        if (showAppManualForm) {
                            AppManualForm(
                                name = manualName,
                                onNameChange = { manualName = it },
                                path = manualTarget,
                                onPathChange = { manualTarget = it },
                                onBrowse = { showDirBrowser = true },
                                submitting = submitting,
                            )
                        } else {
                            // 应用库列表
                            if (apps.isEmpty()) {
                                Text(
                                    if (appListState is AppListState.Loading) "加载中..."
                                    else "应用库为空,可点击\"手动添加\"自定义",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                )
                            } else if (availableApps.isEmpty()) {
                                Text("已添加全部应用", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            } else {
                                Column(
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    availableApps.forEach { app ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    onAddApp(app.id)
                                                    onDismiss()
                                                }
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
                    }
                    QuickItemType.SCRIPT, QuickItemType.WEBSITE, QuickItemType.OBSIDIAN -> {
                        CustomTypeForm(
                            type = selectedType,
                            name = manualName,
                            onNameChange = { manualName = it },
                            target = manualTarget,
                            onTargetChange = { manualTarget = it },
                            onBrowse = { showDirBrowser = true },
                            submitting = submitting,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // APP 类型:列表态显示"手动添加"切换按钮
                if (selectedType == QuickItemType.APP && !showAppManualForm) {
                    TextButton(onClick = { showAppManualForm = true }) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("手动添加", fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(4.dp))
                }
                // APP 手动表单:显示"返回列表"按钮
                if (selectedType == QuickItemType.APP && showAppManualForm) {
                    TextButton(onClick = { showAppManualForm = false }, enabled = !submitting) {
                        Text("返回列表")
                    }
                    Spacer(Modifier.width(4.dp))
                }
                TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") }
                Spacer(Modifier.width(4.dp))
                // 提交按钮:APP 列表态隐藏;APP 手动表单 + 其他类型显示
                val showSubmit = selectedType != QuickItemType.APP || showAppManualForm
                if (showSubmit) {
                    Button(
                        onClick = {
                            val name = manualName.trim()
                            val target = manualTarget.trim()
                            if (name.isBlank() || target.isBlank()) return@Button
                            submitting = true
                            if (selectedType == QuickItemType.APP) {
                                // APP 手动添加:走旧 addCustomApp(注册到 PC 应用库)
                                vm.addCustomApp(name, target) { ok ->
                                    submitting = false
                                    if (ok) onDismiss()
                                }
                            } else {
                                // 其他类型:调用 addCustomQuickItem(type, name, target)
                                onAddCustom(selectedType, name, target)
                                onDismiss()
                            }
                        },
                        enabled = manualName.isNotBlank() && manualTarget.isNotBlank() && !submitting,
                    ) { Text("添加") }
                }
            }
        },
    )

    // 目录浏览器对话框(APP 手动 / SCRIPT 类型可用)
    if (showDirBrowser) {
        DirBrowserDialog(
            vm = vm,
            onPick = { path ->
                manualTarget = path
                // 名称为空时,用文件名自动填充
                if (manualName.isBlank()) {
                    val fileName = path.substringAfterLast('\\').substringAfterLast('/')
                    manualName = when (selectedType) {
                        QuickItemType.APP -> fileName.removeSuffix(".exe").removeSuffix(".EXE")
                        QuickItemType.SCRIPT -> fileName.substringBeforeLast('.')
                        else -> fileName
                    }
                }
                showDirBrowser = false
            },
            onDismiss = { showDirBrowser = false },
        )
    }
}

/**
 * 类型 Tab 按钮(4 个均分宽度,选中态品牌色高亮)
 */
@Composable
private fun TypeTab(
    type: QuickItemType,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        label = "tabBg",
    )
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant
    val icon = when (type) {
        QuickItemType.APP -> Icons.Default.Apps
        QuickItemType.SCRIPT -> Icons.Default.Terminal
        QuickItemType.WEBSITE -> Icons.Default.Public
        QuickItemType.OBSIDIAN -> Icons.Default.MenuBook
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = fg)
        Text(type.label, fontSize = 10.sp, color = fg, maxLines = 1)
    }
}

/**
 * APP 手动添加表单(名称 + exe 路径 + 浏览按钮)
 */
@Composable
private fun AppManualForm(
    name: String,
    onNameChange: (String) -> Unit,
    path: String,
    onPathChange: (String) -> Unit,
    onBrowse: () -> Unit,
    submitting: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("应用名称", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = path,
                onValueChange = onPathChange,
                label = { Text("exe 路径", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                placeholder = {
                    Text(
                        "C:\\Program Files\\App\\app.exe",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                },
            )
            Button(
                onClick = onBrowse,
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
}

/**
 * 自定义类型表单(SCRIPT/WEBSITE/OBSIDIAN):名称 + target 输入
 *
 * target 提示文案随类型变化:
 * - SCRIPT:   脚本路径(.bat/.cmd/.ps1)
 * - WEBSITE:  URL(https://...)
 * - OBSIDIAN: obsidian:// URI
 */
@Composable
private fun CustomTypeForm(
    type: QuickItemType,
    name: String,
    onNameChange: (String) -> Unit,
    target: String,
    onTargetChange: (String) -> Unit,
    onBrowse: () -> Unit,
    submitting: Boolean,
) {
    val targetLabel = when (type) {
        QuickItemType.SCRIPT -> "脚本路径"
        QuickItemType.WEBSITE -> "网址 URL"
        QuickItemType.OBSIDIAN -> "Obsidian URI"
        else -> "目标"
    }
    val targetPlaceholder = when (type) {
        QuickItemType.SCRIPT -> "C:\\scripts\\clean.bat"
        QuickItemType.WEBSITE -> "https://github.com"
        QuickItemType.OBSIDIAN -> "obsidian://open?vault=MyVault&file=Inbox"
        else -> ""
    }
    val targetHint = when (type) {
        QuickItemType.SCRIPT -> ".bat/.cmd 自动包 cmd /c;.ps1 自动包 powershell -File"
        QuickItemType.WEBSITE -> "通过默认浏览器打开 URL"
        QuickItemType.OBSIDIAN -> "通过 Obsidian 客户端打开 URI"
        else -> ""
    }
    val showBrowse = type == QuickItemType.SCRIPT
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("名称", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
            placeholder = {
                Text(
                    when (type) {
                        QuickItemType.SCRIPT -> "清理脚本"
                        QuickItemType.WEBSITE -> "GitHub"
                        QuickItemType.OBSIDIAN -> "项目笔记"
                        else -> ""
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = target,
                onValueChange = onTargetChange,
                label = { Text(targetLabel, fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                placeholder = {
                    Text(
                        targetPlaceholder,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                },
            )
            if (showBrowse) {
                Button(
                    onClick = onBrowse,
                    modifier = Modifier.height(48.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                ) {
                    Icon(Icons.Default.Folder, contentDescription = "浏览", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("浏览", fontSize = 12.sp)
                }
            }
        }
        if (targetHint.isNotEmpty()) {
            Text(
                targetHint,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        if (submitting) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("添加中...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
