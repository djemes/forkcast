package app.forkcast.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

/** Breakfast, Lunch, Dinner, Snacks with default time windows. Snacks has no window (fallback). */
object DefaultMealSlots {

    data class Names(val breakfast: String, val lunch: String, val dinner: String, val snacks: String)

    /**
     * The default names in every language Forkcast ships. They're stored in the language the
     * phone had at install, so a meal still called one of them is shown in the app's current
     * language ([shown]); a name the user typed stays as typed.
     */
    private val DEFAULTS: List<Pair<Set<String>, (Names) -> String>> = listOf(
        setOf("breakfast", "café da manhã") to { n -> n.breakfast },
        setOf("lunch", "almoço") to { n -> n.lunch },
        setOf("dinner", "jantar") to { n -> n.dinner },
        setOf("snacks", "lanches") to { n -> n.snacks },
    )

    /** [name] in the app's language when it is one of the default names, else unchanged. */
    fun shown(name: String, current: Names): String {
        val key = name.trim().lowercase()
        return DEFAULTS.firstOrNull { (known, _) -> key in known }?.second?.invoke(current) ?: name
    }

    fun entities(names: Names, now: Long, newId: () -> String = { UUID.randomUUID().toString() }) = listOf(
        MealSlotEntity(newId(), names.breakfast, 0, 4 * 60, 11 * 60, now, now, null),
        MealSlotEntity(newId(), names.lunch, 1, 11 * 60, 15 * 60, now, now, null),
        MealSlotEntity(newId(), names.dinner, 2, 17 * 60, 22 * 60, now, now, null),
        MealSlotEntity(newId(), names.snacks, 3, null, null, now, now, null),
    )

    /** Called from the database onCreate callback, inside the creation transaction. */
    fun insertInto(db: SupportSQLiteDatabase, names: Names, now: Long = System.currentTimeMillis()) {
        entities(names, now).forEach { e ->
            db.execSQL(
                "INSERT INTO meal_slot (id, name, sortOrder, startMinute, endMinute, createdAt, updatedAt, deletedAt) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, NULL)",
                arrayOf<Any?>(e.id, e.name, e.sortOrder, e.startMinute, e.endMinute, e.createdAt, e.updatedAt),
            )
        }
    }
}
