package com.urufile.uruplayer.data.model

import java.util.Date

/**
 * Represents a single schedule entry from the CMS Schedule XML.
 */
data class ScheduleItem(
    val scheduleId: String,
    val layoutId: Int,
    val fromDt: Date?,
    val toDt: Date?,
    val priority: Int = 0,
    val isDefault: Boolean = false
)
