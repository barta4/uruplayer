package com.urufile.uruplayer.data.model

/**
 * Represents a parsed Uruplayer Layout (XLF).
 */
data class Layout(
    val layoutId: Int,
    val width: Int,
    val height: Int,
    val bgColor: String,
    val bgImage: String? = null,
    val regions: List<Region>
)

data class Region(
    val regionId: String,
    val width: Int,
    val height: Int,
    val top: Int,
    val left: Int,
    val zIndex: Int = 0,
    val mediaItems: List<MediaItem>
)

data class MediaItem(
    val mediaId: String,
    val type: String,       // image | video | webpage | text | ticker | clock
    val duration: Int,      // seconds; 0 = use media natural duration
    val uri: String? = null,
    val rawHtml: String? = null,
    val options: Map<String, String> = emptyMap()
)
