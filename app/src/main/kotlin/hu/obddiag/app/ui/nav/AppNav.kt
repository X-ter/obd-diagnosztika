package hu.obddiag.app.ui.nav

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.Speed
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import hu.obddiag.app.AppContainer
import hu.obddiag.app.ObdApp
import hu.obddiag.app.R
import hu.obddiag.app.ui.components.SimulatedBanner
import hu.obddiag.app.ui.screens.AboutScreen
import hu.obddiag.app.ui.screens.AdapterScreen
import hu.obddiag.app.ui.screens.AdvancedScreen
import hu.obddiag.app.ui.screens.CommLogScreen
import hu.obddiag.app.ui.screens.DashboardScreen
import hu.obddiag.app.ui.screens.DtcScreen
import hu.obddiag.app.ui.screens.HomeScreen
import hu.obddiag.app.ui.screens.LiveDataScreen
import hu.obddiag.app.ui.screens.LogDetailScreen
import hu.obddiag.app.ui.screens.LoggingScreen
import hu.obddiag.app.ui.screens.MoreScreen
import hu.obddiag.app.ui.screens.SettingsScreen
import hu.obddiag.app.ui.screens.TerminalScreen
import hu.obddiag.app.ui.screens.VehiclesScreen
import hu.obddiag.core.connection.ConnectionState

object Routes {
    const val HOME = "home"
    const val DTC = "dtc"
    const val LIVE = "live"
    const val DASH = "dash"
    const val MORE = "more"
    const val LOGGING = "logging"
    const val LOG_DETAIL = "logging/{id}"
    const val VEHICLES = "vehicles"
    const val ADAPTER = "adapter"
    const val ADVANCED = "advanced"
    const val TERMINAL = "terminal"
    const val COMMLOG = "commlog"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val MFR = "mfr"
    fun logDetail(id: Long) = "logging/$id"
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.HOME, R.string.tab_home, Icons.Outlined.Home),
    Tab(Routes.DTC, R.string.tab_dtc, Icons.Outlined.ReportProblem),
    Tab(Routes.LIVE, R.string.tab_live, Icons.Outlined.ShowChart),
    Tab(Routes.DASH, R.string.tab_dash, Icons.Outlined.Speed),
    Tab(Routes.MORE, R.string.tab_more, Icons.Outlined.Apps),
)

/** ViewModel létrehozása az alkalmazás konténeréből. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = (LocalContext.current.applicationContext as ObdApp).container
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as ObdApp).container

fun NavController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val container = appContainer()
    val state by container.connection.state.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                TABS.forEach { tab ->
                    val selected = current == tab.route ||
                        (tab.route == Routes.MORE && current != null && TABS.none { it.route == current })
                    NavigationBarItem(
                        selected = selected,
                        onClick = { nav.navigateTab(tab.route) },
                        modifier = Modifier.testTag("tab_${tab.route}"),
                        icon = { Icon(tab.icon, null) },
                        label = { Text(stringResource(tab.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if ((state as? ConnectionState.Connected)?.simulated == true) SimulatedBanner()
            NavHost(
                nav, startDestination = Routes.HOME, modifier = Modifier.weight(1f),
                enterTransition = { fadeIn() }, exitTransition = { fadeOut() }
            ) {
                composable(Routes.HOME) { HomeScreen(nav) }
                composable(Routes.DTC) { DtcScreen(nav) }
                composable(Routes.LIVE) { LiveDataScreen(nav) }
                composable(Routes.DASH) { DashboardScreen(nav) }
                composable(Routes.MORE) { MoreScreen(nav) }
                composable(Routes.LOGGING) { LoggingScreen(nav) }
                composable(Routes.LOG_DETAIL, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                    LogDetailScreen(nav, it.arguments?.getLong("id") ?: 0L)
                }
                composable(Routes.VEHICLES) { VehiclesScreen(nav) }
                composable(Routes.ADAPTER) { AdapterScreen(nav) }
                composable(Routes.ADVANCED) { AdvancedScreen(nav) }
                composable(Routes.TERMINAL) { TerminalScreen(nav) }
                composable(Routes.COMMLOG) { CommLogScreen(nav) }
                composable(Routes.SETTINGS) { SettingsScreen(nav) }
                composable(Routes.ABOUT) { AboutScreen(nav) }
                composable(Routes.MFR) { hu.obddiag.app.ui.screens.MfrScreen(nav) }
            }
        }
    }
}

