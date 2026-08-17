package com.urufile.uruplayer.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_files")
data class MediaFile(
    @PrimaryKey val fileId: Int,
    val fileType: String,       // actual Xibo type: "media" | "layout" | "resource" | "widget" etc.
    val downloadMethod: String, // "http" | "xmds"
    val md5: String,
    val path: String,           // remote URL (http) or local absolute path (xmds)
    val downloaded: Boolean,
    val fileSize: Long = 0L,
    val saveAs: String = "",    // intended local filename (e.g. "17.otf", "1.xlf")
    val retryCount: Int = 0,    // number of consecutive failed download attempts
    val lastAttemptTimestamp: Long = 0L, // timestamp of last download attempt
    val lastUpdated: Long = System.currentTimeMillis()
)

