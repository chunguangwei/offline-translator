package com.offlinetranslator.app.feature.shell

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.offlinetranslator.app.feature.chat.ChatScreen
import com.offlinetranslator.app.feature.history.HistoryScreen
import com.offlinetranslator.app.feature.models.ModelsScreen
import com.offlinetranslator.app.feature.settings.SettingsScreen
import com.offlinetranslator.app.feature.translate.TranslateScreen

/**
 * 底部 4-Tab 骨架：翻译 / 问答 / 历史 / 设置，全部为真实界面。
 * 模型下载页(ModelsScreen)是非 Tab 路由，由翻译页缺模型横幅或设置页进入，
 * 进入时隐藏底栏。
 */
@Composable
fun AppShell() {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    // 首帧 NavHost 尚未挂载时 route 为 null —— 视作起始页 Translate，
    // 避免底栏「先隐藏后出现」闪一下。
    val currentRoute = backStackEntry?.destination?.route ?: Route.Translate.path

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            // Models 等非 Tab 路由进入时隐藏底栏。
            if (topLevelDestinations.any { it.route.path == currentRoute }) {
                NavigationBar {
                    topLevelDestinations.forEach { dest ->
                        val selected = currentRoute == dest.route.path
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(dest.route.path) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                    contentDescription = stringResource(dest.labelRes),
                                )
                            },
                            label = {
                                Text(stringResource(dest.labelRes), style = MaterialTheme.typography.labelSmall)
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = nav,
            startDestination = Route.Translate.path,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Route.Translate.path) {
                TranslateScreen(
                    padding = innerPadding,
                    onOpenModels = {
                        nav.navigate(Route.Models.path) { launchSingleTop = true }
                    },
                )
            }
            composable(Route.Chat.path) { ChatScreen(padding = innerPadding) }
            composable(Route.History.path) { HistoryScreen(padding = innerPadding) }
            composable(Route.Settings.path) {
                SettingsScreen(
                    padding = innerPadding,
                    onOpenModels = { nav.navigate(Route.Models.path) { launchSingleTop = true } },
                )
            }
            composable(Route.Models.path) { ModelsScreen(padding = innerPadding) }
        }
    }
}

/** Helper composable used when a screen wants to consume scaffold padding. */
@Composable
fun Modifier.consumeScaffoldPadding(padding: PaddingValues): Modifier =
    this.padding(padding)
