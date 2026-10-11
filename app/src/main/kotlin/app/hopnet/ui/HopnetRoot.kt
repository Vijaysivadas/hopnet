package app.hopnet.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hopnet.sdk.Hopnet
import app.hopnet.services.ServiceCatalog
import app.hopnet.services.findme.FindMeScreen
import app.hopnet.services.info.InfoScreen
import app.hopnet.services.info.ReaderScreen
import app.hopnet.services.info.ReaderTarget
import app.hopnet.services.messaging.ChatScreen
import app.hopnet.services.messaging.ConversationsScreen
import app.hopnet.ui.home.HomeScreen
import app.hopnet.ui.network.NetworkScreen
import app.hopnet.ui.onboarding.OnboardingScreen
import app.hopnet.ui.onboarding.PermissionsScreen

sealed interface Route {
    data object Home : Route
    data object Messages : Route
    data class Chat(val handle: String) : Route
    data object FindMe : Route
    data object Info : Route
    data class Reader(val target: ReaderTarget) : Route
    data object Network : Route
}

/** Onboarding (online, once) → permissions → the app. Starts the background relay when ready. */
@Composable
fun HopnetRoot(
    openChat: String?,
    onChatOpened: () -> Unit,
    openFindMe: Boolean,
    onFindMeOpened: () -> Unit,
    openInfo: Boolean,
    onInfoOpened: () -> Unit,
) {
    val context = LocalContext.current
    val profile by Hopnet.profile.collectAsStateWithLifecycle()
    var permitted by remember { mutableStateOf(Hopnet.hasPermissions(context)) }

    LifecycleResumeEffect(Unit) {
        permitted = Hopnet.hasPermissions(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(profile != null, permitted) {
        if (profile != null && permitted) Hopnet.startMesh(context)
    }

    when {
        profile == null -> OnboardingScreen()
        !permitted -> PermissionsScreen(onResult = { permitted = Hopnet.hasPermissions(context) })
        else -> MainNavigation(openChat, onChatOpened, openFindMe, onFindMeOpened, openInfo, onInfoOpened)
    }
}

@Composable
private fun MainNavigation(
    openChat: String?,
    onChatOpened: () -> Unit,
    openFindMe: Boolean,
    onFindMeOpened: () -> Unit,
    openInfo: Boolean,
    onInfoOpened: () -> Unit,
) {
    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    fun go(route: Route) {
        stack.add(route)
    }
    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    LaunchedEffect(openChat) {
        if (openChat != null) {
            stack.clear()
            stack.addAll(listOf(Route.Home, Route.Messages, Route.Chat(openChat)))
            onChatOpened()
        }
    }
    LaunchedEffect(openFindMe) {
        if (openFindMe) {
            stack.clear()
            stack.addAll(listOf(Route.Home, Route.FindMe))
            onFindMeOpened()
        }
    }
    LaunchedEffect(openInfo) {
        if (openInfo) {
            stack.clear()
            stack.addAll(listOf(Route.Home, Route.Info))
            onInfoOpened()
        }
    }
    BackHandler(enabled = stack.size > 1) { back() }

    when (val route = stack.last()) {
        Route.Home -> HomeScreen(
            onOpenService = {
                when (it.id) {
                    ServiceCatalog.messages.id -> go(Route.Messages)
                    ServiceCatalog.findMe.id -> go(Route.FindMe)
                    ServiceCatalog.liveInfo.id -> go(Route.Info)
                }
            },
            onOpenNetwork = { go(Route.Network) },
        )
        Route.Messages -> ConversationsScreen(onBack = ::back, onOpenChat = { go(Route.Chat(it)) })
        is Route.Chat -> ChatScreen(handle = route.handle, onBack = ::back, onOpenNetwork = { go(Route.Network) })
        Route.FindMe -> FindMeScreen(onBack = ::back)
        Route.Info -> InfoScreen(onBack = ::back, onOpenReader = { go(Route.Reader(it)) }, onOpenNetwork = { go(Route.Network) })
        is Route.Reader -> ReaderScreen(route.target, onBack = ::back)
        Route.Network -> NetworkScreen(onBack = ::back)
    }
}
