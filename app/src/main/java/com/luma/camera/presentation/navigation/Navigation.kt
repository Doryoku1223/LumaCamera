package com.luma.camera.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.luma.camera.presentation.screen.camera.CameraScreen
import com.luma.camera.presentation.screen.lutmanager.LutManagerScreen
import com.luma.camera.presentation.screen.settings.SettingsScreen

/**
 * Luma Camera 导航路由
 */
sealed class Screen(val route: String) {
    data object Camera : Screen("camera")
    data object Settings : Screen("settings")
    data object LutManager : Screen("lut_manager")
}

/**
 * Luma Camera 主导航
 */
@Composable
fun LumaCameraNavHost(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Camera.route
    ) {
        composable(Screen.Camera.route) {
            CameraScreen(
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onNavigateToLutManager = {
                    navController.navigate(Screen.LutManager.route)
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        
        composable(Screen.LutManager.route) {
            LutManagerScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
