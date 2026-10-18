package app.forkcast.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.forkcast.ui.onboarding.OnboardingRoute
import app.forkcast.ui.settings.AboutRoute
import app.forkcast.ui.settings.AppearanceRoute
import app.forkcast.ui.settings.ProfileGoalsRoute
import app.forkcast.ui.settings.SettingsScreen
import app.forkcast.ui.today.TodayRoute
import kotlinx.serialization.Serializable

@Serializable data object OnboardingDestination
@Serializable data object TodayDestination
@Serializable data object SettingsDestination
@Serializable data object ProfileGoalsDestination
@Serializable data object AppearanceDestination
@Serializable data object AboutDestination

@Composable
fun ForkcastNavHost(
    startWithOnboarding: Boolean,
    navController: NavHostController = rememberNavController(),
) {
    val back: () -> Unit = { navController.popBackStack() }
    NavHost(
        navController = navController,
        startDestination = if (startWithOnboarding) OnboardingDestination else TodayDestination,
    ) {
        composable<OnboardingDestination> {
            OnboardingRoute(onFinished = {
                navController.navigate(TodayDestination) {
                    popUpTo(OnboardingDestination) { inclusive = true }
                }
            })
        }
        composable<TodayDestination> {
            TodayRoute(onOpenSettings = { navController.navigate(SettingsDestination) })
        }
        composable<SettingsDestination> {
            SettingsScreen(
                onBack = back,
                onOpenProfileGoals = { navController.navigate(ProfileGoalsDestination) },
                onOpenAppearance = { navController.navigate(AppearanceDestination) },
                onOpenAbout = { navController.navigate(AboutDestination) },
            )
        }
        composable<ProfileGoalsDestination> { ProfileGoalsRoute(onBack = back) }
        composable<AppearanceDestination> { AppearanceRoute(onBack = back) }
        composable<AboutDestination> { AboutRoute(onBack = back) }
    }
}
