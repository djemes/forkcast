package app.forkcast.ui.format

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Times as the phone shows them (owner, 2026-10-29): 12- or 24-hour from the phone's clock setting,
 * not from the language alone, which is what `ofLocalizedTime` follows. Words and order come from
 * the app's language ("19:05", "7:05 PM", "19h05" where a language writes it so).
 */
object ClockFormat {
    fun formatter(locale: Locale, is24: Boolean, seconds: Boolean = false, date: Boolean = false): DateTimeFormatter {
        val skeleton = (if (date) "yMd" else "") + (if (is24) "Hm" else "hm") + (if (seconds) "s" else "")
        // java.time reads the platform's patterns; a symbol it doesn't know falls back to the language's style.
        return runCatching { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale) }.getOrElse {
            val style = if (seconds) FormatStyle.MEDIUM else FormatStyle.SHORT
            (if (date) DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, style) else DateTimeFormatter.ofLocalizedTime(style)).withLocale(locale)
        }
    }

    fun formatter(context: Context, seconds: Boolean = false, date: Boolean = false): DateTimeFormatter =
        formatter(context.resources.configuration.locales[0], DateFormat.is24HourFormat(context), seconds, date)
}

/** Read on every composition, so a change of the phone's setting shows the next time a screen draws. */
@Composable
fun clockFormatter(seconds: Boolean = false, date: Boolean = false): DateTimeFormatter =
    ClockFormat.formatter(LocalContext.current, seconds, date)

/** [minute] after midnight. */
fun DateTimeFormatter.formatMinute(minute: Int): String = format(LocalTime.of(minute / 60 % 24, minute % 60))

fun DateTimeFormatter.formatEpoch(epochMs: Long): String = format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
