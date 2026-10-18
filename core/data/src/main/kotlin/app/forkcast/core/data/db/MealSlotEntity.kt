package app.forkcast.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.forkcast.core.model.MealSlot
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "meal_slot")
data class MealSlotEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortOrder: Int,
    /** Minutes after local midnight; null = no time window. */
    val startMinute: Int?,
    val endMinute: Int?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

fun MealSlotEntity.toModel() = MealSlot(
    id = id,
    name = name,
    sortOrder = sortOrder,
    startMinute = startMinute,
    endMinute = endMinute,
)
