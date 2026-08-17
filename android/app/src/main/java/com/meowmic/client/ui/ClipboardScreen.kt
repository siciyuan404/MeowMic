package com.meowmic.client.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowmic.client.ConnectionState
import com.meowmic.client.LauncherRepository
import com.meowmic.client.MeowMicViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 剪贴板同步页(手机 ↔ PC)
 *
 * 数据流(全部走 serverinfo HTTP 端口 base_port+4):
 * - GET  /clipboard/list    历史列表(5s 轮询,PC 新复制的内容自动出现)
 * - POST /clipboard/set     设为 PC 当前剪贴板(新建条目 / 条目"同步到 PC")
 * - POST /clipboard/update  编辑历史条目(编辑后置顶并成为 PC 当前剪贴板)
 * - POST /clipboard/delete  删除条目
 * - POST /clipboard/clear   清空历史
 *
 * UI 结构:
 * - 顶部:ConnBar + ActionBar(新建 / 刷新 / 清空 / 断开)
 * - 列表:文本预览 + 时间,操作行(复制到手机 / 同步到 PC / 编辑 / 删除)
 */
@Composable
fun ClipboardScreen(
    vm: MeowMicViewModel,
    onBack: () -> Unit,
    onDisconnect: () -> Unit = {},
    onNavigate: (String) -> Unit = {},
) {
    val connectionState by vm.connectionState.collectAsState()
    val stats by vm.stats.collectAsState()
    val addr = (connectionState as? ConnectionState.Connected)?.serverAddr ?: ""

    var connExpanded by remember { mutableStateOf(false) }
    val (touchSent, audioSent) = parseStats(stats)

    var entries by remember { mutableStateOf<List<LauncherRepository.ClipboardEntry>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var opStatus by remember { mutableStateOf<String?>(null) }

    // 编辑器:targetId=null 关闭;-1 新建;>0 编辑对应条目
    var editorTargetId by remember { mutableStateOf<Long?>(null) }
    var editorText by remember { mutableStateOf("") }
    // 清空确认
    var showClearDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 刷新列表(silent=true 静默,不显示 loading)
    suspend fun refresh(silent: Boolean) {
        if (!silent) {
            loading = true
            errorMsg = null
        }
        val list = LauncherRepository.listClipboard(addr, vm.clientPubkeyB64())
        if (list != null) {
            entries = list
            errorMsg = null
        } else if (!silent) {
            errorMsg = "无法获取 PC 剪贴板"
        }
        loading = false
    }

    // 首次加载 + 5s 轮询(PC 端新复制的内容自动同步到手机列表)
    LaunchedEffect(addr) {
        if (addr.isBlank()) return@LaunchedEffect
        while (true) {
            refresh(silent = entries != null)
            delay(5000)
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

            // 2. 顶部操作栏
            ClipboardActionBar(
                onBack = onBack,
                onNew = {
                    editorTargetId = -1L
                    editorText = ""
                },
                onRefresh = { scope.launch { refresh(false) } },
                onClear = { showClearDialog = true },
                onDisconnect = onDisconnect,
            )

            // 3. 列表
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    loading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    errorMsg != null -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(errorMsg!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                            TextButton(onClick = { scope.launch { refresh(false) } }) { Text("重试") }
                        }
                    }
                    entries != null && entries!!.isEmpty() -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                Icons.Default.ContentPaste,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.outline,
                            )
                            Text("暂无剪贴板内容", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                            Text(
                                "在 PC 上复制文本,或点击右上角 + 新建",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                    entries != null -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(entries!!, key = { it.id }) { entry ->
                                ClipboardItemCard(
                                    entry = entry,
                                    onCopyToLocal = {
                                        // 写入手机本地剪贴板
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("meowmic", entry.text))
                                        opStatus = "已复制到手机剪贴板"
                                    },
                                    onSyncToPc = {
                                        scope.launch {
                                            val ok = LauncherRepository.setClipboard(addr, entry.text, vm.clientPubkeyB64())
                                            opStatus = if (ok) "已设为 PC 当前剪贴板" else "同步失败"
                                            if (ok) refresh(true)
                                        }
                                    },
                                    onEdit = {
                                        editorTargetId = entry.id
                                        editorText = entry.text
                                    },
                                    onDelete = {
                                        scope.launch {
                                            val ok = LauncherRepository.deleteClipboardEntry(addr, entry.id, vm.clientPubkeyB64())
                                            opStatus = if (ok) "已删除" else "删除失败"
                                            if (ok) refresh(true)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // 4. 操作状态条
            if (opStatus != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(14.dp))
                        Text(
                            opStatus!!,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { opStatus = null },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text("关闭", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }

    // 新建/编辑对话框(-1=新建;>0=编辑)
    if (editorTargetId != null) {
        val isNew = editorTargetId == -1L
        AlertDialog(
            onDismissRequest = { editorTargetId = null },
            title = { Text(if (isNew) "新建剪贴板条目" else "编辑条目") },
            text = {
                OutlinedTextField(
                    value = editorText,
                    onValueChange = { editorText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    placeholder = { Text("输入文本") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 10,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val text = editorText.trim()
                        if (text.isEmpty()) return@TextButton
                        val targetId = editorTargetId ?: return@TextButton
                        scope.launch {
                            val ok = if (isNew) {
                                // 新建 = 设为 PC 当前剪贴板并置顶历史
                                LauncherRepository.setClipboard(addr, text, vm.clientPubkeyB64())
                            } else {
                                // 编辑 = 更新条目并同步为 PC 当前剪贴板
                                LauncherRepository.updateClipboardEntry(addr, targetId, text, vm.clientPubkeyB64())
                            }
                            opStatus = when {
                                ok && isNew -> "已写入 PC 剪贴板"
                                ok -> "已更新并同步到 PC 剪贴板"
                                else -> "操作失败"
                            }
                            if (ok) refresh(true)
                        }
                        editorTargetId = null
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editorTargetId = null }) { Text("取消") }
            },
        )
    }

    // 清空确认
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空剪贴板历史") },
            text = { Text("将删除 PC 端全部 ${entries?.size ?: 0} 条历史记录,不影响当前剪贴板内容。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        scope.launch {
                            val ok = LauncherRepository.clearClipboard(addr, vm.clientPubkeyB64())
                            opStatus = if (ok) "已清空" else "清空失败"
                            if (ok) refresh(true)
                        }
                    },
                ) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }
}

/** 顶部操作栏:返回 + 新建 + 刷新 + 清空 + 断开 */
@Composable
private fun ClipboardActionBar(
    onBack: () -> Unit,
    onNew: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
            }
            Text(
                "剪贴板",
                fontSize = 15.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            IconButton(onClick = onNew) {
                Icon(Icons.Default.Add, contentDescription = "新建")
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Delete, contentDescription = "清空")
            }
            IconButton(onClick = onDisconnect) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = "断开")
            }
        }
    }
}

/** 单条剪贴板历史卡片 */
@Composable
private fun ClipboardItemCard(
    entry: LauncherRepository.ClipboardEntry,
    onCopyToLocal: () -> Unit,
    onSyncToPc: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // 文本预览(最多 4 行)
            Text(
                entry.text,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    timeFmt.format(Date(entry.updatedAt * 1000)),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )
                // 操作按钮
                IconButton(onClick = onCopyToLocal, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "复制到手机",
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onSyncToPc, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "设为 PC 剪贴板",
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "编辑",
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
