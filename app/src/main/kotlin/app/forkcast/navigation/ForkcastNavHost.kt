package app.forkcast.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
import app.forkcast.ui.ai.AiSetupRoute
import app.forkcast.ui.backup.BackupRoute
import app.forkcast.ui.activity.ActivityRoute
import app.forkcast.ui.weekly.WeeklyCheckInRoute
import app.forkcast.ui.health.HealthConnectRoute
import app.forkcast.ui.help.HelpEntry
import app.forkcast.ui.help.HelpRoute
import app.forkcast.ui.help.HelpTopic
import app.forkcast.ui.help.LocalHelp
import app.forkcast.ui.photo.PhotoMealRoute
import app.forkcast.ui.photo.ReviewRoute
import app.forkcast.ui.settings.GoalBudgetRoute
import app.forkcast.ui.settings.ProfileRoute
import app.forkcast.ui.settings.DebugTool
import app.forkcast.ui.settings.DebugToolsEvent
import app.forkcast.ui.settings.DebugToolsViewModel
import app.forkcast.ui.settings.SettingsActions
import app.forkcast.ui.settings.UnitsRoute
import app.forkcast.ui.settings.WaterSettingsRoute
import app.forkcast.ui.settings.SettingsScreen
import app.forkcast.ui.today.TodayActions
import app.forkcast.ui.today.TodayRoute
import app.forkcast.ui.theme.Motion
import app.forkcast.widget.FoodOpen
import app.forkcast.widget.OPEN_LOG
import app.forkcast.widget.OPEN_PHOTO
import app.forkcast.widget.OPEN_SCAN
import app.forkcast.ui.trends.TrendsRoute
import app.forkcast.ui.recipes.RecipeEditRoute
import app.forkcast.ui.recipes.RecipesRoute
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
@Serializable data object TermsDestination
@Serializable data object PrivacyPolicyDestination
@Serializable data object UpdatesDestination
@Serializable data object LanguageDestination
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
/** [describe]: "Describe" (no photo, SPEC §4.6.2), with [text] prefilled from a search. */
@Serializable data class PhotoMealDestination(val mealSlotId: String, val date: String, val describe: Boolean = false, val text: String? = null)

@Serializable data class ReviewDestination(val estimateId: String)

/** Settings › Help, opened at [entry] (a [app.forkcast.ui.help.HelpEntry] name) when an error links to it. */
@Serializable data class HelpDestination(val entry: String? = null)

/** The guided AI setup (roadmap 2.0 task 3.3). */
@Serializable data object AiSetupDestination

/** Edit [profileId], or create a new profile (null). [preset] "openai" = start on OpenAI-compatible (the setup guide's Advanced). */
@Serializable data class AiProfileEditDestination(val profileId: String? = null, val preset: String? = null)

/** Log into [mealSlotId] on [date] (ISO yyyy-MM-dd). */
/** [query]: start with this search ("Log manually instead"); [photo]: that estimate's photo, shown while searching. */
@Serializable data class SearchDestination(
    val mealSlotId: String,
    val date: String,
    val forRecipe: Boolean = false,
    val query: String? = null,
    val photo: String? = null,
)

/** [forRecipe]: picking an ingredient ("Add to recipe"); [mealSlotId] and [date] are then empty. */
@Serializable data class FoodDetailDestination(val foodId: String, val mealSlotId: String, val date: String, val forRecipe: Boolean = false)

/** Create (foodId null) or edit a custom food; [barcode] and [prefill] (scanned label JSON) pre-fill a new one. */
@Serializable data class CustomFoodDestination(
    val foodId: String? = null,
    val barcode: String? = null,
    val mealSlotId: String? = null,
    val date: String? = null,
    val prefill: String? = null,
    val forRecipe: Boolean = false,
)

@Serializable data class ScannerDestination(val mealSlotId: String, val date: String, val forRecipe: Boolean = false)

/** Photograph a Nutrition Facts panel for an unknown [barcode]. */
@Serializable data class LabelScanDestination(val barcode: String, val mealSlotId: String, val date: String, val forRecipe: Boolean = false)

/** Recipe list; with [mealSlotId] and [date] a tap logs the recipe there. */
@Serializable data class RecipesDestination(val mealSlotId: String? = null, val date: String? = null)

/** Edit [recipeId] (or the recipe behind [foodId]), or a new one named [name]; [mealSlotId]/[date] = log it after saving. */
@Serializable data class RecipeEditDestination(
    val recipeId: String? = null,
    val foodId: String? = null,
    val mealSlotId: String? = null,
    val date: String? = null,
    val name: String? = null,
)

/** Key on Today's back-stack entry: the meal name to confirm ("Added to Lunch"). */
private const val ADDED_TO = "added_to"

/** Key on Today's back-stack entry: open the Log sheet (the widget's Log button). */
private const val OPEN_LOG_SHEET = "open_log_sheet"

/** Key on Today's back-stack entry: a day to show (ISO date), set by Trends' "Open day". */
private const val OPEN_DATE = "open_date"

/** Key on the recipe editor's back-stack entry: an ingredient picked in food detail (RecipePick.encode). */
private const val RECIPE_PICK = "recipe_pick"

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
    LaunchedEffect(openRequest) {
        val request = openRequest ?: return@LaunchedEffect
        // Only once Today exists: not during onboarding, but right after it (startWithOnboarding
        // stays true for the whole launch, so it can't be the test).
        val ready = runCatching { navController.getBackStackEntry<TodayDestination>() }.isSuccess
        if (ready) {
            when {
                request.startsWith("estimate:") -> navController.navigate(ReviewDestination(request.removePrefix("estimate:")))
                request == "settings_ai" -> navController.navigate(AiSettingsDestination)
                request.startsWith("weekly:") -> navController.navigate(WeeklyDestination(request.removePrefix("weekly:")))
                request == "backup" -> navController.navigate(BackupDestination)
                request == "updates" && app.forkcast.Distribution.current.hasUpdater -> navController.navigate(UpdatesDestination)
                // Widget, shortcut and Quick Settings tile (Phase 5): over Today, logging into the meal now.
                else -> FoodOpen.parse(request)?.let { r ->
                    val day = (r.date ?: java.time.LocalDate.now()).toString()
                    navController.popBackStack<TodayDestination>(inclusive = false)
                    when (r.kind) {
                        OPEN_LOG -> runCatching { navController.getBackStackEntry<TodayDestination>().savedStateHandle[OPEN_LOG_SHEET] = r.mealId.orEmpty() }
                        OPEN_SCAN -> navController.navigate(ScannerDestination(r.mealId!!, day))
                        OPEN_PHOTO -> navController.navigate(PhotoMealDestination(r.mealId!!, day))
                        else -> navController.navigate(SearchDestination(r.mealId!!, day))
                    }
                }
            }
        }
        onOpenHandled()
    }

    /** Returns to Today and shows "Added to <meal>". */
    fun addedTo(mealName: String) {
        runCatching { navController.getBackStackEntry<TodayDestination>().savedStateHandle[ADDED_TO] = mealName }
        navController.popBackStack<TodayDestination>(inclusive = false)
    }

    // Forward: Material shared axis X (DESIGN.md §8), the new screen slides 30 dp in from the right
    // as the old one fades. Back (button, arrow or swipe): the page slides away to the right and the
    // one underneath slides in from a little to the left. With predictive back the same slide follows
    // the finger, so a swipe previews the page below (replaces Navigation's default 70 % zoom-out).
    val axis = with(LocalDensity.current) { Motion.SHARED_AXIS.roundToPx() }
    // Every error's "Help" opens its entry, from any screen (roadmap 2.0 task 3.4).
    val openHelp: (HelpEntry) -> Unit = { navController.navigate(HelpDestination(it.name)) { launchSingleTop = true } }
    CompositionLocalProvider(LocalHelp provides openHelp) {
        NavHost(
            navController = navController,
            startDestination = if (startWithOnboarding) OnboardingDestination else TodayDestination,
            enterTransition = { slideInHorizontally(Motion.enter(Motion.LONG)) { axis } + fadeIn(Motion.enter(Motion.LONG - Motion.SHARED_AXIS_FADE_OUT, delay = Motion.SHARED_AXIS_FADE_OUT)) },
            exitTransition = { slideOutHorizontally(Motion.exit(Motion.LONG)) { -axis } + fadeOut(Motion.exit(Motion.SHARED_AXIS_FADE_OUT)) },
            popEnterTransition = { backUnder(follow = false) },
            popExitTransition = { backAway(follow = false) },
            predictivePopEnterTransition = { _ -> backUnder(follow = true) },
            predictivePopExitTransition = { _ -> backAway(follow = true) },
        ) {
            composable<OnboardingDestination> {
                OnboardingRoute(
                    onFinished = {
                        navController.navigate(TodayDestination) {
                            popUpTo(OnboardingDestination) { inclusive = true }
                        }
                    },
                    extras = app.forkcast.ui.onboarding.ExtrasActions(
                        setUpAi = { navController.navigate(AiSetupDestination) },
                        healthConnect = { navController.navigate(HealthConnectDestination) },
                        backup = { navController.navigate(BackupDestination) },
                    ),
                )
            }
            composable<TodayDestination> { entry ->
                val added by entry.savedStateHandle.getStateFlow<String?>(ADDED_TO, null).collectAsStateWithLifecycle()
                val openDate by entry.savedStateHandle.getStateFlow<String?>(OPEN_DATE, null).collectAsStateWithLifecycle()
                val openLog by entry.savedStateHandle.getStateFlow<String?>(OPEN_LOG_SHEET, null).collectAsStateWithLifecycle()
                TodayRoute(
                    actions = TodayActions(
                        openSettings = { navController.navigate(SettingsDestination) },
                        openSearch = { meal, date -> navController.navigate(SearchDestination(meal, date.toString())) },
                        openScanner = { meal, date -> navController.navigate(ScannerDestination(meal, date.toString())) },
                        openWeight = { navController.navigate(WeightDestination) },
                        openPhoto = { meal, date -> navController.navigate(PhotoMealDestination(meal, date.toString())) },
                        openDescribe = { meal, date -> navController.navigate(PhotoMealDestination(meal, date.toString(), describe = true)) },
                        openEstimate = { id -> navController.navigate(ReviewDestination(id)) },
                        openHealthConnect = { navController.navigate(HealthConnectDestination) },
                        openActivity = { date -> navController.navigate(ActivityDestination(date.toString())) },
                        openTrends = { navController.navigate(TrendsDestination) },
                        openRecipes = { meal, date -> navController.navigate(RecipesDestination(meal, date.toString())) },
                        openKeys = { ai -> navController.navigate(if (ai) AiSettingsDestination else FoodDatabasesDestination) },
                        openBackup = { navController.navigate(BackupDestination) },
                    ),
                    addedToMeal = added,
                    onAddedShown = { entry.savedStateHandle[ADDED_TO] = null },
                    openDate = openDate,
                    onOpenDateShown = { entry.savedStateHandle[OPEN_DATE] = null },
                    openLogSheet = openLog,
                    onLogSheetOpened = { entry.savedStateHandle[OPEN_LOG_SHEET] = null },
                )
            }
            composable<SearchDestination> { entry ->
                val args = entry.routeOrNull<SearchDestination>()
                val forRecipe = args?.forRecipe == true
                SearchRoute(
                    onBack = back,
                    onOpenFood = { id ->
                        navController.navigate(FoodDetailDestination(id, args?.mealSlotId.orEmpty(), args?.date.orEmpty(), forRecipe))
                    },
                    onCreateFood = { navController.navigate(CustomFoodDestination(mealSlotId = args?.mealSlotId, date = args?.date, forRecipe = forRecipe)) },
                    onCreateRecipe = if (forRecipe) null else { query ->
                        navController.navigate(RecipeEditDestination(mealSlotId = args?.mealSlotId, date = args?.date, name = query.ifBlank { null }))
                    },
                    onDescribe = if (forRecipe) null else { query ->
                        navController.navigate(PhotoMealDestination(args?.mealSlotId.orEmpty(), args?.date.orEmpty(), describe = true, text = query))
                    },
                    onLoggedMeal = ::addedTo,
                    forRecipe = forRecipe,
                )
            }
            composable<ScannerDestination> { entry ->
                val args = entry.routeOrNull<ScannerDestination>()
                val meal = args?.mealSlotId.orEmpty()
                val date = args?.date.orEmpty()
                val forRecipe = args?.forRecipe == true
                ScannerRoute(
                    onBack = back,
                    onOpenFood = { id -> navController.navigate(FoodDetailDestination(id, meal, date, forRecipe)) },
                    onScanLabel = { gtin -> navController.navigate(LabelScanDestination(gtin, meal, date, forRecipe)) },
                    onEnterManually = { gtin -> navController.navigate(CustomFoodDestination(barcode = gtin, mealSlotId = meal, date = date, forRecipe = forRecipe)) },
                )
            }
            composable<LabelScanDestination> { entry ->
                val args = entry.routeOrNull<LabelScanDestination>()
                LabelScanRoute(
                    onBack = back,
                    onParsed = { prefill ->
                        navController.navigate(
                            CustomFoodDestination(
                                barcode = args?.barcode, mealSlotId = args?.mealSlotId, date = args?.date, prefill = prefill.encode(), forRecipe = args?.forRecipe == true,
                            ),
                        ) { popUpTo<LabelScanDestination> { inclusive = true } }
                    },
                )
            }
            composable<FoodDetailDestination> {
                FoodDetailRoute(
                    onBack = back,
                    onAdded = ::addedTo,
                    onEdit = { id -> navController.navigate(CustomFoodDestination(foodId = id)) },
                    onEditRecipe = { id -> navController.navigate(RecipeEditDestination(foodId = id)) },
                    onPicked = { pick ->
                        runCatching { navController.getBackStackEntry<RecipeEditDestination>().savedStateHandle[RECIPE_PICK] = pick.encode() }
                        navController.popBackStack<RecipeEditDestination>(inclusive = false)
                    },
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
                            // A new food goes straight to its detail screen, ready to log (or to add to the recipe).
                            navController.navigate(FoodDetailDestination(id, meal, date, args.forRecipe)) {
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
                val context = androidx.compose.ui.platform.LocalContext.current
                val multilingual = androidx.compose.runtime.remember { app.forkcast.AppLanguage.available(context).size > 1 }
                val debug: DebugToolsViewModel = hiltViewModel()
                val debugState by debug.state.collectAsStateWithLifecycle()
                SettingsScreen(
                    onBack = back,
                    message = debugState.message,
                    onMessageShown = { debug.onEvent(DebugToolsEvent.MessageShown) },
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
                        updates = { navController.navigate(UpdatesDestination) }.takeIf { app.forkcast.Distribution.current.hasUpdater },
                        language = { navController.navigate(LanguageDestination) }.takeIf { multilingual },
                        help = { navController.navigate(HelpDestination()) },
                        debugTools = debugState.tools.map { tool -> DebugTool(tool.label, tool.summary) { debug.onEvent(DebugToolsEvent.Run(tool)) } },
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
                    onAddAi = { navController.navigate(AiSetupDestination) },
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
            composable<AiSetupDestination> {
                AiSetupRoute(
                    onClose = back,
                    onAdvanced = {
                        navController.navigate(AiProfileEditDestination(preset = AiProfileEditViewModel.OPENAI)) {
                            popUpTo<AiSetupDestination> { inclusive = true }
                        }
                    },
                )
            }
            composable<NotificationsDestination> { NotificationsRoute(onBack = back) }
            composable<MealsDestination> { MealsRoute(onBack = back) }
            composable<DailyTargetsDestination> { DailyTargetsRoute(onBack = back) }
            composable<HealthConnectDestination> { HealthConnectRoute(onBack = back) }
            composable<ActivityDestination> { ActivityRoute(onBack = back, onConnect = { navController.navigate(HealthConnectDestination) }) }
            composable<WeeklyDestination> { WeeklyCheckInRoute(onBack = back) }
            composable<PhotoMealDestination> {
                PhotoMealRoute(
                    onBack = back,
                    onQueued = { navController.popBackStack<TodayDestination>(inclusive = false) },
                    // Over the photo screen, so finishing the guide lands on the camera.
                    onSetUpAi = { navController.navigate(AiSetupDestination) },
                )
            }
            composable<ReviewDestination> {
                ReviewRoute(
                    onBack = back,
                    onLogManually = { meal, date, query, photo ->
                        navController.navigate(SearchDestination(meal, date, query = query.ifBlank { null }, photo = photo)) {
                            popUpTo<ReviewDestination> { inclusive = true }
                        }
                    },
                )
            }
            composable<BackupDestination> { BackupRoute(onBack = back) }
            composable<WeightDestination> { WeightRoute(onBack = back) }
            composable<RecipesDestination> { entry ->
                val args = entry.routeOrNull<RecipesDestination>()
                RecipesRoute(
                    mealSlotId = args?.mealSlotId,
                    onBack = back,
                    onLog = { r ->
                        val meal = args?.mealSlotId
                        val date = args?.date
                        val foodId = r.foodId
                        if (foodId != null && meal != null && date != null) navController.navigate(FoodDetailDestination(foodId, meal, date))
                    },
                    onEdit = { r -> navController.navigate(RecipeEditDestination(recipeId = r.id)) },
                    onNew = { navController.navigate(RecipeEditDestination(mealSlotId = args?.mealSlotId, date = args?.date)) },
                )
            }
            composable<RecipeEditDestination> { entry ->
                val args = entry.routeOrNull<RecipeEditDestination>()
                val pick by entry.savedStateHandle.getStateFlow<String?>(RECIPE_PICK, null).collectAsStateWithLifecycle()
                val meal = args?.mealSlotId
                val date = args?.date
                RecipeEditRoute(
                    onBack = back,
                    onAddIngredient = { navController.navigate(SearchDestination("", "", forRecipe = true)) },
                    onScanIngredient = { navController.navigate(ScannerDestination("", "", forRecipe = true)) },
                    onSaved = { saved ->
                        if (saved.wasNew && meal != null && date != null && saved.foodId.isNotEmpty()) {
                            // Built while logging: straight to its detail, one serving ready to add.
                            navController.navigate(FoodDetailDestination(saved.foodId, meal, date)) {
                                popUpTo<RecipeEditDestination> { inclusive = true }
                            }
                        } else {
                            navController.popBackStack()
                        }
                    },
                    onDeleted = {
                        // Its food detail (if open below) would show a deleted food: go back past it.
                        val toList = runCatching { navController.getBackStackEntry<RecipesDestination>() }.isSuccess
                        if (toList) navController.popBackStack<RecipesDestination>(inclusive = false)
                        else navController.popBackStack<TodayDestination>(inclusive = false)
                    },
                    logAfterSave = meal != null && date != null,
                    pick = pick,
                    onPickHandled = { entry.savedStateHandle[RECIPE_PICK] = null },
                )
            }
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
            composable<AboutDestination> {
                AboutRoute(
                    onBack = back,
                    onOpenUpdates = { navController.navigate(UpdatesDestination) },
                    onOpenTerms = { navController.navigate(TermsDestination) },
                    onOpenPrivacy = { navController.navigate(PrivacyPolicyDestination) },
                )
            }
            composable<TermsDestination> { app.forkcast.ui.legal.TermsRoute(onBack = back) }
            composable<PrivacyPolicyDestination> { app.forkcast.ui.legal.PrivacyPolicyScreen(onBack = back) }
            composable<UpdatesDestination> { app.forkcast.ui.settings.UpdatesRoute(onBack = back) }
            composable<LanguageDestination> { app.forkcast.ui.settings.LanguageRoute(onBack = back) }
            composable<HelpDestination> {
                val entries = androidx.compose.runtime.remember {
                    // The Play build updates through Play, so the GitHub updater's answers don't apply.
                    HelpEntry.entries.filter { it.topic != HelpTopic.UPDATES || app.forkcast.Distribution.current.hasUpdater }
                }
                HelpRoute(onBack = back, entries = entries)
            }
        }
    }
}

private inline fun <reified T : Any> NavBackStackEntry.routeOrNull(): T? = runCatching { toRoute<T>() }.getOrNull()

/** Back: the page underneath slides in from a quarter width to the left and brightens. [follow]: tracks a swipe. */
private fun backUnder(follow: Boolean): EnterTransition =
    slideInHorizontally(if (follow) Motion.follow() else Motion.change(Motion.LONG)) { -it / 4 } +
        fadeIn(if (follow) Motion.follow() else Motion.change(Motion.LONG), initialAlpha = Motion.BACK_UNDER_ALPHA)

/** Back: the current page slides all the way out to the right. [follow]: tracks a swipe. */
private fun backAway(follow: Boolean): ExitTransition =
    slideOutHorizontally(if (follow) Motion.follow() else Motion.change(Motion.LONG)) { it }
