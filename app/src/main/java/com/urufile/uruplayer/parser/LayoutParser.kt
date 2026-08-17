package com.urufile.uruplayer.parser

import android.util.Log
import com.urufile.uruplayer.data.model.Layout
import com.urufile.uruplayer.data.model.MediaItem
import com.urufile.uruplayer.data.model.Region
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader

class LayoutParser {

    private val tag = "LayoutParser"

    /**
     * Parse an Uruplayer XLF layout file from disk.
     */
    fun parseFile(xlfFile: File): Layout? {
        return if (xlfFile.exists()) parse(xlfFile.readText(), xlfFile.nameWithoutExtension.toIntOrNull() ?: -1)
        else null
    }

    /**
     * Parse an Uruplayer XLF XML string.
     * Expected format:
     * <layout width="1920" height="1080" bgcolor="#000000">
     *   <region id="r1" width="960" height="1080" top="0" left="0">
     *     <media id="m1" type="image" duration="10">
     *       <options>
     *         <uri>filename.jpg</uri>
     *       </options>
     *     </media>
     *   </region>
     * </layout>
     */
    fun parse(xml: String, layoutId: Int): Layout? {
        if (xml.isBlank()) return null
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var layoutWidth = 1920
            var layoutHeight = 1080
            var bgColor = "#000000"
            var bgImage: String? = null
            val regions = mutableListOf<Region>()

            // Current region state
            var currentRegionId = ""
            var currentRegionWidth = 0
            var currentRegionHeight = 0
            var currentRegionTop = 0
            var currentRegionLeft = 0
            var currentRegionZ = 0
            val currentMediaItems = mutableListOf<MediaItem>()

            // Current media state
            var currentMediaId = ""
            var currentMediaType = ""
            var currentMediaDuration = 10
            val currentOptions = mutableMapOf<String, String>()
            var lastTag = ""
            var inRaw = false      // inside <raw> node (Xibo embeds HTML here)
            var inOptions = false  // inside <options> node

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        lastTag = parser.name
                        when (parser.name.lowercase()) {
                            "layout" -> {
                                layoutWidth = parser.getAttributeValue(null, "width")?.toIntOrNull() ?: 1920
                                layoutHeight = parser.getAttributeValue(null, "height")?.toIntOrNull() ?: 1080
                                bgColor = parser.getAttributeValue(null, "bgcolor") ?: "#000000"
                                bgImage = parser.getAttributeValue(null, "background")
                            }

                            "region" -> {
                                currentRegionId = parser.getAttributeValue(null, "id") ?: ""
                                currentRegionWidth = parser.getAttributeValue(null, "width")?.toIntOrNull() ?: layoutWidth
                                currentRegionHeight = parser.getAttributeValue(null, "height")?.toIntOrNull() ?: layoutHeight
                                currentRegionTop = parser.getAttributeValue(null, "top")?.toIntOrNull() ?: 0
                                currentRegionLeft = parser.getAttributeValue(null, "left")?.toIntOrNull() ?: 0
                                currentRegionZ = parser.getAttributeValue(null, "zindex")?.toIntOrNull() ?: 0
                                currentMediaItems.clear()
                            }

                            "media", "widget" -> {
                                currentMediaId = parser.getAttributeValue(null, "id") ?: ""
                                currentMediaType = parser.getAttributeValue(null, "type") ?: "image"
                                currentMediaDuration = parser.getAttributeValue(null, "duration")?.toIntOrNull() ?: 10
                                currentOptions.clear()
                                inRaw = false
                                inOptions = false
                            }

                            "raw" -> inRaw = true
                            "options" -> inOptions = true
                        }
                    }

                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim() ?: ""
                        if (text.isNotBlank() && lastTag.isNotBlank()) {
                            // Collect text from both <options> and <raw> children
                            currentOptions[lastTag] = (currentOptions[lastTag] ?: "") + text
                        }
                    }

                    XmlPullParser.CDSECT -> {
                        // CDATA sections (used by Xibo for HTML content in <embedHtml>)
                        val cdataText = parser.text ?: ""
                        if (cdataText.isNotBlank() && lastTag.isNotBlank()) {
                            currentOptions[lastTag] = cdataText
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        when (parser.name.lowercase()) {
                            "raw" -> inRaw = false
                            "options" -> inOptions = false

                            "media", "widget" -> {
                                val uri = currentOptions["uri"]
                                // Xibo embeds HTML in <raw><embedHtml><![CDATA[...]]></embedHtml></raw>
                                val rawHtml = currentOptions["embedHtml"]
                                    ?: currentOptions["rawHtml"]
                                    ?: currentOptions["html"]

                                // Map Xibo media types to our renderer types:
                                // "embedded" -> HTML WebView renderer
                                // "webpage"  -> URL WebView renderer
                                val rendererType = when (currentMediaType.lowercase()) {
                                    "embedded", "text", "ticker", "clock", "datasetview" -> "html"
                                    "webpage" -> "webpage"
                                    "video" -> "video"
                                    "image" -> "image"
                                    else -> currentMediaType
                                }

                                currentMediaItems.add(
                                    MediaItem(
                                        mediaId = currentMediaId,
                                        type = rendererType,
                                        duration = currentMediaDuration,
                                        uri = uri,
                                        rawHtml = rawHtml,
                                        options = currentOptions.toMap()
                                    )
                                )
                            }

                            "region" -> {
                                if (currentRegionId.isNotBlank()) {
                                    regions.add(
                                        Region(
                                            regionId = currentRegionId,
                                            width = currentRegionWidth,
                                            height = currentRegionHeight,
                                            top = currentRegionTop,
                                            left = currentRegionLeft,
                                            zIndex = currentRegionZ,
                                            mediaItems = currentMediaItems.toList()
                                        )
                                    )
                                }
                            }
                        }
                        lastTag = ""
                    }
                }
                event = parser.next()
            }

            Layout(
                layoutId = layoutId,
                width = layoutWidth,
                height = layoutHeight,
                bgColor = bgColor,
                bgImage = bgImage,
                regions = regions
            )
        } catch (e: Exception) {
            Log.e(tag, "Error parsing XLF: ${e.message}", e)
            null
        }
    }
}
