package com.offlinetranslator.app.feature.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.GraphicEq
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
    TopLevelDestination(Route.Chat, R.string.nav_chat, Icons.Rounded.Forum, Icons.Outlined.Forum),
    TopLevelDestination(Route.Voice, R.string.nav_voice, Icons.Rounded.GraphicEq, Icons.Outlined.GraphicEq),
    TopLevelDestination(Route.Vision, R.string.nav_vision, Icons.Rounded.Image, Icons.Outlined.Image),
    TopLevelDestination(Route.Models, R.string.nav_models, Icons.Rounded.Widgets, Icons.Outlined.Widgets),
    TopLevelDestination(Route.Settings, R.string.nav_settings, Icons.Rounded.Settings, Icons.Outlined.Settings),
)
