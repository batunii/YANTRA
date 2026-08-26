package ie.napkin.supertasks.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    fun node(id: String) = "node/$id"
    fun smart(id: String) = "smart/$id"
    fun ink(id: String) = "ink/$id"
    fun focus(nodeId: String) = "focus/$nodeId"
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
    // Expressive spatial motion: pages slide in on a spring with a fade; pops mirror it.
    val spatialSlide = spring<IntOffset>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
    val effectsFade = tween<Float>(220)
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { slideInHorizontally(spatialSlide) { it / 6 } + fadeIn(effectsFade) },
        exitTransition = { fadeOut(effectsFade) },
        popEnterTransition = { fadeIn(effectsFade) },
        popExitTransition = { slideOutHorizontally(spatialSlide) { it / 6 } + fadeOut(effectsFade) },
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
