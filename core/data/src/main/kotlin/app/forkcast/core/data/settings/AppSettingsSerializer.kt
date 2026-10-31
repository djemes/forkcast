package app.forkcast.core.data.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import app.forkcast.core.model.AppSettings
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.InputStream
import java.io.OutputStream

/**
 * Typed DataStore serializer (JSON instead of Proto; see docs/DECISIONS.md).
 * Unknown keys are ignored so a newer settings file still opens after a downgrade,
 * and missing keys fall back to defaults so older files open after an upgrade.
 */
object AppSettingsSerializer : Serializer<AppSettings> {

    /** The DataStore file, under the app's `datastore/` folder. */
    const val FILE_NAME = "settings.json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    override val defaultValue: AppSettings = AppSettings()

    override suspend fun readFrom(input: InputStream): AppSettings {
        val text = input.readBytes().decodeToString()
        if (text.isBlank()) return defaultValue
        return try {
            decode(text)
        } catch (e: SerializationException) {
            throw CorruptionException("Settings file is not valid JSON", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("Settings file has invalid values", e)
        }
    }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        output.write(encode(t).encodeToByteArray())
    }

    /** Throws [SerializationException] or [IllegalArgumentException] for a file that isn't settings. */
    internal fun decode(text: String): AppSettings =
        json.decodeFromJsonElement(AppSettings.serializer(), LegacySettings.upgrade(json.parseToJsonElement(text).jsonObject))

    internal fun encode(settings: AppSettings): String = json.encodeToString(AppSettings.serializer(), settings)
}
