package dev.ownednotes.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Calendar boundaries follow the device zone, including daylight-saving transitions. */
enum class NotebookModifiedFilter(val label:String,val chip:String) {
    ALL("All dates","All dates"), WEEK("Modified this week","This week"),
    MONTH("Modified this month","This month"), OLDER("Older than this month","Older");
    fun includes(modified:Long,now:Long,zone:ZoneId=ZoneId.systemDefault()):Boolean {
        if(this==ALL)return true
        if(modified<=0 || modified>now)return false
        val today=Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val month=today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return when(this) {
            ALL -> true
            WEEK -> modified>=today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant().toEpochMilli()
            MONTH -> modified>=month
            OLDER -> modified<month
        }
    }
}
