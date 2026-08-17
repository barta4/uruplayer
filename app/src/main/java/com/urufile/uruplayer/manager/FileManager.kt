package com.urufile.uruplayer.manager

import android.content.Context
import android.util.Base64
import android.util.Log
import com.urufile.uruplayer.data.db.AppDatabase
import com.urufile.uruplayer.data.model.MediaFile
import com.urufile.uruplayer.xmds.XmdsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.security.MessageDigest

class FileManager(private val context: Context) {

    private val tag = "FileManager"
    private val client = XmdsClient(context)
    private val dao = AppDatabase.getInstance(context).mediaFileDao()
    private val httpClient = OkHttpClient()

    companion object {
        private const val CHUNK_SIZE = 524288L   // 512 KB
        private const val MEDIA_DIR = "media"
        private const val DOWNLOAD_HTTP = "http"   // RequiredFiles download attribute value
        const val MIN_FREE_SPACE_BYTES = 150L * 1024L * 1024L // 150 MB buffer mínimo requerido
        const val MAX_RETRY_COUNT = 5
    }

    val mediaDir: File
        get() = File(context.filesDir, MEDIA_DIR).also { it.mkdirs() }

    // ── Parse RequiredFiles XML ──────────────────────────────────────────────

    /**
     * Parse RequiredFiles XML and return list of MediaFile needing download.
     * Example XML:
     * <files>
     *   <file type="media" id="123" md5="abc" size="12345" download="xmds" path="img.jpg"/>
     *   <file type="layout" id="1" md5="def" size="456" download="xmds" path="1.xlf"/>
     * </files>
     */
    suspend fun parseAndSyncRequiredFiles(requiredFilesXml: String): List<MediaFile> =
        withContext(Dispatchers.IO) {
            val required = mutableListOf<MediaFile>()
            try {
                val factory = XmlPullParserFactory.newInstance()
                val parser = factory.newPullParser()
                parser.setInput(StringReader(requiredFilesXml))

                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == "file") {
                        val type     = parser.getAttributeValue(null, "type") ?: continue
                        val id       = parser.getAttributeValue(null, "id")?.toIntOrNull() ?: continue
                        val md5      = parser.getAttributeValue(null, "md5") ?: ""
                        val size     = parser.getAttributeValue(null, "size")?.toLongOrNull() ?: 0L
                        val saveAs   = parser.getAttributeValue(null, "saveAs") ?: "$id"
                        val download = parser.getAttributeValue(null, "download") ?: "xmds"
                        // path attribute: for download=http it's a full URL; for xmds it may be empty
                        // ksoap2 double-encodes XML entities: &amp;amp; → unescape twice to get real &
                        val rawPath = parser.getAttributeValue(null, "path") ?: ""
                        val remotePath = rawPath
                            .replace("&amp;amp;", "&")
                            .replace("&amp;", "&")
                            .replace("&lt;", "<")
                            .replace("&gt;", ">")

                        // Save file locally using the saveAs name
                        val localPath = File(mediaDir, saveAs).absolutePath
                        val existing = dao.getById(id)
                        val downloaded = existing?.downloaded == true && existing.md5 == md5
                                && File(localPath).exists()
                        val retryCount = if (existing != null && existing.md5 == md5) existing.retryCount else 0
                        val lastAttempt = if (existing != null && existing.md5 == md5) existing.lastAttemptTimestamp else 0L

                        val mediaFile = MediaFile(
                            fileId    = id,
                            fileType  = type,
                            downloadMethod = download,
                            md5       = md5,
                            // path: for HTTP downloads store remote URL; for xmds store local path
                            path      = if (download == DOWNLOAD_HTTP) remotePath
                                        else File(mediaDir, saveAs).absolutePath,
                            downloaded = downloaded,
                            fileSize  = size,
                            saveAs    = saveAs,  // always track the intended local filename
                            retryCount = retryCount,
                            lastAttemptTimestamp = lastAttempt
                        )
                        required.add(mediaFile)
                        dao.insert(mediaFile)
                    }
                    event = parser.next()
                }

                // Remove orphaned files not in the new required list
                val activeIds = required.map { it.fileId }
                val orphaned = if (activeIds.isNotEmpty()) {
                    dao.getOrphaned(activeIds)
                } else {
                    dao.getAll()
                }

                // Delete physical files from disk
                orphaned.forEach { file ->
                    try {
                        val f = File(file.path)
                        if (f.exists()) {
                            f.delete()
                            Log.i(tag, "Deleted physical orphaned file: ${file.path}")
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to delete physical file: ${file.path}", e)
                    }
                }

                // Clean database
                if (activeIds.isNotEmpty()) {
                    dao.deleteOrphaned(activeIds)
                } else {
                    dao.deleteAll()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    throw e
                }
                Log.e(tag, "Error parsing RequiredFiles: ${e.message}", e)
            }
            required
        }

    // ── Download missing files ───────────────────────────────────────────────

    suspend fun downloadPendingFiles() = withContext(Dispatchers.IO) {
        val usableSpace = context.filesDir.usableSpace
        val usableMb = usableSpace / (1024 * 1024)

        // 1. Verificación global de espacio crítico (mínimo 150 MB libres)
        if (usableSpace < MIN_FREE_SPACE_BYTES) {
            val msg = "⚠️ Espacio en disco crítico ($usableMb MB disponibles, mínimo requerido 150 MB). Pausando descargas."
            Log.w(tag, msg)
            try {
                StatusReporter(context).submitLog(msg, "warning")
            } catch (e: Exception) {
                Log.e(tag, "Error enviando log al CMS: ${e.message}")
            }
            return@withContext
        }

        val pending = dao.getPending()
        Log.i(tag, "Verificando ${pending.size} archivos pendientes para descarga (Espacio libre: $usableMb MB)")
        val now = System.currentTimeMillis()

        for (mediaFile in pending) {
            // 2. Control anti-bucle: Backoff escalonado ante fallos repetitivos
            val backoffMs = when {
                mediaFile.retryCount >= MAX_RETRY_COUNT -> 60 * 60 * 1000L // 1 hora
                mediaFile.retryCount >= 3 -> 15 * 60 * 1000L // 15 min
                mediaFile.retryCount >= 1 -> 2 * 60 * 1000L  // 2 min
                else -> 0L
            }

            if (backoffMs > 0 && (now - mediaFile.lastAttemptTimestamp) < backoffMs) {
                val waitMin = ((backoffMs - (now - mediaFile.lastAttemptTimestamp)) / 1000 / 60) + 1
                Log.d(tag, "Saltando fileId=${mediaFile.fileId} por backoff de reintentos (fallos=${mediaFile.retryCount}, reintento en ~${waitMin}m)")
                continue
            }

            // 3. Comprobar que haya espacio suficiente para este archivo específico + 150 MB de margen de seguridad
            val currentFree = context.filesDir.usableSpace
            val requiredSpace = mediaFile.fileSize + MIN_FREE_SPACE_BYTES
            if (mediaFile.fileSize > 0 && currentFree < requiredSpace) {
                val fileSizeMb = mediaFile.fileSize / (1024 * 1024)
                val msg = "Espacio insuficiente para fileId=${mediaFile.fileId} (Requiere $fileSizeMb MB + 150 MB buffer. Libre=${currentFree / (1024 * 1024)} MB). Postergando."
                Log.w(tag, msg)
                dao.incrementRetry(mediaFile.fileId, now)
                continue
            }

            try {
                downloadFile(mediaFile)
            } catch (e: org.ksoap2.SoapFault) {
                if (e.faultstring?.contains("Requested an invalid file", ignoreCase = true) == true) {
                    Log.w(tag, "File id=${mediaFile.fileId} is missing or invalid on CMS (SoapFault). Creating placeholder.")
                    try {
                        val outputFile = File(mediaFile.path)
                        outputFile.parentFile?.mkdirs()
                        outputFile.createNewFile()
                        dao.markDownloaded(mediaFile.fileId)
                    } catch (ioe: Exception) {
                        Log.e(tag, "Failed to create placeholder for invalid file: ${ioe.message}")
                        dao.incrementRetry(mediaFile.fileId, now)
                    }
                } else {
                    Log.e(tag, "SOAP Fault during download for fileId=${mediaFile.fileId}: ${e.message}", e)
                    dao.incrementRetry(mediaFile.fileId, now)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    throw e
                }
                Log.e(tag, "Failed to download fileId=${mediaFile.fileId}: ${e.message}", e)
                dao.incrementRetry(mediaFile.fileId, now)
            }
        }
    }

    /**
     * Downloads a file in chunks, writing each chunk directly to disk
     * to avoid OutOfMemoryError on large files (videos).
     * MD5 is computed incrementally without holding the whole file in RAM.
     */
    private suspend fun downloadFile(mediaFile: MediaFile) {
        if (mediaFile.downloadMethod == DOWNLOAD_HTTP) {
            // ── HTTP direct download ──────────────────────────────────────────
            downloadFileHttp(mediaFile)
        } else {
            // ── XMDS GetFile SOAP download ────────────────────────────────────
            downloadFileSoap(mediaFile)
        }
    }

    /** Download via plain HTTP GET (when RequiredFiles says download="http") */
    private suspend fun downloadFileHttp(mediaFile: MediaFile) {
        // Use the saveAs field from RequiredFiles XML as the local filename
        val localName = mediaFile.saveAs.ifBlank { "${mediaFile.fileId}" }
        val outputFile = File(mediaDir, localName)

        Log.i(tag, "HTTP download fileId=${mediaFile.fileId} url=${mediaFile.path} -> ${outputFile.name}")

        val request = Request.Builder().url(mediaFile.path).build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(tag, "HTTP download failed for fileId=${mediaFile.fileId}: ${response.code}")
                    if (response.code == 404) {
                        Log.w(tag, "File id=${mediaFile.fileId} returned 404. Creating placeholder.")
                        try {
                            outputFile.parentFile?.mkdirs()
                            outputFile.createNewFile()
                            dao.markDownloaded(mediaFile.fileId)
                        } catch (ioe: Exception) {
                            Log.e(tag, "Failed to create placeholder for 404 file: ${ioe.message}")
                            dao.incrementRetry(mediaFile.fileId, System.currentTimeMillis())
                        }
                    } else {
                        dao.incrementRetry(mediaFile.fileId, System.currentTimeMillis())
                    }
                    return
                }

                val tempFile = File(mediaDir, "$localName.tmp")
                tempFile.parentFile?.mkdirs()

                try {
                    val digest = MessageDigest.getInstance("MD5")
                    var totalBytes = 0L
                    FileOutputStream(tempFile).use { fos ->
                        response.body?.byteStream()?.use { input ->
                            val buf = ByteArray(65536)
                            var read: Int
                            while (input.read(buf).also { read = it } != -1) {
                                fos.write(buf, 0, read)
                                digest.update(buf, 0, read)
                                totalBytes += read
                            }
                        }
                    }

                    if (totalBytes == 0L) {
                        Log.w(tag, "Empty HTTP response for fileId=${mediaFile.fileId}")
                        tempFile.delete()
                        dao.incrementRetry(mediaFile.fileId, System.currentTimeMillis())
                        return
                    }

                    val downloadedMd5 = digest.digest().joinToString("") { "%02x".format(it) }
                    if (mediaFile.md5.isNotBlank() && !downloadedMd5.equals(mediaFile.md5, ignoreCase = true)) {
                        Log.e(tag, "MD5 mismatch for fileId=${mediaFile.fileId}. Expected=${mediaFile.md5} Got=$downloadedMd5")
                        tempFile.delete()
                        dao.incrementRetry(mediaFile.fileId, System.currentTimeMillis())
                        if (mediaFile.retryCount + 1 >= MAX_RETRY_COUNT) {
                            StatusReporter(context).submitLog("File id=${mediaFile.fileId} falló validación MD5 $MAX_RETRY_COUNT veces consecutivas. Pausando descargas.", "error")
                        }
                        return
                    }

                    if (tempFile.renameTo(outputFile)) {
                        dao.markDownloaded(mediaFile.fileId)
                        Log.i(tag, "HTTP downloaded fileId=${mediaFile.fileId} ($totalBytes bytes) -> ${outputFile.name}")
                    } else {
                        Log.e(tag, "Failed to rename temp file $tempFile to $outputFile")
                        tempFile.delete()
                        dao.incrementRetry(mediaFile.fileId, System.currentTimeMillis())
                    }
                } catch (e: Exception) {
                    tempFile.delete()
                    throw e
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                throw e
            }
            throw e
        }
    }

    /** Download via XMDS GetFile SOAP (when RequiredFiles says download="xmds") */
    private suspend fun downloadFileSoap(mediaFile: MediaFile) {
        val outputFile = File(mediaFile.path)
        outputFile.parentFile?.mkdirs()
        
        val tempFile = File(mediaFile.path + ".tmp")
        tempFile.parentFile?.mkdirs()

        Log.i(tag, "SOAP download fileId=${mediaFile.fileId} to temp file -> ${tempFile.name}")

        try {
            val digest = MessageDigest.getInstance("MD5")
            var offset = 0L
            var totalBytes = 0L

            FileOutputStream(tempFile).use { fos ->
                while (true) {
                    val chunkB64 = client.getFile(
                        fileId = mediaFile.fileId,
                        fileType = mediaFile.fileType,
                        chunkOffset = offset,
                        chunkSize = CHUNK_SIZE
                    )
                    if (chunkB64.isBlank()) break

                    val bytes = Base64.decode(chunkB64, Base64.DEFAULT)
                    fos.write(bytes)
                    digest.update(bytes)
                    offset += bytes.size
                    totalBytes += bytes.size

                    if (bytes.size < CHUNK_SIZE) break
                }
            }

            if (totalBytes == 0L) {
                Log.w(tag, "Empty SOAP response for fileId=${mediaFile.fileId}")
                tempFile.delete()
                dao.incrementRetry(mediaFile.fileId, System.currentTimeMillis())
                return
            }

            val downloadedMd5 = digest.digest().joinToString("") { "%02x".format(it) }
            if (mediaFile.md5.isNotBlank() && !downloadedMd5.equals(mediaFile.md5, ignoreCase = true)) {
                Log.e(tag, "MD5 mismatch for fileId=${mediaFile.fileId}. Expected=${mediaFile.md5} Got=$downloadedMd5")
                tempFile.delete()
                dao.incrementRetry(mediaFile.fileId, System.currentTimeMillis())
                if (mediaFile.retryCount + 1 >= MAX_RETRY_COUNT) {
                    StatusReporter(context).submitLog("File id=${mediaFile.fileId} falló validación MD5 $MAX_RETRY_COUNT veces consecutivas. Pausando descargas.", "error")
                }
                return
            }

            if (tempFile.renameTo(outputFile)) {
                dao.markDownloaded(mediaFile.fileId)
                Log.i(tag, "SOAP downloaded fileId=${mediaFile.fileId} ($totalBytes bytes) -> ${outputFile.name}")
            } else {
                Log.e(tag, "Failed to rename temp file $tempFile to $outputFile")
                tempFile.delete()
                dao.incrementRetry(mediaFile.fileId, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            tempFile.delete()
            if (e is kotlinx.coroutines.CancellationException) {
                throw e
            }
            throw e
        }
    }

    // ── Media Inventory XML ──────────────────────────────────────────────────

    suspend fun buildMediaInventoryXml(): String {
        val files = dao.getAll()
        val sb = StringBuilder("<files>")
        files.forEach { f ->
            sb.append(
                "<file type=\"${f.fileType}\" id=\"${f.fileId}\" complete=\"${if (f.downloaded) 1 else 0}\" " +
                        "md5=\"${f.md5}\" lastChecked=\"${f.lastUpdated}\"/>"
            )
        }
        sb.append("</files>")
        return sb.toString()
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    /**
     * Resolve a filename against the media directory.
     * If the path is already absolute, return it directly.
     */
    fun getLocalFile(filename: String): File {
        val f = File(filename)
        return if (f.isAbsolute) f else File(mediaDir, filename)
    }
}
