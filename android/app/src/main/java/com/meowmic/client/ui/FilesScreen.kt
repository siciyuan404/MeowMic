package com.meowmic.client.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowmic.client.ConnectionState
import com.meowmic.client.LauncherRepository
import com.meowmic.client.MeowMicViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 文件传输页(远程文件管理)
 *
 * 数据流:
 * - GET  /file/list      列出目录
 * - GET  /file/download   下载文件
 * - POST /file/upload     上传文件
 * - POST /file/mkdir      新建目录
 * - POST /file/delete     删除
 * - POST /file/rename     重命名
 *
 * UI 结构:
 * - 顶部:ConnBar + ActionBar(页面切换 + 新建目录 + 上传 + 下载 + 断开)
 * - 路径栏:面包屑 + 当前路径
 * - 文件列表:目录优先,图标 + 名称 + 大小 + 修改时间
 * - 操作:点击进入目录;长按文件多选/重命名/删除
 */
@Composable
fun FilesScreen(
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

    // 当前目录(空 = 根目录/盘符列表)
    var currentPath by remember { mutableStateOf("") }
    var listing by remember { mutableStateOf<LauncherRepository.FileListing?>(null) }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // 选中的文件(用于操作菜单)
    var selectedPath by remember { mutableStateOf<String?>(null) }
    var showActions by remember { mutableStateOf(false) }
    // 新建目录对话框
    var showMkdirDialog by remember { mutableStateOf(false) }
    var mkdirName by remember { mutableStateOf("") }
    // 重命名对话框
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTo by remember { mutableStateOf("") }
    // 上传/下载状态
    var transferStatus by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 文件选择器(上传)
    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                val ok = uploadFromUri(context, addr, vm.clientPubkeyB64(), it, currentPath) { msg ->
                    transferStatus = msg
                }
                if (ok) {
                    transferStatus = "上传完成"
                    refresh(addr, vm, currentPath) { l, e ->
                        listing = l; errorMsg = e; loading = false
                    }
                } else {
                    transferStatus = "上传失败"
                }
            }
        }
    }

    // 下载保存选择器
    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val srcPath = selectedPath ?: return@rememberLauncherForActivityResult
        uri?.let {
            scope.launch {
                val ok = downloadToUri(context, addr, vm.clientPubkeyB64(), srcPath, it) { msg ->
                    transferStatus = msg
                }
                transferStatus = if (ok) "下载完成" else "下载失败"
            }
        }
        selectedPath = null
    }

    // 加载目录
    LaunchedEffect(currentPath, addr) {
        if (addr.isBlank()) return@LaunchedEffect
        loading = true
        errorMsg = null
        val pk = vm.clientPubkeyB64()
        val result = LauncherRepository.listFiles(addr, currentPath, pk)
        if (result != null) {
            listing = result
            currentPath = result.current
        } else {
            errorMsg = "无法读取目录"
        }
        loading = false
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

            // 2. 顶部操作栏:页面切换 + 新建目录 + 上传 + 下载 + 断开
            FilesActionBar(
                onBack = onBack,
                onNavigate = onNavigate,
                onMkdir = { showMkdirDialog = true },
                onUpload = { uploadLauncher.launch(arrayOf("*/*")) },
                onDownload = {
                    if (selectedPath != null) {
                        val name = selectedPath!!.substringAfterLast('\\').substringAfterLast('/')
                        downloadLauncher.launch(name)
                    }
                },
                onDisconnect = onDisconnect,
            )

            // 3. 路径栏:上级按钮 + 面包屑
            PathBar(
                current = listing?.current ?: "",
                parent = listing?.parent,
                onNavigateUp = { parent ->
                    currentPath = parent ?: ""
                },
                onNavigateTo = { currentPath = it },
            )

            // 4. 文件列表
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
                            TextButton(onClick = {
                                currentPath = listing?.current ?: ""
                            }) { Text("重试") }
                        }
                    }
                    listing != null -> {
                        FileList(
                            items = listing!!.items,
                            onItemClick = { entry ->
                                if (entry.isDir) {
                                    currentPath = entry.path
                                    selectedPath = null
                                } else {
                                    selectedPath = entry.path
                                    showActions = true
                                }
                            },
                        )
                    }
                }
            }

            // 5. 传输状态条
            if (transferStatus != null) {
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
                        Text(transferStatus!!, fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }
    }

    // 新建目录对话框
    if (showMkdirDialog) {
        AlertDialog(
            onDismissRequest = { showMkdirDialog = false; mkdirName = "" },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = mkdirName,
                    onValueChange = { mkdirName = it },
                    label = { Text("文件夹名", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (mkdirName.isNotBlank() && addr.isNotBlank()) {
                            scope.launch {
                                val parent = listing?.current ?: ""
                                val newPath = if (parent.isBlank()) mkdirName else "$parent\\$mkdirName"
                                val ok = LauncherRepository.mkdir(addr, newPath, vm.clientPubkeyB64())
                                if (ok) {
                                    showMkdirDialog = false
                                    mkdirName = ""
                                    // 刷新当前目录
                                    refresh(addr, vm, currentPath) { l, e ->
                                        listing = l; errorMsg = e; loading = false
                                    }
                                } else {
                                    transferStatus = "创建失败"
                                }
                            }
                        }
                    }
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showMkdirDialog = false; mkdirName = "" }) { Text("取消") } },
        )
    }

    // 文件操作菜单(点击文件弹出:下载/重命名/删除)
    if (showActions && selectedPath != null) {
        AlertDialog(
            onDismissRequest = { showActions = false; selectedPath = null },
            title = {
                Text(
                    selectedPath!!.substringAfterLast('\\').substringAfterLast('/'),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val path = selectedPath!!
                    TextButton(
                        onClick = {
                            showActions = false
                            val name = path.substringAfterLast('\\').substringAfterLast('/')
                            downloadLauncher.launch(name)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("下载到手机")
                    }
                    TextButton(
                        onClick = {
                            showActions = false
                            renameTo = path.substringAfterLast('\\').substringAfterLast('/')
                            showRenameDialog = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("重命名")
                    }
                    TextButton(
                        onClick = {
                            showActions = false
                            val toDelete = path
                            selectedPath = null
                            scope.launch {
                                val ok = LauncherRepository.deleteFile(addr, toDelete, vm.clientPubkeyB64())
                                transferStatus = if (ok) "已删除" else "删除失败"
                                refresh(addr, vm, currentPath) { l, e ->
                                    listing = l; errorMsg = e; loading = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {},
        )
    }

    // 重命名对话框
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false; renameTo = "" },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = renameTo,
                    onValueChange = { renameTo = it },
                    label = { Text("新名称", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val srcPath = selectedPath ?: return@TextButton
                        if (renameTo.isNotBlank() && addr.isNotBlank()) {
                            scope.launch {
                                val parentDir = srcPath.substringBeforeLast('\\').ifEmpty { srcPath.substringBeforeLast('/') }
                                val newPath = if (parentDir.isBlank()) renameTo else "$parentDir\\$renameTo"
                                val ok = LauncherRepository.renameFile(addr, srcPath, newPath, vm.clientPubkeyB64())
                                if (ok) {
                                    showRenameDialog = false
                                    renameTo = ""
                                    selectedPath = null
                                    transferStatus = "已重命名"
                                    refresh(addr, vm, currentPath) { l, e ->
                                        listing = l; errorMsg = e; loading = false
                                    }
                                } else {
                                    transferStatus = "重命名失败"
                                }
                            }
                        }
                    }
                ) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false; renameTo = "" }) { Text("取消") } },
        )
    }
}

/** 文件传输页顶部操作栏 */
@Composable
private fun FilesActionBar(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    onMkdir: () -> Unit,
    onUpload: () -> Unit,
    onDownload: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val btnSize = 24.dp
    val icSize = 14.dp
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧:页面切换组(激活 = files)
        PageSwitcher(
            currentView = "files",
            onBack = onBack,
            onNavigate = onNavigate,
            btnSize = btnSize,
            iconSize = icSize,
        )

        Spacer(Modifier.weight(1f))

        // 右侧上下文:新建目录 + 上传 + 下载
        IconButtonSmall(
            icon = Icons.Default.CreateNewFolder,
            contentDescription = "新建文件夹",
            onClick = onMkdir,
            buttonSize = btnSize,
            iconSize = icSize,
        )
        IconButtonSmall(
            icon = Icons.Default.FileUpload,
            contentDescription = "上传文件",
            onClick = onUpload,
            buttonSize = btnSize,
            iconSize = icSize,
        )
        IconButtonSmall(
            icon = Icons.Default.FileDownload,
            contentDescription = "下载文件",
            onClick = onDownload,
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

/** 路径栏:上级按钮 + 面包屑(横向滚动) */
@Composable
private fun PathBar(
    current: String,
    parent: String?,
    onNavigateUp: (String?) -> Unit,
    onNavigateTo: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 上级按钮
            if (parent != null) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onNavigateUp(parent) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.ArrowUpward,
                        contentDescription = "上级目录",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // 面包屑
            if (current.isBlank()) {
                Text("我的电脑", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                // 拆分为各级: C:\Users\62744 → [C:, Users, 62744]
                val parts = current.split('\\', '/').filter { it.isNotBlank() }
                parts.forEachIndexed { index, part ->
                    if (index > 0) {
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                    val fullPath = if (index == 0 && part.endsWith(":")) {
                        "$part\\"
                    } else {
                        parts.subList(0, index + 1).joinToString("\\")
                    }
                    Text(
                        part,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .clickable { onNavigateTo(fullPath) }
                            .padding(horizontal = 2.dp, vertical = 2.dp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 文件列表 */
@Composable
private fun FileList(
    items: List<LauncherRepository.FileEntry>,
    onItemClick: (LauncherRepository.FileEntry) -> Unit,
) {
    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("空目录", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        items(items, key = { it.path }) { entry ->
            FileItem(entry = entry, onClick = { onItemClick(entry) })
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        }
    }
}

/** 单个文件条目 */
@Composable
private fun FileItem(entry: LauncherRepository.FileEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 图标(目录用 Folder,文件按扩展名)
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = fileIcon(entry),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (entry.isDir) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 名称 + 元信息
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.name,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!entry.isDir) {
                    Text(
                        formatFileSize(entry.size),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                if (entry.modified > 0) {
                    Text(
                        formatModified(entry.modified),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                if (entry.readonly) {
                    Text(
                        "只读",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    )
                }
            }
        }
        // 进入箭头(目录)
        if (entry.isDir) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}

/** 根据扩展名返回文件图标 */
private fun fileIcon(entry: LauncherRepository.FileEntry): androidx.compose.ui.graphics.vector.ImageVector {
    if (entry.isDir) return Icons.Default.Folder
    val ext = entry.name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "txt", "md", "log" -> Icons.Default.Description
        "png", "jpg", "jpeg", "gif", "bmp", "webp" -> Icons.Default.Image
        "mp3", "wav", "flac", "aac", "ogg" -> Icons.Default.AudioFile
        "mp4", "mkv", "avi", "mov", "wmv" -> Icons.Default.VideoFile
        "pdf" -> Icons.Default.PictureAsPdf
        "zip", "rar", "7z", "tar", "gz" -> Icons.Default.FolderZip
        "exe", "msi", "bat", "ps1" -> Icons.Default.Apps
        "doc", "docx", "rtf" -> Icons.Default.Article
        "xls", "xlsx", "csv" -> Icons.Default.TableChart
        "ppt", "pptx" -> Icons.Default.Slideshow
        else -> Icons.Default.InsertDriveFile
    }
}

// ── 上传/下载实现(挂起函数,在协程中调用) ──

private suspend fun uploadFromUri(
    context: Context,
    serverAddr: String,
    pubkey: String,
    uri: Uri,
    targetDir: String,
    onProgress: (String) -> Unit,
): Boolean = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    // 从 URI 获取文件名
    var fileName = ""
    resolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) {
            fileName = cursor.getString(idx) ?: ""
        }
    }
    if (fileName.isBlank()) fileName = "upload_${System.currentTimeMillis()}"
    val targetPath = if (targetDir.isBlank()) fileName else "$targetDir\\$fileName"
    onProgress("上传中: $fileName")
    val data = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext false
    LauncherRepository.uploadFile(serverAddr, targetPath, data, pubkey)
}

private suspend fun downloadToUri(
    context: Context,
    serverAddr: String,
    pubkey: String,
    srcPath: String,
    targetUri: Uri,
    onProgress: (String) -> Unit,
): Boolean = withContext(Dispatchers.IO) {
    val name = srcPath.substringAfterLast('\\').substringAfterLast('/')
    onProgress("下载中: $name")
    val data = LauncherRepository.downloadFile(serverAddr, srcPath, pubkey) ?: return@withContext false
    context.contentResolver.openOutputStream(targetUri)?.use { it.write(data) } ?: return@withContext false
    true
}

/** 刷新当前目录(供对话框操作后调用) */
private suspend fun refresh(
    addr: String,
    vm: MeowMicViewModel,
    currentPath: String,
    onResult: (LauncherRepository.FileListing?, String?) -> Unit,
) {
    val pk = vm.clientPubkeyB64()
    val result = LauncherRepository.listFiles(addr, currentPath, pk)
    if (result != null) onResult(result, null) else onResult(null, "无法读取目录")
}
