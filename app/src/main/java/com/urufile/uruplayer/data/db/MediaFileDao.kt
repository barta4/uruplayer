package com.urufile.uruplayer.data.db

import androidx.room.*
import com.urufile.uruplayer.data.model.MediaFile

@Dao
interface MediaFileDao {

    @Query("SELECT * FROM media_files")
    suspend fun getAll(): List<MediaFile>

    @Query("SELECT * FROM media_files WHERE fileId = :id LIMIT 1")
    suspend fun getById(id: Int): MediaFile?

    @Query("SELECT * FROM media_files WHERE downloaded = 1")
    suspend fun getDownloaded(): List<MediaFile>

    @Query("SELECT * FROM media_files WHERE downloaded = 0")
    suspend fun getPending(): List<MediaFile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: MediaFile)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(files: List<MediaFile>)

    @Update
    suspend fun update(file: MediaFile)

    @Query("UPDATE media_files SET downloaded = 1, retryCount = 0 WHERE fileId = :id")
    suspend fun markDownloaded(id: Int)

    @Query("UPDATE media_files SET retryCount = retryCount + 1, lastAttemptTimestamp = :timestamp WHERE fileId = :id")
    suspend fun incrementRetry(id: Int, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE media_files SET retryCount = 0 WHERE fileId = :id")
    suspend fun resetRetries(id: Int)

    @Delete
    suspend fun delete(file: MediaFile)

    @Query("SELECT * FROM media_files WHERE fileId NOT IN (:activeIds)")
    suspend fun getOrphaned(activeIds: List<Int>): List<MediaFile>

    @Query("DELETE FROM media_files WHERE fileId NOT IN (:activeIds)")
    suspend fun deleteOrphaned(activeIds: List<Int>)

    @Query("DELETE FROM media_files")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM media_files WHERE downloaded = 0")
    suspend fun pendingCount(): Int
}
