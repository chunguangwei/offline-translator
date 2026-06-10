package com.offlinetranslator.app.feature.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.ui.graphics.vector.ImageVector
import com.offlinetranslator.app.R

/** All navigation destinations. */
sealed class Route(val path: String) {
    data object Translate : Route("translate")
    data object Chat : Route("chat")
    data object Voice : Route("voice")
    data object Vision : Route("vision")
    data object Models : Route("models")
    data object History : Route("history")
    data object Settings : Route("settings")
}

data class TopLevelDestination(
    val route: Route,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

val topLevelDestinations = listOf(
    TopLevelDestination(Route.Translate, R.string.nav_translate, Icons.Rounded.SwapHoriz, Icons.Outlined.SwapHoriz),
    TopLevelDestination(Route.Chat, R.string.nav_qa, Icons.Rounded.Forum, Icons.Outlined.Forum),
    TopLevelDestination(Route.History, R.string.nav_history, Icons.Rounded.History, Icons.Outlined.History),
    TopLevelDestination(Route.Settings, R.string.nav_settings, Icons.Rounded.Settings, Icons.Outlined.Settings),
)
