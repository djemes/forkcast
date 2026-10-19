package app.forkcast.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.forkcast.ui.settings.NotificationsRoute
import app.forkcast.ui.settings.MealsRoute
import app.forkcast.ui.settings.DailyTargetsRoute
import app.forkcast.ui.ai.AiProfileEditRoute
import app.forkcast.ui.ai.AiProfileEditViewModel
import app.forkcast.ui.ai.AiSettingsRoute
import app.forkcast.ui.backup.BackupRoute
import app.forkcast.ui.activity.ActivityRoute
import app.forkcast.ui.weekly.WeeklyCheckInRoute
import app.forkcast.ui.health.HealthConnectRoute
import app.forkcast.ui.photo.PhotoMealRoute
import app.forkcast.ui.photo.ReviewRoute
import app.forkcast.ui.settings.GoalBudgetRoute
import app.forkcast.ui.settings.ProfileRoute
import app.forkcast.ui.settings.SettingsActions
import app.forkcast.ui.settings.UnitsRoute
import app.forkcast.ui.settings.WaterSettingsRoute
import app.forkcast.ui.settings.SettingsScreen
import app.forkcast.ui.today.TodayActions
import app.forkcast.ui.today.TodayRoute
import app.forkcast.ui.trends.TrendsRoute
import app.forkcast.ui.weight.WeightRoute
import kotlinx.serialization.Serializable

@Serializable data object OnboardingDestination
@Serializable data object TodayDestination
@Serializable data object SettingsDestination
/** Settings › Goal & budget (kept under its old name so saved navigation state still resolves). */
@Serializable data object ProfileGoalsDestination
@Serializable data object ProfileDestination
@Serializable data object UnitsDestination
@Serializable data object WaterSettingsDestination

/** Weekly check-in for the week starting [start] (ISO date); null = last week. */
@Serializable data class WeeklyDestination(val start: String? = null)
@Serializable data object AppearanceDestination
@Serializable data object AboutDestination
@Serializable data object FoodDatabasesDestination
@Serializable data object BackupDestination
@Serializable data object WeightDestination
@Serializable data object TrendsDestination
@Serializable data object AiSettingsDestination
@Serializable data object NotificationsDestination
@Serializable data object MealsDestination
@Serializable data object DailyTargetsDestination
@Serializable data object HealthConnectDestination

/** Activity detail for [date] (ISO yyyy-MM-dd) and the days before it. */
@Serializable data class ActivityDestination(val date: String)

/** Photo-meal camera, logging into [mealSlotId] on [date] (ISO yyyy-MM-dd). */
@Serializable data class PhotoMealDestination(val mealSlotId: String, val date: String)

@Serializable data class ReviewDestination(val estimateId: String)

/** Edit [profileId], or create a new profile (null). [preset] "free_gemini" = guided free setup. */
@Serializable data class AiProfileEditDestination(val profileId: String? = null, val preset: String? = null)

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

/** Key on Today's back-stack entry: a day to show (ISO date), set by Trends' "Open day". */
private const val OPEN_DATE = "open_date"

/** Key on Settings › AI's back-stack entry: a profile the editor's Delete confirmed (the list deletes it, with Undo). */
private const val DELETE_AI_PROFILE = "delete_ai_profile"

@Composable
fun ForkcastNavHost(
    startWithOnboarding: Boolean,
    navController: NavHostController = rememberNavController(),
    openRequest: String? = null,
    onOpenHandled: () -> Unit = {},
) {
    val back: () -> Unit = { navController.popBackStack() }

    // A tapped notification: open the estimate's review screen or Settings › AI.
    LaunchedEffect(openRequest, startWithOnboarding) {
        val request = openRequest ?: return@LaunchedEffect
        if (!startWithOnboarding) {
            when {
                request.startsWith("estimate:") -> navController.navigate(ReviewDestination(request.removePrefix("estimate:")))
                request == "settings_ai" -> navController.navigate(AiSettingsDestination)
                request.startsWith("weekly:") -> navController.navigate(WeeklyDestination(request.removePrefix("weekly:")))
            }
        }
        onOpenHandled()
    }

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
            val openDate by entry.savedStateHandle.getStateFlow<String?>(OPEN_DATE, null).collectAsStateWithLifecycle()
            TodayRoute(
                actions = TodayActions(
                    openSettings = { navController.navigate(SettingsDestination) },
                    openSearch = { meal, date -> navController.navigate(SearchDestination(meal, date.toString())) },
                    openScanner = { meal, date -> navController.navigate(ScannerDestination(meal, date.toString())) },
                    openWeight = { navController.navigate(WeightDestination) },
                    openPhoto = { meal, date -> navController.navigate(PhotoMealDestination(meal, date.toString())) },
                    openEstimate = { id -> navController.navigate(ReviewDestination(id)) },
                    openHealthConnect = { navController.navigate(HealthConnectDestination) },
                    openActivity = { date -> navController.navigate(ActivityDestination(date.toString())) },
                    openTrends = { navController.navigate(TrendsDestination) },
                ),
                addedToMeal = added,
                onAddedShown = { entry.savedStateHandle[ADDED_TO] = null },
                openDate = openDate,
                onOpenDateShown = { entry.savedStateHandle[OPEN_DATE] = null },
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
                actions = SettingsActions(
                    profile = { navController.navigate(ProfileDestination) },
                    goalBudget = { navController.navigate(ProfileGoalsDestination) },
                    dailyTargets = { navController.navigate(DailyTargetsDestination) },
                    meals = { navController.navigate(MealsDestination) },
                    water = { navController.navigate(WaterSettingsDestination) },
                    units = { navController.navigate(UnitsDestination) },
                    healthConnect = { navController.navigate(HealthConnectDestination) },
                    ai = { navController.navigate(AiSettingsDestination) },
                    foodDatabases = { navController.navigate(FoodDatabasesDestination) },
                    notifications = { navController.navigate(NotificationsDestination) },
                    appearance = { navController.navigate(AppearanceDestination) },
                    backup = { navController.navigate(BackupDestination) },
                    about = { navController.navigate(AboutDestination) },
                ),
            )
        }
        composable<ProfileGoalsDestination> { GoalBudgetRoute(onBack = back, onOpenWeekly = { navController.navigate(WeeklyDestination()) }) }
        composable<ProfileDestination> { ProfileRoute(onBack = back) }
        composable<UnitsDestination> { UnitsRoute(onBack = back) }
        composable<WaterSettingsDestination> { WaterSettingsRoute(onBack = back) }
        composable<FoodDatabasesDestination> { FoodDatabasesRoute(onBack = back) }
        composable<AiSettingsDestination> { entry ->
            val deleteRequest by entry.savedStateHandle.getStateFlow<String?>(DELETE_AI_PROFILE, null).collectAsStateWithLifecycle()
            AiSettingsRoute(
                onBack = back,
                onEditProfile = { id -> navController.navigate(AiProfileEditDestination(profileId = id)) },
                onSetUpFreeGemini = { navController.navigate(AiProfileEditDestination(preset = AiProfileEditViewModel.FREE_GEMINI)) },
                deleteRequest = deleteRequest,
                onDeleteHandled = { entry.savedStateHandle[DELETE_AI_PROFILE] = null },
            )
        }
        composable<AiProfileEditDestination> {
            AiProfileEditRoute(
                onBack = back,
                onDelete = { id ->
                    runCatching { navController.getBackStackEntry<AiSettingsDestination>().savedStateHandle[DELETE_AI_PROFILE] = id }
                    navController.popBackStack<AiSettingsDestination>(inclusive = false)
                },
            )
        }
        composable<NotificationsDestination> { NotificationsRoute(onBack = back) }
        composable<MealsDestination> { MealsRoute(onBack = back) }
        composable<DailyTargetsDestination> { DailyTargetsRoute(onBack = back) }
        composable<HealthConnectDestination> { HealthConnectRoute(onBack = back) }
        composable<ActivityDestination> { ActivityRoute(onBack = back) }
        composable<WeeklyDestination> { WeeklyCheckInRoute(onBack = back) }
        composable<PhotoMealDestination> {
            PhotoMealRoute(
                onBack = back,
                onQueued = { navController.popBackStack<TodayDestination>(inclusive = false) },
                onOpenAiSettings = { navController.navigate(AiSettingsDestination) },
            )
        }
        composable<ReviewDestination> { ReviewRoute(onBack = back) }
        composable<BackupDestination> { BackupRoute(onBack = back) }
        composable<WeightDestination> { WeightRoute(onBack = back) }
        composable<TrendsDestination> {
            TrendsRoute(
                onBack = back,
                onOpenDay = { date ->
                    runCatching { navController.getBackStackEntry<TodayDestination>().savedStateHandle[OPEN_DATE] = date.toString() }
                    navController.popBackStack<TodayDestination>(inclusive = false)
                },
            )
        }
        composable<AppearanceDestination> { AppearanceRoute(onBack = back) }
        composable<AboutDestination> { AboutRoute(onBack = back) }
    }
}

private inline fun <reified T : Any> NavBackStackEntry.routeOrNull(): T? = runCatching { toRoute<T>() }.getOrNull()
