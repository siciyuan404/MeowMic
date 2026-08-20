package com.meowmic.client.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.meowmic.client.LauncherRepository

/**
 * 远程视频播放全屏对话框
 *
 * 用 ExoPlayer 直接流式播放 PC 上的视频文件:
 * - URL 走新增的 GET /file/stream(服务端支持 HTTP Range → 进度条可拖动 seek)
 * - 复用配对鉴权:URL 带 pubkey 查询参数,播放前需已配对
 * - 内置播放器控制器(播放/暂停/进度/全屏),播放时保持屏幕常亮
 */
@Composable
fun VideoPlayerDialog(
    serverAddr: String,
    path: String,
    pubkey: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val url = remember(path) { LauncherRepository.streamUrl(serverAddr, path, pubkey) }

    // PlayerView 引用:释放前先把 player 解绑,避免释放后 View 回调 NPE
    val viewRef = remember { arrayOfNulls<PlayerView>(1) }

    val player = remember(path) {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            setHandleAudioBecomingNoisy(true)
            setMediaItem(MediaItem.fromUri(url))
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewRef[0]?.player = null
            player.release()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        viewRef[0] = this
                        this.player = player
                        useController = true
                        setKeepScreenOn(true)
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // 顶部右上角关闭按钮
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                    .clickable { onDismiss() }
                    .padding(8.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "关闭播放",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
