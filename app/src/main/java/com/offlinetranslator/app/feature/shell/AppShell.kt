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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.offlinetranslator.app.core.designsystem.components.AuroraBackground
import com.offlinetranslator.app.feature.chat.ChatScreen
import com.offlinetranslator.app.feature.models.ModelsScreen
import com.offlinetranslator.app.feature.settings.SettingsScreen
import com.offlinetranslator.app.feature.translate.TranslateScreen
import com.offlinetranslator.app.feature.update.UpdateDialogHost
import com.offlinetranslator.app.feature.update.UpdateViewModel
import com.offlinetranslator.app.feature.vision.VisionScreen
import com.offlinetranslator.app.feature.voice.VoiceScreen

@Composable
fun AppShell() {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Silent self-update check on cold start: prompts only when a newer release
    // exists, otherwise stays completely quiet.
    val updateVm: UpdateViewModel = hiltViewModel()
    LaunchedEffect(Unit) { updateVm.check(silent = true) }
    UpdateDialogHost(updateVm)

    AuroraBackground(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                ) {
                    topLevelDestinations.forEach { dest ->
                        val selected = currentRoute == dest.route.path
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(dest.route.path) {
                                    popUpTo(nav.graph.findStartDestination().id) {
                                        saveState = true
                                    }
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
                                Text(
                                    text = stringResource(dest.labelRes),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = nav,
                startDestination = Route.Translate.path,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(Route.Translate.path) { TranslateScreen(padding = innerPadding) }
                composable(Route.Chat.path) { ChatScreen(padding = innerPadding) }
                composable(Route.Voice.path) {
                    VoiceScreen(
                        padding = innerPadding,
                        onNavigateToModels = {
                            nav.navigate(Route.Models.path) {
                                launchSingleTop = true
                                popUpTo(Route.Translate.path)
                            }
                        },
                    )
                }
                composable(Route.Vision.path) { VisionScreen(padding = innerPadding) }
                composable(Route.Models.path) { ModelsScreen(padding = innerPadding) }
                composable(Route.Settings.path) { SettingsScreen(padding = innerPadding) }
            }
        }
    }
}

/** Helper composable used when a screen wants to consume scaffold padding. */
@Composable
fun Modifier.consumeScaffoldPadding(padding: PaddingValues): Modifier =
    this.padding(padding)
