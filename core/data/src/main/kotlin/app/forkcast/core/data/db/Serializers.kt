package app.forkcast.core.data.db

import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.Nutrients
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDate

/** ISO yyyy-MM-dd, as in the export schema. */
object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

/** Nutrient maps keyed by enum name, like the database column; unknown names are skipped. */
object NutrientsSerializer : KSerializer<Nutrients> {
    private val delegate = MapSerializer(String.serializer(), Double.serializer())
    override val descriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: Nutrients) =
        encoder.encodeSerializableValue(delegate, value.entries.sortedBy { it.key.ordinal }.associate { it.key.name to it.value })
    override fun deserialize(decoder: Decoder): Nutrients =
        decoder.decodeSerializableValue(delegate).mapNotNull { (k, v) ->
            runCatching { NutrientKey.valueOf(k) }.getOrNull()?.let { it to v }
        }.toMap()
}
