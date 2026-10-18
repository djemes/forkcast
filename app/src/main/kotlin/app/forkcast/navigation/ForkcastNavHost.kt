package app.forkcast.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.forkcast.ui.settings.AboutRoute
import app.forkcast.ui.settings.AppearanceRoute
import app.forkcast.ui.settings.SettingsScreen
import app.forkcast.ui.today.TodayRoute
import kotlinx.serialization.Serializable

@Serializable data object TodayDestination
@Serializable data object SettingsDestination
@Serializable data object AppearanceDestination
@Serializable data object AboutDestination

@Composable
fun ForkcastNavHost(navController: NavHostController = rememberNavController()) {
    val back: () -> Unit = { navController.popBackStack() }
    NavHost(navController = navController, startDestination = TodayDestination) {
        composable<TodayDestination> {
            TodayRoute(onOpenSettings = { navController.navigate(SettingsDestination) })
        }
        composable<SettingsDestination> {
            SettingsScreen(
                onBack = back,
                onOpenAppearance = { navController.navigate(AppearanceDestination) },
                onOpenAbout = { navController.navigate(AboutDestination) },
            )
        }
        composable<AppearanceDestination> { AppearanceRoute(onBack = back) }
        composable<AboutDestination> { AboutRoute(onBack = back) }
    }
}
