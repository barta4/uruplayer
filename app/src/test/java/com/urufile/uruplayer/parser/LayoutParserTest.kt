package com.urufile.uruplayer.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LayoutParserTest {

    private val parser = LayoutParser()

    @Test
    fun `test parse valid xlf layout xml`() {
        val xml = """
            <layout width="1920" height="1080" bgcolor="#1A1A2E">
                <region id="r1" width="1920" height="1080" top="0" left="0" zindex="1">
                    <media id="m1" type="video" duration="30">
                        <options>
                            <uri>video_promo.mp4</uri>
                        </options>
                    </media>
                </region>
                <region id="r2" width="400" height="100" top="980" left="0" zindex="2">
                    <media id="m2" type="text" duration="10">
                        <options>
                            <text>Bienvenido a UruPlayer</text>
                        </options>
                    </media>
                </region>
            </layout>
        """.trimIndent()

        val layout = parser.parse(xml, 101)
        assertNotNull(layout)
        assertEquals(101, layout?.layoutId)
        assertEquals(1920, layout?.width)
        assertEquals(1080, layout?.height)
        assertEquals("#1A1A2E", layout?.bgColor)
        assertEquals(2, layout?.regions?.size)

        val r1 = layout?.regions?.find { it.regionId == "r1" }
        assertNotNull(r1)
        assertEquals(1, r1?.zIndex)
        assertEquals(1, r1?.mediaItems?.size)
        assertEquals("video", r1?.mediaItems?.first()?.type)
        assertEquals("video_promo.mp4", r1?.mediaItems?.first()?.uri)
        assertEquals(30, r1?.mediaItems?.first()?.duration)

        val r2 = layout?.regions?.find { it.regionId == "r2" }
        assertNotNull(r2)
        assertEquals(2, r2?.zIndex)
        assertEquals("text", r2?.mediaItems?.first()?.type)
    }

    @Test
    fun `test parse empty string returns null`() {
        val layout = parser.parse("", 1)
        assertNull(layout)
    }
}
