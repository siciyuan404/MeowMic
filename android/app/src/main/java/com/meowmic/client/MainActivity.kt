package com.meowmic.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.meowmic.client.ui.ClipboardScreen
import com.meowmic.client.ui.ConnectScreen
import com.meowmic.client.ui.FilesScreen
import com.meowmic.client.ui.LauncherScreen
import com.meowmic.client.ui.MeowMicTheme
import com.meowmic.client.ui.MonitorScreen
import com.meowmic.client.ui.TouchpadScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MeowMicTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val navController = rememberNavController()
                    val vm: MeowMicViewModel = viewModel()

                    // 统一断开 + 返回连接页
                    fun doDisconnect() {
                        vm.disconnect()
                        navController.popBackStack("connect", inclusive = false)
                    }
                    // 统一页面跳转(避免重复入栈)
                    fun navigateTo(view: String) {
                        if (view == "touch") {
                            // 触控页作为主页面,pop 到 touchpad
                            navController.popBackStack("touchpad", inclusive = false)
                        } else if (view == "audio" || view == "keyboard") {
                            // 语音/键盘暂未独立路由,回触控页
                            navController.popBackStack("touchpad", inclusive = false)
                        } else {
                            navController.navigate(view) {
                                launchSingleTop = true
                            }
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = "connect",
                    ) {
                        composable("connect") {
                            ConnectScreen(
                                vm = vm,
                                onConnected = { navController.navigate("touchpad") },
                            )
                        }
                        composable("touchpad") {
                            TouchpadScreen(
                                vm = vm,
                                onDisconnect = { doDisconnect() },
                                onOpenLauncher = {
                                    navController.navigate("launcher") {
                                        launchSingleTop = true
                                    }
                                },
                                onNavigate = { view -> navigateTo(view) },
                            )
                        }
                        composable("launcher") {
                            LauncherScreen(
                                vm = vm,
                                onBack = { navController.popBackStack() },
                                onDisconnect = { doDisconnect() },
                                onNavigate = { view -> navigateTo(view) },
                            )
                        }
                        composable("monitor") {
                            MonitorScreen(
                                vm = vm,
                                onBack = { navController.popBackStack("touchpad", inclusive = false) },
                                onDisconnect = { doDisconnect() },
                                onNavigate = { view -> navigateTo(view) },
                            )
                        }
                        composable("files") {
                            FilesScreen(
                                vm = vm,
                                onBack = { navController.popBackStack("touchpad", inclusive = false) },
                                onDisconnect = { doDisconnect() },
                                onNavigate = { view -> navigateTo(view) },
                            )
                        }
                        composable("clipboard") {
                            ClipboardScreen(
                                vm = vm,
                                onBack = { navController.popBackStack("touchpad", inclusive = false) },
                                onDisconnect = { doDisconnect() },
                                onNavigate = { view -> navigateTo(view) },
                            )
                        }
                    }
                }
            }
        }
    }
}
