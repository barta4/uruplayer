package com.urufile.uruplayer.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScheduleParserTest {

    private val parser = ScheduleParser()

    @Test
    fun `test parse valid schedule xml with default and timed layout`() {
        val xml = """
            <schedule>
                <default file="1"/>
                <layout file="10" scheduleid="sched_1" priority="5" fromdt="2025-01-01 00:00:00" todt="2035-12-31 23:59:59"/>
                <layout file="20" scheduleid="sched_2" priority="1" fromdt="2020-01-01 00:00:00" todt="2020-01-02 00:00:00"/>
            </schedule>
        """.trimIndent()

        val items = parser.parse(xml)
        assertEquals(3, items.size)

        val defaultItem = items.find { it.isDefault }
        assertNotNull(defaultItem)
        assertEquals(1, defaultItem?.layoutId)

        val highPriorityItem = items.find { it.scheduleId == "sched_1" }
        assertNotNull(highPriorityItem)
        assertEquals(10, highPriorityItem?.layoutId)
        assertEquals(5, highPriorityItem?.priority)
    }

    @Test
    fun `test getCurrentLayoutId selects active high priority layout`() {
        val xml = """
            <schedule>
                <default file="1"/>
                <layout file="10" scheduleid="sched_active" priority="10" fromdt="2020-01-01 00:00:00" todt="2035-12-31 23:59:59"/>
                <layout file="5" scheduleid="sched_low" priority="2" fromdt="2020-01-01 00:00:00" todt="2035-12-31 23:59:59"/>
            </schedule>
        """.trimIndent()

        val items = parser.parse(xml)
        val currentLayoutId = parser.getCurrentLayoutId(items)
        assertEquals(10, currentLayoutId)
    }

    @Test
    fun `test getCurrentLayoutId falls back to default when schedule is expired`() {
        val xml = """
            <schedule>
                <default file="99"/>
                <layout file="10" scheduleid="sched_expired" priority="10" fromdt="2010-01-01 00:00:00" todt="2011-01-01 00:00:00"/>
            </schedule>
        """.trimIndent()

        val items = parser.parse(xml)
        val currentLayoutId = parser.getCurrentLayoutId(items)
        assertEquals(99, currentLayoutId)
    }

    @Test
    fun `test parse empty or invalid xml returns empty list`() {
        val items = parser.parse("")
        assertTrue(items.isEmpty())

        val invalidItems = parser.parse("<invalid></invalid>")
        assertTrue(invalidItems.isEmpty())
    }
}
