package dev.ownednotes.app

import android.text.format.DateUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * P598: times said in English, like the rest of the app. Android's own wording follows the phone's language, so on a
 * German phone a Library tile read "Edited gestern" and a backup "Last backup: vor 3 Stunden". On an English phone
 * Android's wording is used exactly as before; in any other language the same wording is built here in English.
 */
internal object EnglishTime {
    private fun english() = Locale.getDefault().language == "en"

    /** "5 minutes ago", "3 hours ago", "Yesterday", "4 days ago", then a date ("Nov 3", or "Nov 3, 2025" in another year). */
    fun ago(then: Long, now: Long = System.currentTimeMillis()): String {
        if (english()) return DateUtils.getRelativeTimeSpanString(then, now, DateUtils.MINUTE_IN_MILLIS).toString()
        val past = then <= now
        val span = abs(now - then)
        fun said(count: Long, unit: String) = (if (count == 1L) "1 $unit" else "$count ${unit}s").let { if (past) "$it ago" else "in $it" }
        if (span < DateUtils.HOUR_IN_MILLIS) return said(span / DateUtils.MINUTE_IN_MILLIS, "minute")
        if (span < DateUtils.DAY_IN_MILLIS) return said(span / DateUtils.HOUR_IN_MILLIS, "hour")
        val zone = ZoneId.systemDefault()
        val thenDay = Instant.ofEpochMilli(then).atZone(zone).toLocalDate()
        val days = abs(ChronoUnit.DAYS.between(thenDay, Instant.ofEpochMilli(now).atZone(zone).toLocalDate()))
        if (days < 7) return when {
            days <= 1L && past -> "Yesterday"
            days <= 1L -> "Tomorrow"
            else -> said(days, "day")
        }
        val sameYear = thenDay.year == Instant.ofEpochMilli(now).atZone(zone).year
        return thenDay.format(DateTimeFormatter.ofPattern(if (sameYear) "MMM d" else "MMM d, yyyy", Locale.ENGLISH))
    }

    /** A date and time, such as a saved version's: "Nov 3, 2026, 14:05" (or 2:05 PM where the phone uses a 12-hour clock). */
    fun dateTime(context: android.content.Context, time: Long): String {
        if (english()) return DateUtils.formatDateTime(context, time,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
        val clock = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        return Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d, yyyy, $clock", Locale.ENGLISH))
    }

    /** A short date and time with no context to hand: the phone's own short format in English, otherwise "2026-11-03 14:05". */
    fun short(time: Long): String =
        if (english()) java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(time))
        else Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT))
}
