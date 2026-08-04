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
import com.meowmic.client.ui.ConnectScreen
import com.meowmic.client.ui.LauncherScreen
import com.meowmic.client.ui.MeowMicTheme
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
                                onDisconnect = {
                                    vm.disconnect()
                                    navController.popBackStack("connect", inclusive = false)
                                },
                                onOpenLauncher = {
                                    navController.navigate("launcher")
                                },
                            )
                        }
                        composable("launcher") {
                            LauncherScreen(
                                vm = vm,
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                }
            }
        }
    }
}
