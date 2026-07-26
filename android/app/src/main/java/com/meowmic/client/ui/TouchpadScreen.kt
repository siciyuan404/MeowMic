package com.meowmic.client.ui

import android.content.res.Configuration
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowmic.client.AudioInputManager
import com.meowmic.client.ConnectionState
import com.meowmic.client.MeowMicViewModel
import org.json.JSONObject

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TouchpadScreen(
    vm: MeowMicViewModel,
    onDisconnect: () -> Unit,
) {
    val connectionState by vm.connectionState.collectAsState()
    val stats by vm.stats.collectAsState()
    val configuration = LocalConfiguration.current

    val connected = connectionState as? ConnectionState.Connected
    val serverAddr = connected?.serverAddr ?: "未知"

    var touchMode by remember { mutableStateOf("移动") }
    var pointerCount by remember { mutableStateOf(0) }
    var micMode by remember { mutableStateOf("ptt") }
    var isMicActive by remember { mutableStateOf(false) }
    var muteSpeaker by remember { mutableStateOf(false) }
    val currentAudioMode by vm.currentAudioMode.collectAsState()
    var showFilePicker by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val path = it.path ?: return@let
            scope.launch {
                vm.playMusicFile(path)
            }
        }
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

    LaunchedEffect(micMode, currentAudioMode) {
        if (currentAudioMode == AudioInputManager.InputMode.MUSIC_FILE) {
            vm.setMicEnabled(false)
            isMicActive = false
        } else if (micMode == "continuous") {
            vm.setMicEnabled(true)
            isMicActive = true
        } else {
            vm.setMicEnabled(false)
            isMicActive = false
        }
    }

    LaunchedEffect(showFilePicker) {
        if (showFilePicker) {
            filePickerLauncher.launch(arrayOf("audio/*"))
            showFilePicker = false
        }
    }

    var touchSent = 0L
    var audioSent = 0L
    try {
        val json = JSONObject(stats)
        touchSent = json.optLong("touch_sent", 0)
        audioSent = json.optLong("audio_sent", 0)
    } catch (_: Exception) { }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Mouse,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        "已连接",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                    Text(
                        serverAddr,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text("Touch: $touchSent", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Audio: $audioSent", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "手势: $touchMode | 指: $pointerCount",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "横屏" else "竖屏",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                )
                .pointerInteropFilter { event ->
                    pointerCount = event.pointerCount
                    when {
                        event.pointerCount >= 3 -> touchMode = "三指"
                        event.pointerCount == 2 -> {
                            if (event.action == MotionEvent.ACTION_MOVE) {
                                val deltaY = event.getY(1) - event.getHistoricalY(1, 0)
                                touchMode = if (kotlin.math.abs(deltaY) > 0.5f) "滚动" else "双指"
                            } else {
                                touchMode = "双指"
                            }
                        }
                        else -> touchMode = when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> "按下"
                            MotionEvent.ACTION_MOVE -> "移动"
                            MotionEvent.ACTION_UP -> "抬起"
                            else -> "移动"
                        }
                    }
                    val motionEvent = event as? MotionEvent
                    if (motionEvent != null) {
                        vm.handleTouch(motionEvent)
                    }
                    true
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Default.Mouse,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.size(80.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "滑动此处移动鼠标",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "单指移动·双指滚动·点击单击",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
            ) {
                Text(
                    "🎙️ 语音控制",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val micModifier = if (micMode == "ptt") {
                        Modifier.pointerInput(micMode) {
                            detectTapGestures(
                                onPress = {
                                    isMicActive = true
                                    vm.setMicEnabled(true)
                                    tryAwaitRelease()
                                    isMicActive = false
                                    vm.setMicEnabled(false)
                                },
                            )
                        }
                    } else {
                        Modifier
                    }

                    Button(
                        onClick = {},
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .then(micModifier),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isMicActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(
                            if (isMicActive) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (isMicActive) "录音中" else if (micMode == "ptt") "长按说话" else "实时模式",
                            fontSize = 13.sp,
                        )
                    }

                    TextButton(
                        onClick = {
                            micMode = if (micMode == "ptt") "continuous" else "ptt"
                        },
                        modifier = Modifier.height(48.dp),
                    ) {
                        Text(
                            if (micMode == "ptt") "PTT" else "实时",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    IconButton(
                        onClick = {
                            muteSpeaker = !muteSpeaker
                            vm.setMuteSpeaker(muteSpeaker)
                        },
                        modifier = Modifier.height(48.dp),
                    ) {
                        Icon(
                            Icons.Default.SurroundSound,
                            contentDescription = "静音外放",
                            tint = if (muteSpeaker) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "音频源:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilterChip(
                        selected = currentAudioMode == AudioInputManager.InputMode.MICROPHONE,
                        onClick = {
                            vm.switchAudioMode(AudioInputManager.InputMode.MICROPHONE)
                        },
                        label = { Text("麦克风", fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                    )
                    FilterChip(
                        selected = currentAudioMode == AudioInputManager.InputMode.MUSIC_FILE,
                        onClick = {
                            showFilePicker = true
                        },
                        label = { Text("音乐文件", fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                    )
                    if (currentAudioMode == AudioInputManager.InputMode.MUSIC_FILE) {
                        Text(
                            "🎵",
                            fontSize = 14.sp,
                            modifier = Modifier.clickable { vm.stopMusicPlayback() }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { vm.sendButtonClick(0x01) },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("左键")
            }
            Button(
                onClick = { vm.sendButtonClick(0x02) },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("右键")
            }
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("断开")
            }
        }
    }
}
