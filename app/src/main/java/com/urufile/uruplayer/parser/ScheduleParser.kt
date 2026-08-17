package com.urufile.uruplayer.parser

import android.util.Log
import com.urufile.uruplayer.data.model.ScheduleItem
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScheduleParser {

    private val tag = "ScheduleParser"

    /**
     * Parse CMS Schedule XML into a list of ScheduleItems.
     * Example XML:
     * <schedule>
     *   <default file="3"/>
     *   <layout file="5" fromdt="2025-01-01 00:00:00" todt="2025-12-31 23:59:59"
     *           scheduleid="10" priority="1"/>
     * </schedule>
     */
    fun parse(xml: String): List<ScheduleItem> {
        val items = mutableListOf<ScheduleItem>()
        if (xml.isBlank()) return items

        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name.lowercase()) {
                        "default" -> {
                            val layoutId = parser.getAttributeValue(null, "file")?.toIntOrNull() ?: -1
                            if (layoutId != -1) {
                                items.add(
                                    ScheduleItem(
                                        scheduleId = "default",
                                        layoutId = layoutId,
                                        fromDt = null,
                                        toDt = null,
                                        priority = -1,
                                        isDefault = true
                                    )
                                )
                            }
                        }

                        "layout" -> {
                            val layoutId = parser.getAttributeValue(null, "file")?.toIntOrNull() ?: -1
                            val scheduleId = parser.getAttributeValue(null, "scheduleid") ?: ""
                            val fromDtStr = parser.getAttributeValue(null, "fromdt")
                            val toDtStr = parser.getAttributeValue(null, "todt")
                            val priority = parser.getAttributeValue(null, "priority")?.toIntOrNull() ?: 0

                            if (layoutId != -1) {
                                items.add(
                                    ScheduleItem(
                                        scheduleId = scheduleId,
                                        layoutId = layoutId,
                                        fromDt = fromDtStr?.let { parseDt(it) },
                                        toDt = toDtStr?.let { parseDt(it) },
                                        priority = priority,
                                        isDefault = false
                                    )
                                )
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            Log.e(tag, "Error parsing schedule XML: ${e.message}", e)
        }
        return items
    }

    /**
     * Determine which layout should be playing right now.
     * Returns the layout ID with highest priority that is currently active,
     * or the default layout if none match.
     */
    fun getCurrentLayoutId(items: List<ScheduleItem>): Int {
        val now = Date()
        val defaultItem = items.firstOrNull { it.isDefault }

        val activeItems = items
            .filter { !it.isDefault }
            .filter { item ->
                val from = item.fromDt
                val to = item.toDt
                when {
                    from == null && to == null -> true
                    from != null && to != null -> now.after(from) && now.before(to)
                    from != null -> now.after(from)
                    to != null -> now.before(to)
                    else -> false
                }
            }
            .sortedByDescending { it.priority }

        return activeItems.firstOrNull()?.layoutId
            ?: defaultItem?.layoutId
            ?: -1
    }

    private fun parseDt(str: String): Date? = try {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(str)
    } catch (e: Exception) {
        Log.w(tag, "Cannot parse date: $str")
        null
    }
}
