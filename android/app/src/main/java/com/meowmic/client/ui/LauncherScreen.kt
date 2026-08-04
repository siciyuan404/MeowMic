package com.meowmic.client.ui

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowmic.client.AppListState
import com.meowmic.client.ConnectionState
import com.meowmic.client.MeowMicViewModel

private val GRID_COLUMNS = 5
private val GRID_ROWS = 6
private val PAGE_SIZE = GRID_COLUMNS * GRID_ROWS // 30
private val DOCK_COUNT = 4

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
) {
    val connectionState by vm.connectionState.collectAsState()
    val appListState by vm.appListState.collectAsState()
    val quickAppIds by vm.quickAppIds.collectAsState()
    val launchFeedback by vm.launchFeedback.collectAsState()

    var editMode by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    // 进入页面时拉取应用库(仅一次,且需已连接)
    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected && appListState is AppListState.Idle) {
            vm.loadAppList()
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

    // 分页 pagerState(提升到此处,PageIndicator 与 PagerGrid 共享)
    val pageCount = if (quickAppIds.isEmpty()) 1 else (quickAppIds.size + PAGE_SIZE - 1) / PAGE_SIZE
    val pagerState = rememberPagerState(pageCount = { pageCount })

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 1. 顶部状态栏
            StatusBar(addr)

            // 2. 顶部操作栏
            TopBar(
                editMode = editMode,
                onBack = onBack,
                onToggleEdit = { editMode = !editMode },
                onAdd = { showAddDialog = true },
            )

            // 3. 分页网格
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
                            onLaunch = { id -> vm.launchApp(id) },
                            onRemove = { id -> vm.removeQuickApp(id) },
                            onAdd = { showAddDialog = true },
                        )
                    }
                }
            }

            // 4. 页面指示器(多页时显示)
            if (pageCount > 1) {
                PageIndicator(pagerState = pagerState, pageCount = pageCount)
            }

            // 5. 底部 Dock 栏
            DockBar(
                quickAppIds = quickAppIds,
                vm = vm,
                onLaunch = { id -> vm.launchApp(id) },
            )
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
            onAdd = { id -> vm.addQuickApp(id) },
            onDismiss = { showAddDialog = false },
        )
    }
}

/** 顶部状态栏:已连接信息 + 快捷启动模式标签 */
@Composable
private fun StatusBar(addr: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.Mouse,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (addr.isNotBlank()) "已连接 · $addr" else "未连接",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "快捷启动模式",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            ) {
                Text(
                    "竖屏",
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 顶部操作栏:返回(secondary) / 标题 / 编辑(ghost) / 添加(ghost) */
@Composable
private fun TopBar(
    editMode: Boolean,
    onBack: () -> Unit,
    onToggleEdit: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 返回按钮:secondary 风格(overlay 背景 + 边框)
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    RoundedCornerShape(8.dp),
                )
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    RoundedCornerShape(8.dp),
                )
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.ArrowBack,
                contentDescription = "返回",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            "快捷启动",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // 编辑按钮:ghost 风格(透明背景,激活时主色)
        Box(
            modifier = Modifier
                .size(28.dp)
                .clickable(onClick = onToggleEdit),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (editMode) Icons.Default.Check else Icons.Default.Edit,
                contentDescription = "编辑",
                modifier = Modifier.size(18.dp),
                tint = if (editMode) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 添加按钮:ghost 风格
        Box(
            modifier = Modifier
                .size(28.dp)
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "添加",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 空状态:已弃用,统一用 PagerGrid 显示空位"添加"格子 */

/** 分页网格:HorizontalPager + 每页 5×6,空位全部显示"添加" */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PagerGrid(
    pagerState: androidx.compose.foundation.pager.PagerState,
    quickAppIds: List<String>,
    vm: MeowMicViewModel,
    editMode: Boolean,
    onLaunch: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
) {
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        pageSpacing = 8.dp,
    ) { pageIndex ->
        val start = pageIndex * PAGE_SIZE
        val end = minOf(start + PAGE_SIZE, quickAppIds.size)
        val pageItems = quickAppIds.subList(start, end)

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (row in 0 until GRID_ROWS) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (col in 0 until GRID_COLUMNS) {
                        val index = row * GRID_COLUMNS + col
                        Box(modifier = Modifier.weight(1f)) {
                            val appId = pageItems.getOrNull(index)
                            if (appId != null) {
                                QuickAppCell(
                                    appId = appId,
                                    name = vm.findApp(appId)?.name ?: appId,
                                    vm = vm,
                                    editMode = editMode,
                                    onLaunch = onLaunch,
                                    onRemove = onRemove,
                                )
                            } else {
                                // 所有空位都显示"添加"(对齐设计稿)
                                AddCell(onClick = onAdd)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 快捷启动格子:图标 + 名称,点击启动,长按(编辑模式)删除 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickAppCell(
    appId: String,
    name: String,
    vm: MeowMicViewModel,
    editMode: Boolean,
    onLaunch: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (editMode) onRemove(appId) else onLaunch(appId)
                },
                onLongClick = { onRemove(appId) },
            )
            .padding(start = 2.dp, end = 2.dp, top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            AppIconBox(appId = appId, vm = vm)
            // 编辑模式:删除角标
            if (editMode) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(16.dp),
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

/** 应用图标容器:48×48 圆角方块,显示 PC 真实图标或占位 */
@Composable
private fun AppIconBox(appId: String, vm: MeowMicViewModel) {
    val v by vm.iconVersion.collectAsState() // 订阅图标更新
    val bmp: Bitmap? = vm.iconCache[appId]
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                RoundedCornerShape(12.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = appId,
                modifier = Modifier.size(28.dp),
            )
        } else {
            // 未加载或加载失败:触发加载 + 占位图标
            LaunchedEffect(appId) { vm.loadIcon(appId) }
            Icon(
                Icons.Default.Apps,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
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

/** 空位"添加"格子(虚线边框 + 加号图标) */
@Composable
private fun AddCell(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
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

/** 底部 Dock 栏:前 4 个快捷应用 */
@Composable
private fun DockBar(
    quickAppIds: List<String>,
    vm: MeowMicViewModel,
    onLaunch: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in 0 until DOCK_COUNT) {
                val appId = quickAppIds.getOrNull(i)
                if (appId != null) {
                    DockItem(
                        appId = appId,
                        vm = vm,
                        onClick = { onLaunch(appId) },
                    )
                } else {
                    // 空位占位(对齐设计稿 dock-item 透明背景)
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                RoundedCornerShape(8.dp),
                            ),
                    )
                }
            }
        }
    }
}

/** Dock 单项:32×32 圆角图标(对齐设计稿 dock-item) */
@Composable
private fun DockItem(appId: String, vm: MeowMicViewModel, onClick: () -> Unit) {
    val v by vm.iconVersion.collectAsState()
    val bmp: Bitmap? = vm.iconCache[appId]
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = appId,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                LaunchedEffect(appId) { vm.loadIcon(appId) }
                Icon(
                    Icons.Default.Apps,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 添加应用对话框:从 PC 应用库中挑选未添加的应用 */
@Composable
private fun AddAppDialog(
    appListState: AppListState,
    quickAppIds: List<String>,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val apps = (appListState as? AppListState.Loaded)?.apps ?: emptyList()
    val available = apps.filter { it.id !in quickAppIds }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加应用") },
        text = {
            if (apps.isEmpty()) {
                Text(
                    if (appListState is AppListState.Loading) "加载中..."
                    else "应用库为空",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            } else if (available.isEmpty()) {
                Text("已添加全部应用", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    available.forEach { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAdd(app.id) }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
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
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}
