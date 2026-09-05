package ie.napkin.supertasks.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ie.napkin.supertasks.App
import ie.napkin.supertasks.AppContainer
import ie.napkin.supertasks.ui.components.LocalNow
import ie.napkin.supertasks.ui.home.HomeScreen
import ie.napkin.supertasks.ui.ink.InkScreen
import ie.napkin.supertasks.ui.node.NodePageScreen
import ie.napkin.supertasks.ui.focus.FocusScreen
import ie.napkin.supertasks.ui.focus.StatsScreen
import ie.napkin.supertasks.ui.smart.SmartListScreen
import ie.napkin.supertasks.ui.settings.SettingsScreen
import ie.napkin.supertasks.ui.archive.ArchiveScreen
import ie.napkin.supertasks.ui.sync.AddWorkspaceScreen
import ie.napkin.supertasks.ui.sync.SignInScreen

/** Pulls the app-wide container out of ViewModel CreationExtras. */
fun CreationExtras.container(): AppContainer = (this[APPLICATION_KEY] as App).container

@Composable
fun appContainer(): AppContainer =
    (LocalContext.current.applicationContext as App).container

/** A launch deep-link: a node to open (widget/notification tap), or the focus screen. */
data class OpenTarget(
    val nodeId: String?,
    val isSmart: Boolean,
    val focus: Boolean = false,
    /** GitHub sent them back here after installing the App — see [Routes.GITHUB]. */
    val github: Boolean = false,
)

object Routes {
    const val SPLASH = "splash"
    const val HOME = "home"

    /**
     * A node id, safe to drop into a route.
     *
     * Not decoration. A route string is parsed as a URI, so a `#` in an id is read as the start of
     * a fragment and everything after it is thrown away — and blocks the page format does not name
     * get a *derived* id built from the page they sit on (see PageMapper.blockId). Un-encoded,
     * `node/<page>#3` therefore resolved to `node/<page>`: tapping a subtask animated you onto the
     * page you were already on. Encoding here is the single place that cannot be forgotten.
     */
    private fun arg(id: String) = android.net.Uri.encode(id)

    fun node(id: String) = "node/${arg(id)}"
    fun smart(id: String) = "smart/${arg(id)}"
    fun ink(id: String) = "ink/${arg(id)}"
    fun focus(nodeId: String) = "focus/${arg(nodeId)}"
    const val FOCUS_CURRENT = "focus"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val GITHUB = "github"
    const val ADD_WORKSPACE = "workspace/add"
    const val ARCHIVE = "archive"
}

@Composable
fun AppNav(
    openTarget: OpenTarget? = null,
    onOpenConsumed: () -> Unit = {},
    navController: NavHostController = rememberNavController(),
) {
    /**
     * Fixed at the first composition, and deliberately not derived from [openTarget] afterwards.
     *
     * It used to read `if (openTarget != null) HOME else SPLASH` inline. Changing a NavHost's start
     * destination rebuilds its graph and throws the back stack away — so the moment [onOpenConsumed]
     * set the target back to null, one frame after arriving somewhere, the whole navigation was
     * undone and the previous screen came back from saved state. The destination *was* reached; it
     * just did not survive being told the intent had been handled.
     */
    val startDestination = remember { if (openTarget != null) Routes.HOME else Routes.SPLASH }

    // The task being worked on, offered once to the whole graph. Every screen that draws it — the
    // washed row's trailing slot, the now bar — reads this one, so none of them can be looking at
    // a different task from the one the app is actually on.
    val now = appContainer().running.now

    // A widget tap skips the splash and jumps straight to the tapped list/task.
    LaunchedEffect(openTarget) {
        val target = openTarget ?: return@LaunchedEffect
        val route = when {
            target.github -> Routes.GITHUB
            target.focus -> Routes.FOCUS_CURRENT
            target.nodeId == null -> return@LaunchedEffect
            target.isSmart -> Routes.smart(target.nodeId)
            else -> Routes.node(target.nodeId)
        }
        navController.navigate(route) {
            popUpTo(Routes.HOME) { inclusive = false }
            launchSingleTop = true
        }
        onOpenConsumed()
    }
    // Material shared-x-axis, and the two halves deliberately do not overlap in time.
    //
    // The previous pairing — both screens near their resting position, both fading over the same
    // 220ms — meant that for ~150ms two pages of text were drawn on top of each other at the same
    // coordinates. Headlines and rows superimposed into something unreadable, which is not a
    // transition so much as a smear. So: the outgoing page fades out fast and completely, and the
    // incoming one starts its fade only once that has finished. At no frame is more than one
    // screen's text legible.
    val outMs = 90
    val inMs = 210
    val spatialSlide = spring<IntOffset>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
    val fadeAway = tween<Float>(outMs, easing = LinearEasing)
    val fadeUp = tween<Float>(inMs, delayMillis = outMs, easing = LinearEasing)
    CompositionLocalProvider(LocalNow provides now) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { slideInHorizontally(spatialSlide) { it / 3 } + fadeIn(fadeUp) },
        exitTransition = { slideOutHorizontally(spatialSlide) { -it / 3 } + fadeOut(fadeAway) },
        popEnterTransition = { slideInHorizontally(spatialSlide) { -it / 3 } + fadeIn(fadeUp) },
        popExitTransition = { slideOutHorizontally(spatialSlide) { it / 3 } + fadeOut(fadeAway) },
    ) {
        composable(Routes.SPLASH) {
            SplashScreen(navController)
        }
        composable(Routes.HOME) {
            HomeScreen(navController)
        }
        composable("node/{nodeId}") { backStack ->
            NodePageScreen(navController, backStack.arguments!!.getString("nodeId")!!)
        }
        composable("smart/{nodeId}") { backStack ->
            SmartListScreen(navController, backStack.arguments!!.getString("nodeId")!!)
        }
        composable("ink/{nodeId}") { backStack ->
            InkScreen(navController, backStack.arguments!!.getString("nodeId")!!)
        }
        composable("focus/{nodeId}") { backStack ->
            FocusScreen(navController, backStack.arguments!!.getString("nodeId"))
        }
        composable(Routes.FOCUS_CURRENT) {
            FocusScreen(navController, null)
        }
        composable(Routes.STATS) {
            StatsScreen(navController)
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(navController)
        }
        composable(Routes.GITHUB) {
            SignInScreen(navController)
        }
        composable(Routes.ADD_WORKSPACE) {
            AddWorkspaceScreen(navController)
        }
        composable(Routes.ARCHIVE) {
            ArchiveScreen(navController)
        }
    }
    }
}
