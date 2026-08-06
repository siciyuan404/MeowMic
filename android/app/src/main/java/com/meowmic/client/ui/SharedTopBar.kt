package com.meowmic.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/**
 * 页面切换组:返回 + 分隔线 + 触控/快捷启动/语音/键盘/显示器/文件 6 个按钮
 *
 * 当前页对应的按钮高亮,其他页面通过 [currentView] 标识。
 * 对齐设计稿 mm-actionbar 左侧部分。
 */
@Composable
internal fun PageSwitcher(
    currentView: String,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    btnSize: androidx.compose.ui.unit.Dp = 24.dp,
    iconSize: androidx.compose.ui.unit.Dp = 14.dp,
) {
    // 返回按钮
    IconButtonSmall(
        icon = Icons.Default.ArrowBack,
        contentDescription = "返回",
        onClick = onBack,
        buttonSize = btnSize,
        iconSize = iconSize,
    )
    ActionBarDivider()
    // 6 个页面切换按钮
    ToggleButtonSmall(
        icon = Icons.Default.Mouse,
        contentDescription = "触控",
        isOn = currentView == "touch",
        onClick = { onNavigate("touch") },
        buttonSize = btnSize,
        iconSize = iconSize,
    )
    ToggleButtonSmall(
        icon = Icons.Default.GridView,
        contentDescription = "快捷启动",
        isOn = currentView == "launcher",
        onClick = { onNavigate("launcher") },
        buttonSize = btnSize,
        iconSize = iconSize,
    )
    ToggleButtonSmall(
        icon = Icons.Default.GraphicEq,
        contentDescription = "语音",
        isOn = currentView == "audio",
        onClick = { onNavigate("audio") },
        buttonSize = btnSize,
        iconSize = iconSize,
    )
    ToggleButtonSmall(
        icon = Icons.Default.Keyboard,
        contentDescription = "键盘",
        isOn = currentView == "keyboard",
        onClick = { onNavigate("keyboard") },
        buttonSize = btnSize,
        iconSize = iconSize,
    )
    ToggleButtonSmall(
        icon = Icons.Default.Monitor,
        contentDescription = "显示器",
        isOn = currentView == "monitor",
        onClick = { onNavigate("monitor") },
        buttonSize = btnSize,
        iconSize = iconSize,
    )
    ToggleButtonSmall(
        icon = Icons.Default.Folder,
        contentDescription = "文件",
        isOn = currentView == "files",
        onClick = { onNavigate("files") },
        buttonSize = btnSize,
        iconSize = iconSize,
    )
}

/**
 * 抽取统计字段为可读字符串(供各页面 ConnBar 展开详情复用)
 */
internal fun parseStats(stats: String): Pair<Long, Long> {
    return try {
        val json = JSONObject(stats)
        val touch = json.optLong("touch_sent", 0)
        val audio = json.optLong("audio_sent", 0)
        touch to audio
    } catch (_: Exception) {
        0L to 0L
    }
}

/**
 * 格式化文件大小(字节 → KB/MB/GB)
 */
internal fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "${bytes} B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    }
}

/**
 * 格式化 Unix 时间戳为本地时间字符串
 */
internal fun formatModified(modified: Long): String {
    if (modified == 0L) return ""
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(modified * 1000))
}


/** 小尺寸图标按钮(对齐设计稿顶栏圆角方块,尺寸按方向自适应)
 *
 * 统一定义在 SharedTopBar.kt,供 LauncherScreen / TouchpadScreen / MonitorScreen / FilesScreen 复用。
 * 默认 buttonSize=28dp / iconSize=16dp(沿用 TouchpadScreen 原有默认值)。
 */
@Composable
internal fun IconButtonSmall(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    isDanger: Boolean = false,
    buttonSize: androidx.compose.ui.unit.Dp = 28.dp,
    iconSize: androidx.compose.ui.unit.Dp = 16.dp,
) {
    val tint = if (isDanger) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(buttonSize)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(6.dp),
            )
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(iconSize), tint = tint)
    }
}

/** 带激活态的小图标按钮(用于顶栏开关组)
 *
 * 激活时:主色 12% 背景 + 主色图标;未激活时:surfaceVariant 背景 + onSurfaceVariant 图标
 * 与 IconButtonSmall 等宽,便于顶栏对齐。
 */
@Composable
internal fun ToggleButtonSmall(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    isOn: Boolean,
    onClick: () -> Unit,
    tintOff: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    tintOn: Color = MaterialTheme.colorScheme.primary,
    buttonSize: androidx.compose.ui.unit.Dp = 28.dp,
    iconSize: androidx.compose.ui.unit.Dp = 16.dp,
) {
    Box(
        modifier = Modifier
            .size(buttonSize)
            .background(
                if (isOn) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(6.dp),
            )
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(iconSize), tint = if (isOn) tintOn else tintOff)
    }
}

/** 操作栏分隔线(1dp 宽 20dp 高,左右 2dp 间距) */
@Composable
internal fun ActionBarDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .width(1.dp)
            .height(20.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}
