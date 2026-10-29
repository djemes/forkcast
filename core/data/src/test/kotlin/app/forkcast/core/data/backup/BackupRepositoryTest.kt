package app.forkcast.core.data.backup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.db.ActivityDayEntity
import app.forkcast.core.data.db.DayStatusEntity
import app.forkcast.core.data.db.TdeeEstimateEntity
import app.forkcast.core.model.ExerciseSession
import app.forkcast.core.model.TdeeConfidence
import app.forkcast.core.model.TdeeMethod
import app.forkcast.core.data.db.FoodEntity
import app.forkcast.core.data.db.ForkcastDatabase
import app.forkcast.core.data.db.GoalEntity
import app.forkcast.core.data.db.LogEntryEntity
import app.forkcast.core.data.db.MealSlotEntity
import app.forkcast.core.data.db.ProfileEntity
import app.forkcast.core.data.db.SavedMealEntity
import app.forkcast.core.data.db.SavedMealItemEntity
import app.forkcast.core.data.db.TargetOverrideEntity
import app.forkcast.core.data.db.WaterEntryEntity
import app.forkcast.core.data.db.WeightEntryEntity
import app.forkcast.core.model.ActivityLevel
import app.forkcast.core.model.AppSettings
import app.forkcast.core.model.ExerciseMode
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.MealEstimateJson
import app.forkcast.core.model.ProviderId
import app.forkcast.core.model.TierLabel
import app.forkcast.core.data.db.AiComponentEntity
import app.forkcast.core.data.db.AiEstimateEntity
import app.forkcast.core.data.db.AiMatchCacheEntity
import app.forkcast.core.data.db.AiProfileEntity
import app.forkcast.core.data.db.ComponentSource
import app.forkcast.core.data.db.EstimateStatus
import app.forkcast.core.data.db.NotificationLogEntity
import app.forkcast.core.data.db.UsageRecordEntity
import app.forkcast.core.model.GoalType
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.ServingSize
import app.forkcast.core.model.Sex
import app.forkcast.core.model.ThemeMode
import app.forkcast.core.model.WeightSource
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class BackupRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, ForkcastDatabase::class.java)
        .allowMainThreadQueries().build()
    private val clock = Clock.fixed(Instant.parse("2026-10-18T16:00:00Z"), ZoneId.of("America/New_York"))
    private val settings = MemoryDataStore(AppSettings())
    private val repo = RoomBackupRepository(context, db, settings, clock, "0.1.0")
    private val dao = db.backupDao()
    private val day = LocalDate.of(2026, 10, 18)

    @After fun tearDown() {
        db.close()
        context.filesDir.resolve(RoomBackupRepository.SAFETY_DIR).deleteRecursively()
    }

    @Test fun roundTripIsIdenticalExceptExportedAt() = runTest {
        seedEveryTable()
        settings.updateData { it.copy(appearance = it.appearance.copy(themeMode = ThemeMode.DARK)) }
        val first = repo.exportJson()

        dao.clearAll()
        settings.updateData { AppSettings() }
        val parsed = repo.parse(first) as ParsedBackup.Ok
        repo.import(parsed.export, ImportMode.REPLACE)
        val second = repo.exportJson()

        assertThat(withoutExportedAt(second)).isEqualTo(withoutExportedAt(first))
        assertThat(settings.data.first().appearance.themeMode).isEqualTo(ThemeMode.DARK)
        // Soft-deleted rows travel too.
        assertThat(dao.logEntries().single { it.id == "e2" }.deletedAt).isEqualTo(50L)
    }

    /** REVIEW-2 M1: a crafted backup can't point photo paths at other app files. */
    @Test fun photoPathsOutsideThePhotoFolderAreDropped() = runTest {
        seedEveryTable()
        val json = repo.exportJson().replace("\"a1/0.jpg\"", "\"a1/0.jpg\", \"../databases/forkcast.db\", \"/data/x\", \"a1/../../secrets/store.bin\"")
        val parsed = repo.parse(json) as ParsedBackup.Ok
        assertThat(parsed.export.aiEstimates.single().photoPaths).containsExactly("a1/0.jpg")
        val photos = app.forkcast.core.data.ai.PhotoStore(context)
        assertThat(photos.file("../databases/forkcast.db").exists()).isFalse()
        assertThat(photos.file("../databases/forkcast.db").canonicalPath).startsWith(photos.root.canonicalPath)
        assertThat(photos.file("a1/0.jpg")).isEqualTo(File(photos.root, "a1/0.jpg"))
    }

    @Test fun exportHasSchemaFieldsAndNoSecrets() = runTest {
        seedEveryTable()
        val json = repo.exportJson()
        val root = BackupJson.parseToJsonElement(json).jsonObject
        assertThat(root.keys).containsAtLeast(
            "schemaVersion", "app", "appVersion", "exportedAt", "timeZone", "profile", "goals",
            "targetOverrides", "tdeeEstimates", "mealSlots", "foods", "recipes", "recipeIngredients", "savedMeals",
            "logEntries", "aiEstimates", "aiComponents", "aiProfiles", "usageRecords", "notificationLog", "aiMatchCache", "weights", "water", "activityDays", "dayStatus", "settings",
        )
        assertThat(root["exportedAt"].toString()).isEqualTo("\"2026-10-18T16:00:00Z\"")
        assertThat(json).contains("\"ENERGY_KCAL\"")
        assertThat(json.lowercase()).doesNotContain("apikey")
        assertThat(json.lowercase()).doesNotContain("api_key")
    }

    /** core/data/schemas/export/export-schema-v7.json must list exactly the fields the app writes, and the same enum values. */
    @Test fun schemaFileMatchesExport() = runTest {
        seedEveryTable()
        val export = BackupJson.parseToJsonElement(repo.exportJson()).jsonObject
        val schema = BackupJson.parseToJsonElement(exportSchema("export-schema-v7.json").readText()).jsonObject
        val defs = schema.getValue("\$defs").jsonObject

        assertThat(schema.getValue("properties").jsonObject.keys).containsExactlyElementsIn(export.keys)
        val tables = mapOf(
            "goals" to "goal", "targetOverrides" to "targetOverride", "mealSlots" to "mealSlot", "foods" to "food",
            "savedMeals" to "savedMeal", "savedMealItems" to "savedMealItem", "logEntries" to "logEntry",
            "weights" to "weight", "water" to "water", "dayStatus" to "dayStatus",
            "aiEstimates" to "aiEstimate", "aiComponents" to "aiComponent", "aiProfiles" to "aiProfile",
            "usageRecords" to "usageRecord", "notificationLog" to "notificationLog", "aiMatchCache" to "aiMatchCache",
            "tdeeEstimates" to "tdeeEstimate", "activityDays" to "activityDay",
            "recipes" to "recipe", "recipeIngredients" to "recipeIngredient",
        )
        tables.forEach { (table, def) ->
            val row = export.getValue(table).jsonArray.first().jsonObject
            assertWithMessage(def).that(defs.getValue(def).jsonObject.getValue("properties").jsonObject.keys)
                .containsExactlyElementsIn(row.keys)
        }
        assertThat(defs.getValue("profile").jsonObject.getValue("properties").jsonObject.keys)
            .containsExactlyElementsIn(export.getValue("profile").jsonObject.keys)

        fun enumOf(vararg path: String): List<String> {
            var node: JsonObject = defs
            path.forEach { node = node.getValue(it).jsonObject }
            return node.getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        }
        assertThat(enumOf("foodSource")).containsExactlyElementsIn(FoodSource.entries.map { it.name })
        assertThat(enumOf("profile", "properties", "activityLevel")).containsExactlyElementsIn(ActivityLevel.entries.map { it.name })
        assertThat(enumOf("goal", "properties", "goalType")).containsExactlyElementsIn(GoalType.entries.map { it.name })
        assertThat(enumOf("weight", "properties", "source")).containsExactlyElementsIn(WeightSource.entries.map { it.name })
    }

    /** The JSON Schema files next to Room's schemas; tests run from the module or the project folder. */
    private fun exportSchema(name: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, "schemas/export/$name"), File(it, "core/data/schemas/export/$name")) }
            .first { it.exists() }

    @Test fun previewCountsVisibleRowsAndDateRange() = runTest {
        seedEveryTable()
        val preview = (repo.parse(repo.exportJson()) as ParsedBackup.Ok).preview
        assertThat(preview.counts.logEntries).isEqualTo(1) // e2 is deleted
        assertThat(preview.counts.foods).isEqualTo(1)
        assertThat(preview.counts.weights).isEqualTo(1)
        assertThat(preview.counts.hasProfile).isTrue()
        assertThat(preview.firstDate).isEqualTo(day.minusDays(3))
        assertThat(preview.lastDate).isEqualTo(day)
        assertThat(preview.appVersion).isEqualTo("0.1.0")
    }

    @Test fun rejectsNewerSchemaOtherAppsAndGarbage() {
        assertThat(repo.parse("""{"app":"Forkcast","schemaVersion":8}"""))
            .isEqualTo(ParsedBackup.Rejected(RejectReason.NEWER_VERSION))
        assertThat(repo.parse("""{"app":"Other","schemaVersion":1}"""))
            .isEqualTo(ParsedBackup.Rejected(RejectReason.NOT_FORKCAST))
        assertThat(repo.parse("not json")).isEqualTo(ParsedBackup.Rejected(RejectReason.UNREADABLE))
        assertThat(repo.parse("""{"app":"Forkcast","schemaVersion":6,"appVersion":"x","exportedAt":"x","timeZone":"UTC","logEntries":[{"id":1}]}"""))
            .isEqualTo(ParsedBackup.Rejected(RejectReason.UNREADABLE))
    }

    /** Files from before 1.2 (export v1–v5) are refused as too old (owner, 2026-10-24). */
    @Test fun rejectsExportsOlderThanTheVersionBefore() {
        for (v in 1..5) {
            assertThat(repo.parse("""{"app":"Forkcast","schemaVersion":$v,"appVersion":"1.1.0","exportedAt":"2026-10-18T16:00:00Z","timeZone":"UTC"}"""))
                .isEqualTo(ParsedBackup.Rejected(RejectReason.OLD_VERSION))
        }
    }

    /** Phase 3 write state is per phone: never exported, so an imported row is written to Health Connect again. */
    @Test fun healthConnectWriteStateIsNotExported() = runTest {
        seedEveryTable()
        val json = repo.exportJson()
        assertThat(json).doesNotContain("hcSyncedAt")
        assertThat(json).contains("\"hcRecordId\": \"hc-h1\"")
        dao.clearAll()
        repo.import((repo.parse(json) as ParsedBackup.Ok).export, ImportMode.REPLACE)
        assertThat(dao.water().single().hcSyncedAt).isNull()
    }

    @Test fun mergeMatchesActivityDaysByDate() = runTest {
        dao.upsertActivityDays(listOf(ActivityDayEntity("mine", day, 100, null, null, null, emptyList(), 1, 10)))
        repo.import(export(activityDays = listOf(ActivityDayEntity("theirs", day, 9000, null, null, null, emptyList(), 1, 20))), ImportMode.MERGE)
        assertThat(dao.activityDays().map { it.id to it.steps }).containsExactly("theirs" to 9000L)
    }

    /** A 1.2/1.3 file (v6) imports in 1.4: its entries keep their times and `timeEstimated` is ignored. */
    @Test fun importsV6WithTimeEstimated() = runTest {
        seedEveryTable()
        val v7 = BackupJson.parseToJsonElement(repo.exportJson()).jsonObject
        val v6 = kotlinx.serialization.json.JsonObject(
            v7 + mapOf(
                "schemaVersion" to kotlinx.serialization.json.JsonPrimitive(6),
                "logEntries" to kotlinx.serialization.json.JsonArray(
                    v7.getValue("logEntries").jsonArray.map { e ->
                        kotlinx.serialization.json.JsonObject(e.jsonObject + ("timeEstimated" to kotlinx.serialization.json.JsonPrimitive(true)))
                    },
                ),
            ),
        )
        val parsed = repo.parse(v6.toString()) as ParsedBackup.Ok
        val before = dao.logEntries()
        dao.clearAll()
        repo.import(parsed.export, ImportMode.REPLACE)
        assertThat(dao.logEntries().map { it.id to it.eatenMinute }).containsExactlyElementsIn(before.map { it.id to it.eatenMinute })
    }

    @Test fun acceptsMinimalV6AndUnknownFields() {
        val parsed = repo.parse(
            """{"app":"Forkcast","schemaVersion":6,"appVersion":"1.2.1","exportedAt":"2026-10-18T16:00:00Z","timeZone":"UTC","futureField":true}""",
        )
        assertThat(parsed).isInstanceOf(ParsedBackup.Ok::class.java)
    }

    @Test fun mergeNewerWinsAndDeletionsTravel() = runTest {
        dao.upsertLogEntries(listOf(entry("a", "Old name", updatedAt = 10), entry("b", "Mine is newer", updatedAt = 90), entry("c", "Kept", updatedAt = 10)))
        val incoming = export(
            logEntries = listOf(
                entry("a", "New name", updatedAt = 20),
                entry("b", "Stale", updatedAt = 30),
                entry("c", "Kept", updatedAt = 40, deletedAt = 40),
                entry("d", "Brand new", updatedAt = 5),
            ),
        )

        val summary = repo.import(incoming, ImportMode.MERGE)

        val rows = dao.logEntries().associateBy { it.id }
        assertThat(rows.getValue("a").displayName).isEqualTo("New name")
        assertThat(rows.getValue("b").displayName).isEqualTo("Mine is newer")
        assertThat(rows.getValue("c").deletedAt).isEqualTo(40L)
        assertThat(rows.getValue("d").displayName).isEqualTo("Brand new")
        assertThat(summary).isEqualTo(ImportSummary(ImportMode.MERGE, added = 1, updated = 2, unchanged = 1))
    }

    @Test fun mergeKeepsLocalSettingsAndNoSafetyBackup() = runTest {
        settings.updateData { it.copy(waterTargetMl = 3000.0) }
        repo.import(export(settings = AppSettings(waterTargetMl = 1000.0)), ImportMode.MERGE)
        assertThat(settings.data.first().waterTargetMl).isEqualTo(3000.0)
        assertThat(repo.readSafetyBackup()).isNull()
    }

    @Test fun mergeMapsMealSlotsFromAnotherPhoneByName() = runTest {
        dao.upsertMealSlots(listOf(slot("here-lunch", "Lunch"), slot("here-dinner", "Dinner")))
        val incoming = export(
            mealSlots = listOf(slot("there-lunch", "Lunch"), slot("there-brunch", "Brunch")),
            logEntries = listOf(entry("x", "Soup", slotId = "there-lunch"), entry("y", "Eggs", slotId = "there-brunch")),
        )
        repo.import(incoming, ImportMode.MERGE)

        assertThat(dao.mealSlots().map { it.id }).containsExactly("here-lunch", "here-dinner", "there-brunch")
        val rows = dao.logEntries().associateBy { it.id }
        assertThat(rows.getValue("x").mealSlotId).isEqualTo("here-lunch")
        assertThat(rows.getValue("y").mealSlotId).isEqualTo("there-brunch")
    }

    @Test fun mergeLeavesOneActiveGoal() = runTest {
        dao.upsertGoals(listOf(goal("mine", active = true, updatedAt = 10)))
        repo.import(export(goals = listOf(goal("theirs", active = true, updatedAt = 20))), ImportMode.MERGE)
        assertThat(dao.goals().filter { it.isActive }.map { it.id }).containsExactly("theirs")
    }

    @Test fun mergeMatchesDayStatusByDate() = runTest {
        dao.upsertDayStatus(listOf(DayStatusEntity("s-here", day, false, null, 1, 10)))
        repo.import(export(dayStatus = listOf(DayStatusEntity("s-there", day, true, "done", 1, 20))), ImportMode.MERGE)
        assertThat(dao.dayStatus().single().markedComplete).isTrue()
    }

    @Test fun replaceWritesSafetyBackupFirst() = runTest {
        dao.upsertLogEntries(listOf(entry("old", "Before replace")))
        repo.import(export(logEntries = listOf(entry("new", "After"))), ImportMode.REPLACE)

        assertThat(dao.logEntries().map { it.id }).containsExactly("new")
        val saved = (repo.parse(repo.readSafetyBackup()!!) as ParsedBackup.Ok).export
        assertThat(saved.logEntries.map { it.id }).containsExactly("old")
    }

    /** REVIEW-2 S4: a file can't quietly point AI estimates at someone else's server. */
    @Test fun customAiServersAreNamedAndArriveTurnedOff() = runTest {
        // This phone already has its own Ollama profile on the same server: it stays on.
        dao.upsertAiProfiles(listOf(aiProfile("mine", "http://192.168.1.20:11434/v1/", updatedAt = 1)))
        val incoming = export(
            aiProfiles = listOf(
                aiProfile("mine", "http://192.168.1.20:11434/v1", updatedAt = 5),
                aiProfile("theirs", "https://collector.example:8443", updatedAt = 5),
                aiProfile("openai", "https://api.openai.com", updatedAt = 5),
                aiProfile("gemini", null, provider = ProviderId.GEMINI, updatedAt = 5),
            ),
        )
        assertThat(repo.parse(BackupJson.encodeToString(ForkcastExport.serializer(), incoming)).let { (it as ParsedBackup.Ok).preview.aiServers })
            .containsExactly("192.168.1.20:11434", "collector.example:8443").inOrder()

        repo.import(incoming, ImportMode.MERGE)
        val merged = dao.aiProfiles().associate { it.id to it.enabled }
        assertThat(merged).containsExactly("mine", true, "theirs", false, "openai", true, "gemini", true)

        // A replace with an existing profile moved to another server turns it off too.
        repo.import(export(aiProfiles = listOf(aiProfile("mine", "http://203.0.113.9:11434", updatedAt = 9))), ImportMode.REPLACE)
        assertThat(dao.aiProfiles().single().enabled).isFalse()
    }

    private fun aiProfile(id: String, baseUrl: String?, updatedAt: Long, provider: ProviderId = ProviderId.OPENAI_COMPAT) =
        AiProfileEntity(id, id, provider, "m", baseUrl, TierLabel.FREE, 0, true, false, null, 1, updatedAt)

    private fun withoutExportedAt(json: String): JsonObject =
        JsonObject(BackupJson.parseToJsonElement(json).jsonObject - "exportedAt")

    private fun export(
        mealSlots: List<MealSlotEntity> = emptyList(),
        goals: List<GoalEntity> = emptyList(),
        logEntries: List<LogEntryEntity> = emptyList(),
        dayStatus: List<DayStatusEntity> = emptyList(),
        activityDays: List<ActivityDayEntity> = emptyList(),
        settings: AppSettings? = null,
        aiProfiles: List<AiProfileEntity> = emptyList(),
    ) = ForkcastExport(
        appVersion = "0.1.0", exportedAt = "2026-10-18T16:00:00Z", timeZone = "America/New_York",
        mealSlots = mealSlots, goals = goals, logEntries = logEntries, dayStatus = dayStatus,
        activityDays = activityDays, settings = settings, aiProfiles = aiProfiles,
    )

    private fun slot(id: String, name: String) = MealSlotEntity(id, name, 0, null, null, 1, 1, null)

    private fun goal(id: String, active: Boolean, updatedAt: Long) = GoalEntity(
        id, GoalType.LOSE, day, 90.0, 80.0, null, 0.5, active, 1, updatedAt,
    )

    private fun entry(
        id: String,
        name: String,
        updatedAt: Long = 1,
        deletedAt: Long? = null,
        slotId: String = "lunch",
        date: LocalDate = day,
    ) = LogEntryEntity(
        id, date, "America/New_York", slotId, "f1", name, null, 1.0, "1 cup", 158.0,
        mapOf(NutrientKey.ENERGY_KCAL to 205.0, NutrientKey.PROTEIN_G to 4.3), FoodSource.USDA,
        null, null, null, 1, 1, updatedAt, deletedAt,
    )

    private suspend fun seedEveryTable() {
        dao.upsertProfiles(listOf(ProfileEntity(sex = Sex.FEMALE, birthDate = LocalDate.of(1990, 3, 1), heightCm = 168.0,
            activityLevel = ActivityLevel.LIGHT, exerciseMode = ExerciseMode.INCLUDED, exerciseAddBackPct = 50,
            createdAt = 1, updatedAt = 2)))
        dao.upsertGoals(listOf(goal("g1", active = true, updatedAt = 3)))
        dao.upsertTargetOverrides(listOf(TargetOverrideEntity("t1", "PROTEIN", 120.0, day, 1, 1)))
        dao.upsertMealSlots(listOf(MealSlotEntity("lunch", "Lunch", 1, 660, 900, 1, 1, null)))
        dao.upsertFoods(listOf(FoodEntity("f1", "Rice, \"white\"", null, FoodSource.USDA, "123", "00012345678905",
            listOf(ServingSize("1 cup", 158.0)), mapOf(NutrientKey.ENERGY_KCAL to 130.0), true, 5, 1.0, "1 cup", 1, 5)))
        dao.upsertSavedMeals(listOf(SavedMealEntity("m1", "Lunch box", 1, 1)))
        dao.upsertRecipes(listOf(app.forkcast.core.data.db.RecipeEntity("r1", "Chili", 4.0, 1200.0, "f1", 1, 2)))
        dao.upsertRecipeIngredients(listOf(app.forkcast.core.data.db.RecipeIngredientEntity("ri1", "r1", 0, "f1", "Rice", null, 1.0, "1 cup", 158.0,
            mapOf(NutrientKey.ENERGY_KCAL to 205.0), 1, 1)))
        dao.upsertSavedMealItems(listOf(SavedMealItemEntity("i1", "m1", 0, "f1", "Rice", null, 1.0, "1 cup", 158.0,
            mapOf(NutrientKey.ENERGY_KCAL to 205.0), FoodSource.USDA, 1, 1)))
        dao.upsertLogEntries(listOf(entry("e1", "Rice"), entry("e2", "Deleted", updatedAt = 50, deletedAt = 50, date = day.minusDays(9))))
        dao.upsertWeights(listOf(WeightEntryEntity("w1", day.minusDays(3).atTime(12, 0).atZone(clock.zone).toInstant().toEpochMilli(),
            80.5, null, WeightSource.MANUAL, null, 1, 1)))
        dao.upsertWater(listOf(WaterEntryEntity("h1", 1, day, "America/New_York", 250.0, 1, 1, hcRecordId = "hc-h1", hcSyncedAt = 1)))
        dao.upsertActivityDays(listOf(ActivityDayEntity("ad1", day, 8200, 410.0, 2650.0, 35,
            listOf(ExerciseSession("hc-s1", "Morning run", 56, 1_000, 2_000, 300.0)), 1, 1)))
        dao.upsertTdeeEstimates(listOf(TdeeEstimateEntity("td1", day, TdeeMethod.ADAPTIVE, 2640.0, 2700.0, TdeeConfidence.MEDIUM, 18, 9, 1, 1)))
        dao.upsertDayStatus(listOf(DayStatusEntity("d1", day, true, null, 1, 1)))
        val ai = db.aiDao()
        ai.upsertEstimate(AiEstimateEntity(
            id = "a1", date = day, zoneId = "America/New_York", mealSlotId = "lunch", logEntryId = "e1", status = EstimateStatus.DONE,
            profileId = "p1", profileName = "Gemini", provider = ProviderId.GEMINI, model = "gemini-3.8-flash", tierLabel = TierLabel.FREE,
            description = "rice", personalContext = null, photoPaths = listOf("a1/0.jpg"), localTime = "12:40", unitsHint = "metric",
            correction = null, promptVersion = "meal-v1", rawJson = "{}", result = MealEstimateJson("Rice"), warnings = emptyList(),
            mealName = "Rice", kcalLow = 150.0, kcalExpected = 205.0, kcalHigh = 260.0, inputTokens = 10, outputTokens = 5, latencyMs = 900,
            errorCode = null, errorDetail = null, attempts = 1, retryAfter = null, notified = true, reviewedAt = null, createdAt = 1, updatedAt = 1,
        ))
        ai.upsertComponents(listOf(AiComponentEntity("c1", "a1", 0, "Rice", "boiled", 158.0, 120.0, 200.0, 0.8, false, "Rice, white, cooked",
            "f1", "123", "Rice", 0.9, mapOf(NutrientKey.ENERGY_KCAL to 130.0), mapOf(NutrientKey.ENERGY_KCAL to 130.0), ComponentSource.USDA,
            mapOf(NutrientKey.ENERGY_KCAL to 205.0), 1, 1)))
        ai.upsertProfile(AiProfileEntity("p1", "Gemini", ProviderId.GEMINI, "gemini-3.8-flash", null, TierLabel.FREE, 0, true, false, null, 1, 1))
        ai.upsertUsage(UsageRecordEntity("u1", day, "p1", ProviderId.GEMINI, "gemini-3.8-flash", 2, 1, 0, 0, 10, 5, 1, 1))
        ai.insertNotification(NotificationLogEntity("n1", day, "budget", 80, 1, 1, 1))
        ai.upsertMatch(AiMatchCacheEntity("rice white cooked | boiled", "f1", "123", 0.9, 1, 1))
    }
}

/** DataStore stand-in; the real one needs a file and a scope that outlives runTest. */
class MemoryDataStore<T>(initial: T) : DataStore<T> {
    private val state = MutableStateFlow(initial)
    private val lock = Mutex()
    override val data = state
    override suspend fun updateData(transform: suspend (t: T) -> T): T =
        lock.withLock { transform(state.value).also { state.value = it } }
}
