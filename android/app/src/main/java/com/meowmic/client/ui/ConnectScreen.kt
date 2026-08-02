package com.meowmic.client.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.meowmic.client.ConnectionState
import com.meowmic.client.MeowMicViewModel
import com.meowmic.client.NativeBridge
import com.meowmic.client.PairingMode
import com.meowmic.client.PcEntry
import com.meowmic.client.ServerStatus
import com.meowmic.client.UpdateState
import com.meowmic.client.normalizeAddress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    vm: MeowMicViewModel,
    onConnected: () -> Unit,
) {
    val context = LocalContext.current
    val connectionState by vm.connectionState.collectAsState()
    val historyAddresses by vm.historyAddresses.collectAsState()
    val lastAddr by vm.lastAddr.collectAsState()
    val pcList by vm.pcList.collectAsState()
    val pairingRequired by vm.pairingRequired.collectAsState()
    val pairingSubmitting by vm.pairingSubmitting.collectAsState()
    val wolFeedback by vm.wolFeedback.collectAsState()

    // 默认地址:优先用上次输入的,其次用历史第一条,最后留空(由用户输入或点选列表)
    var serverAddr by remember {
        mutableStateOf(lastAddr.ifBlank { historyAddresses.firstOrNull() ?: "" })
    }
    // 地址实时校验:非空但无法规范化时提示(裸 IP 会自动补 :28900)
    val addrInvalid = serverAddr.isNotBlank() && normalizeAddress(serverAddr) == null
    var clientName by remember { mutableStateOf("Android-Client") }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var showHistoryMenu by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
    }

    LaunchedEffect(Unit) {
        vm.init(context)
    }

    // 进入页面启动 mDNS 发现,离开时停止
    DisposableEffect(Unit) {
        vm.startDiscovery()
        onDispose { vm.stopDiscovery() }
    }

    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected) {
            onConnected()
        }
    }

    // WoL 唤醒结果一次性提示
    LaunchedEffect(wolFeedback) {
        wolFeedback?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            vm.clearWolFeedback()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasMicPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Computer,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "MeowMic",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "极低延迟手机外设",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        // 统一 PC 列表(mDNS 发现 + 手动添加,点击直连)
        if (pcList.isNotEmpty()) {
            Text(
                text = "我的电脑",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            PcListView(
                entries = pcList,
                isConnecting = connectionState is ConnectionState.Connecting,
                onPick = { entry ->
                    serverAddr = entry.primaryAddr
                    vm.connectPc(entry, clientName)
                },
                onRemove = { entry -> vm.removeManualPc(entry.id) },
                onWake = { entry -> vm.wakePc(entry) },
            )
            Spacer(Modifier.height(20.dp))

            Text(
                text = "或手动输入地址",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
        }

        ExposedDropdownMenuBox(
            expanded = showHistoryMenu,
            onExpandedChange = { showHistoryMenu = it }
        ) {
            OutlinedTextField(
                value = serverAddr,
                onValueChange = { serverAddr = it },
                label = { Text("PC 端地址") },
                placeholder = { Text("如 192.168.1.12(自动补 :28900)") },
                singleLine = true,
                isError = addrInvalid,
                supportingText = {
                    if (addrInvalid) {
                        Text("格式:IP 或 IP:端口,如 192.168.1.12:28900")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                leadingIcon = { Icon(Icons.Default.SettingsEthernet, contentDescription = null) },
                trailingIcon = {
                    if (historyAddresses.isNotEmpty()) {
                        Text(
                            text = if (showHistoryMenu) "▲" else "▼",
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .clickable { showHistoryMenu = !showHistoryMenu },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
            )

            if (historyAddresses.isNotEmpty()) {
                ExposedDropdownMenu(
                    expanded = showHistoryMenu,
                    onDismissRequest = { showHistoryMenu = false }
                ) {
                    historyAddresses.forEach { addr ->
                        DropdownMenuItem(
                            text = { Text(addr) },
                            onClick = {
                                serverAddr = addr
                                showHistoryMenu = false
                            }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = clientName,
            onValueChange = { clientName = it },
            label = { Text("设备名(可选)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))

        if (!hasMicPermission) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "缺少麦克风权限",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "语音输入功能需要录音权限,点击下方按钮重新申请",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("申请麦克风权限")
            }
            Spacer(Modifier.height(12.dp))
        }

        if (!NativeBridge.isLoaded()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "libmeowmic.so 未加载,仅触控可用,语音功能不可用",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        val isConnecting = connectionState is ConnectionState.Connecting
        val addrValid = normalizeAddress(serverAddr) != null
        Button(
            onClick = { vm.connect(serverAddr, clientName) },
            enabled = !isConnecting && addrValid,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(12.dp))
                Text("连接中...")
            } else {
                Text("连接", fontSize = 16.sp)
            }
        }

        // 把当前输入的地址保存到"我的电脑"列表(借鉴 Moonlight 的手动添加 PC)
        TextButton(
            onClick = { vm.addManualPc(serverAddr) },
            enabled = !isConnecting && addrValid,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text("＋ 添加到我的电脑", fontSize = 13.sp)
        }

        Spacer(Modifier.height(20.dp))
        (connectionState as? ConnectionState.Error)?.let { err ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        err.message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
    // 底部更新面板
    UpdatePanel(
        vm = vm,
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
    )
    }

    // 配对对话框(正向输入 / 反向显示双模式)
    pairingRequired?.let { state ->
        PinPairingDialog(
            serverAddr = state.serverAddr,
            mode = state.mode,
            reversePin = state.reversePin,
            errorMessage = state.errorMessage,
            submitting = pairingSubmitting,
            onSubmit = { pin -> vm.completePairing(pin) },
            onSwitchToReverse = { vm.startReversePairing() },
            onSwitchToForward = { vm.backToForwardPairing() },
            onDismiss = { vm.cancelPairing() },
        )
    }
}

/**
 * 统一 PC 列表:每行一台 PC,带三态状态指示、配对徽标、多地址提示
 *
 * 状态视觉:
 * - ONLINE:  绿色圆点 + "在线",正常可点
 * - UNKNOWN: 灰色圆点 + "检测中...",正常可点
 * - OFFLINE: 红色圆点 + "离线",整行 alpha 降低,仍可点(用户可手动重试);
 *   若已知 MAC(此前 serverinfo 收集),额外显示"唤醒"按钮(Wake-on-LAN)
 * - 配对徽标:绿色"已配对" / 橙色"未配对" / 未知不显示
 * - 手动添加的条目右侧显示删除按钮
 */
@Composable
private fun PcListView(
    entries: List<PcEntry>,
    isConnecting: Boolean,
    onPick: (PcEntry) -> Unit,
    onRemove: (PcEntry) -> Unit,
    onWake: (PcEntry) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            entries.forEachIndexed { idx, entry ->
                if (idx > 0) HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
                val rowAlpha = if (entry.status == ServerStatus.OFFLINE) 0.5f else 1f
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isConnecting) { onPick(entry) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = rowAlpha),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = entry.name,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = rowAlpha),
                                fontSize = 14.sp,
                            )
                            when (entry.paired) {
                                true -> {
                                    Spacer(Modifier.width(8.dp))
                                    PairedBadge("已配对", MaterialTheme.colorScheme.primary)
                                }
                                false -> {
                                    Spacer(Modifier.width(8.dp))
                                    PairedBadge("未配对", MaterialTheme.colorScheme.tertiary)
                                }
                                null -> {}
                            }
                        }
                        val addrText = if (entry.addresses.size > 1) {
                            "${entry.primaryAddr} 等 ${entry.addresses.size} 个地址"
                        } else {
                            entry.primaryAddr
                        }
                        Text(
                            text = addrText,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f * rowAlpha),
                        )
                    }
                    // OFFLINE 且已知 MAC:显示 WoL 唤醒按钮(整行点击仍是重连)
                    if (entry.status == ServerStatus.OFFLINE && entry.mac.isNotEmpty()) {
                        Text(
                            text = "唤醒",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .clickable(enabled = !isConnecting) { onWake(entry) }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    if (entry.manual) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "移除",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                            modifier = Modifier
                                .size(18.dp)
                                .clickable(enabled = !isConnecting) { onRemove(entry) },
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    StatusBadge(status = entry.status)
                }
            }
        }
    }
}

/**
 * 配对状态小徽标(已配对/未配对)
 */
@Composable
private fun PairedBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            color = color,
        )
    }
}

/**
 * 状态指示器:小圆点 + 状态文字
 */
@Composable
private fun StatusBadge(status: ServerStatus) {
    val (color, label) = when (status) {
        ServerStatus.ONLINE -> MaterialTheme.colorScheme.primary to "在线"
        ServerStatus.UNKNOWN -> MaterialTheme.colorScheme.outline to "检测中"
        ServerStatus.OFFLINE -> MaterialTheme.colorScheme.error to "离线"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = color,
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

/**
 * 更新面板:显示版本号 + 检查更新 + 下载进度 + 安装
 */
@Composable
private fun UpdatePanel(vm: MeowMicViewModel, modifier: Modifier = Modifier) {
    val updateState by vm.updateState.collectAsState()
    val currentVer = remember { vm.currentVersion() }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "v$currentVer",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                when (val s = updateState) {
                    is UpdateState.Idle, is UpdateState.UpToDate -> {
                        TextButton(
                            onClick = { vm.checkForUpdate() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text("检查更新", fontSize = 12.sp)
                        }
                    }
                    is UpdateState.Checking -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("检查中...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is UpdateState.Available -> {
                        Button(
                            onClick = { vm.downloadUpdate() },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("下载 v${s.version}", fontSize = 12.sp)
                        }
                    }
                    is UpdateState.Downloading -> {
                        Text(
                            "下载中 ${s.progress}%",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is UpdateState.ReadyToInstall -> {
                        Button(
                            onClick = { vm.installUpdate() },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("安装更新", fontSize = 12.sp)
                        }
                    }
                    is UpdateState.Error -> {
                        Text(
                            s.message,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        TextButton(
                            onClick = { vm.checkForUpdate() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text("重试", fontSize = 12.sp)
                        }
                    }
                }
            }

            // 下载进度条
            (updateState as? UpdateState.Downloading)?.let { d ->
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { d.progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            }

            // 更新日志
            (updateState as? UpdateState.Available)?.let { a ->
                if (a.notes.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        a.notes,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }

            // 已是最新
            if (updateState is UpdateState.UpToDate) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "已是最新版本",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 配对 PIN 对话框(双模式,借鉴 Sunshine 双向配对)
 *
 * - ENTER_PIN(正向,NVIDIA 方向):PC 控制台显示 PIN,手机端输入后提交
 * - SHOW_PIN(反向,Sunshine 方向):手机生成并显示 PIN,用户在 PC 控制台输入;
 *   手机后台自动重试提交,PC 确认后自动完成配对
 *
 * 配对成功后,客户端会持久化服务端公钥,后续连接自动用 HelloPaired 跳过此对话框。
 */
@Composable
private fun PinPairingDialog(
    serverAddr: String,
    mode: PairingMode,
    reversePin: String?,
    errorMessage: String?,
    submitting: Boolean,
    onSubmit: (String) -> Unit,
    onSwitchToReverse: () -> Unit,
    onSwitchToForward: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {
            // 正向提交中不允许点外部关闭,避免状态错乱;反向模式随时可关
            if (mode == PairingMode.SHOW_PIN || !submitting) onDismiss()
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.SettingsEthernet,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text("设备配对", fontWeight = FontWeight.SemiBold)
            }
        },
        text = {
            Column {
                Text(
                    "首次连接 $serverAddr 需要配对",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                if (mode == PairingMode.ENTER_PIN) {
                    ForwardPinContent(
                        errorMessage = errorMessage,
                        submitting = submitting,
                        onSubmit = onSubmit,
                    )
                } else {
                    ReversePinContent(reversePin = reversePin ?: "------")
                }
            }
        },
        confirmButton = {
            // 模式切换入口(提交按钮内嵌在 ForwardPinContent 中,
            // 因为 PIN 输入状态在子组件内;反向模式无需提交按钮)
            TextButton(onClick = if (mode == PairingMode.ENTER_PIN) onSwitchToReverse else onSwitchToForward) {
                Text(
                    if (mode == PairingMode.ENTER_PIN) "让 PC 输入手机上的 PIN"
                    else "改为输入 PC 上的 PIN",
                    fontSize = 12.sp,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = mode == PairingMode.SHOW_PIN || !submitting,
            ) {
                Text("取消")
            }
        },
    )
}

/** 正向配对内容:PIN 输入框 + 错误提示 + 提交按钮 */
@Composable
private fun ForwardPinContent(
    errorMessage: String?,
    submitting: Boolean,
    onSubmit: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    val isError = pin.isNotEmpty() && pin.length != 6

    Text(
        "请在 PC 端控制台「设备配对」面板查看 6 位 PIN",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = pin,
        onValueChange = { value ->
            // 只允许数字,最多 6 位
            pin = value.filter { it.isDigit() }.take(6)
        },
        label = { Text("PIN") },
        singleLine = true,
        isError = isError || errorMessage != null,
        supportingText = {
            when {
                errorMessage != null -> Text(
                    errorMessage,
                    color = MaterialTheme.colorScheme.error,
                )
                isError -> Text("PIN 为 6 位数字")
                else -> Text("6 位数字")
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
        ),
        enabled = !submitting,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = { onSubmit(pin) },
        enabled = !submitting && pin.length == 6,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (submitting) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(8.dp))
            Text("配对中...")
        } else {
            Text("配对")
        }
    }
}

/** 反向配对内容:大字号显示本机 PIN + 等待确认动画 */
@Composable
private fun ReversePinContent(reversePin: String) {
    Text(
        "请在 PC 端控制台「设备配对」面板的「反向配对」输入框中输入:",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    // 3+3 分组显示,方便在 PC 侧对照输入
    val grouped = reversePin.take(3) + " " + reversePin.drop(3)
    Text(
        text = grouped,
        fontSize = 40.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 4.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth(),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
    Spacer(Modifier.height(16.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "等待 PC 确认…(自动重试中)",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
