package app.forkcast

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

/**
 * The app's own language (roadmap 2.0, task 1.6), separate from the phone's. Android 13+ keeps it
 * (LocaleManager, the same setting as Settings › Apps › Forkcast › Language). On Android 10–12
 * Forkcast saves it and applies it to the application and every activity in attachBaseContext,
 * so screens, notifications and widgets all use it. Null means "follow the device".
 */
object AppLanguage {

    /** The languages Forkcast ships, as language tags, from `res/xml/locales_config.xml`. */
    fun available(context: Context): List<String> {
        val tags = mutableListOf<String>()
        context.resources.getXml(R.xml.locales_config).use { xml ->
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG && xml.name == "locale") {
                    xml.getAttributeValue(ANDROID_NS, "name")?.let(tags::add)
                }
            }
        }
        return tags
    }

    /** The chosen language tag, or null to follow the device. */
    fun current(context: Context): String? =
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()
        } else {
            prefs(context).getString(KEY, null)
        }

    /** Sets the language ([tag] null = the device's). On Android 10–12 the caller recreates the activity. */
    fun set(context: Context, tag: String?) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            return
        }
        prefs(context).edit().apply { if (tag == null) remove(KEY) else putString(KEY, tag) }.commit()
        // The application context (notifications, widgets, workers) follows without a restart.
        val app = context.applicationContext
        val locales = tag?.let { LocaleList.forLanguageTags(it) } ?: Resources.systemLocales()
        Locale.setDefault(locales[0])
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(Configuration(app.resources.configuration).apply { setLocales(locales) }, app.resources.displayMetrics)
    }

    /** For attachBaseContext on Android 10–12: [base] with the saved language, if any. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = prefs(base).getString(KEY, null) ?: return base
        val locales = LocaleList.forLanguageTags(tag)
        Locale.setDefault(locales[0])
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocales(locales) })
    }

    /** "Português (Brasil)" for "pt-BR": each language named in itself. */
    fun displayName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
    }

    private object Resources {
        fun systemLocales(): LocaleList = android.content.res.Resources.getSystem().configuration.locales
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val FILE = "app_language"
    private const val KEY = "tag"
}
