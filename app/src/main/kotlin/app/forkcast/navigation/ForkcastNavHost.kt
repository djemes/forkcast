package app.forkcast.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.forkcast.ui.food.CustomFoodRoute
import app.forkcast.ui.food.FoodDetailRoute
import app.forkcast.ui.food.SearchRoute
import app.forkcast.ui.onboarding.OnboardingRoute
import app.forkcast.ui.scan.LabelScanRoute
import app.forkcast.ui.scan.ScannerRoute
import app.forkcast.ui.settings.AboutRoute
import app.forkcast.ui.settings.AppearanceRoute
import app.forkcast.ui.settings.FoodDatabasesRoute
import app.forkcast.ui.settings.ProfileGoalsRoute
import app.forkcast.ui.settings.SettingsScreen
import app.forkcast.ui.today.TodayActions
import app.forkcast.ui.today.TodayRoute
import app.forkcast.ui.weight.WeightRoute
import kotlinx.serialization.Serializable

@Serializable data object OnboardingDestination
@Serializable data object TodayDestination
@Serializable data object SettingsDestination
@Serializable data object ProfileGoalsDestination
@Serializable data object AppearanceDestination
@Serializable data object AboutDestination
@Serializable data object FoodDatabasesDestination
@Serializable data object WeightDestination

/** Log into [mealSlotId] on [date] (ISO yyyy-MM-dd). */
@Serializable data class SearchDestination(val mealSlotId: String, val date: String)

@Serializable data class FoodDetailDestination(val foodId: String, val mealSlotId: String, val date: String)

/** Create (foodId null) or edit a custom food; [barcode] and [prefill] (scanned label JSON) pre-fill a new one. */
@Serializable data class CustomFoodDestination(
    val foodId: String? = null,
    val barcode: String? = null,
    val mealSlotId: String? = null,
    val date: String? = null,
    val prefill: String? = null,
)

@Serializable data class ScannerDestination(val mealSlotId: String, val date: String)

/** Photograph a Nutrition Facts panel for an unknown [barcode]. */
@Serializable data class LabelScanDestination(val barcode: String, val mealSlotId: String, val date: String)

/** Key on Today's back-stack entry: the meal name to confirm ("Added to Lunch"). */
private const val ADDED_TO = "added_to"

@Composable
fun ForkcastNavHost(
    startWithOnboarding: Boolean,
    navController: NavHostController = rememberNavController(),
) {
    val back: () -> Unit = { navController.popBackStack() }

    /** Returns to Today and shows "Added to <meal>". */
    fun addedTo(mealName: String) {
        runCatching { navController.getBackStackEntry<TodayDestination>().savedStateHandle[ADDED_TO] = mealName }
        navController.popBackStack<TodayDestination>(inclusive = false)
    }

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
        composable<TodayDestination> { entry ->
            val added by entry.savedStateHandle.getStateFlow<String?>(ADDED_TO, null).collectAsStateWithLifecycle()
            TodayRoute(
                actions = TodayActions(
                    openSettings = { navController.navigate(SettingsDestination) },
                    openSearch = { meal, date -> navController.navigate(SearchDestination(meal, date.toString())) },
                    openScanner = { meal, date -> navController.navigate(ScannerDestination(meal, date.toString())) },
                    openWeight = { navController.navigate(WeightDestination) },
                ),
                addedToMeal = added,
                onAddedShown = { entry.savedStateHandle[ADDED_TO] = null },
            )
        }
        composable<SearchDestination> { entry ->
            val args = entry.routeOrNull<SearchDestination>()
            SearchRoute(
                onBack = back,
                onOpenFood = { id ->
                    navController.navigate(FoodDetailDestination(id, args?.mealSlotId.orEmpty(), args?.date.orEmpty()))
                },
                onCreateFood = { navController.navigate(CustomFoodDestination(mealSlotId = args?.mealSlotId, date = args?.date)) },
            )
        }
        composable<ScannerDestination> { entry ->
            val args = entry.routeOrNull<ScannerDestination>()
            val meal = args?.mealSlotId.orEmpty()
            val date = args?.date.orEmpty()
            ScannerRoute(
                onBack = back,
                onOpenFood = { id -> navController.navigate(FoodDetailDestination(id, meal, date)) },
                onScanLabel = { gtin -> navController.navigate(LabelScanDestination(gtin, meal, date)) },
                onEnterManually = { gtin -> navController.navigate(CustomFoodDestination(barcode = gtin, mealSlotId = meal, date = date)) },
            )
        }
        composable<LabelScanDestination> { entry ->
            val args = entry.routeOrNull<LabelScanDestination>()
            LabelScanRoute(
                onBack = back,
                onParsed = { prefill ->
                    navController.navigate(
                        CustomFoodDestination(barcode = args?.barcode, mealSlotId = args?.mealSlotId, date = args?.date, prefill = prefill.encode()),
                    ) { popUpTo<LabelScanDestination> { inclusive = true } }
                },
            )
        }
        composable<FoodDetailDestination> {
            FoodDetailRoute(
                onBack = back,
                onAdded = ::addedTo,
                onEdit = { id -> navController.navigate(CustomFoodDestination(foodId = id)) },
            )
        }
        composable<CustomFoodDestination> { entry ->
            val args = entry.routeOrNull<CustomFoodDestination>()
            CustomFoodRoute(
                onBack = back,
                onSaved = { id, isNew ->
                    val meal = args?.mealSlotId
                    val date = args?.date
                    if (isNew && meal != null && date != null) {
                        // A new food goes straight to its detail screen, ready to log.
                        navController.navigate(FoodDetailDestination(id, meal, date)) {
                            popUpTo<CustomFoodDestination> { inclusive = true }
                        }
                    } else {
                        navController.popBackStack()
                    }
                },
                onDeleted = { navController.popBackStack<TodayDestination>(inclusive = false) },
            )
        }
        composable<SettingsDestination> {
            SettingsScreen(
                onBack = back,
                onOpenProfileGoals = { navController.navigate(ProfileGoalsDestination) },
                onOpenFoodDatabases = { navController.navigate(FoodDatabasesDestination) },
                onOpenAppearance = { navController.navigate(AppearanceDestination) },
                onOpenAbout = { navController.navigate(AboutDestination) },
            )
        }
        composable<ProfileGoalsDestination> { ProfileGoalsRoute(onBack = back) }
        composable<FoodDatabasesDestination> { FoodDatabasesRoute(onBack = back) }
        composable<WeightDestination> { WeightRoute(onBack = back) }
        composable<AppearanceDestination> { AppearanceRoute(onBack = back) }
        composable<AboutDestination> { AboutRoute(onBack = back) }
    }
}

private inline fun <reified T : Any> NavBackStackEntry.routeOrNull(): T? = runCatching { toRoute<T>() }.getOrNull()
