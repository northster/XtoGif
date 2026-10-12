package io.github.northster.xsaver.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * Saves into Pictures/XSaver through MediaStore: one album in the gallery for photos, videos and GIFs, visible right
 * away, and no storage permission needed (the app owns the files it creates).
 *
 * Everything goes through a file in the cache first, which is checked (is it really a JPEG / MP4 / GIF?) before it
 * reaches the gallery, and the gallery copy is checked again (same size) and scanned so the gallery app reads its
 * length and thumbnail. A bad download then shows up as an error instead of a blank item in the gallery.
 */
object Gallery {
    const val ALBUM = "XSaver"
    private val relativePath = Environment.DIRECTORY_PICTURES + "/" + ALBUM

    private fun collection(mime: String): Uri =
        if (mime.startsWith("video/")) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** Copies [file] into the gallery as [displayName]. Saving the same file again makes another copy ("name (1)"). */
    suspend fun saveFile(context: Context, file: File, displayName: String, mime: String): Uri = withContext(Dispatchers.IO) {
        check(file, mime, null)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection(mime), values) ?: throw IOException("갤러리에 파일을 만들 수 없습니다")
        try {
            (resolver.openOutputStream(uri, "w") ?: throw IOException("갤러리 파일을 열 수 없습니다")).use { out ->
                file.inputStream().use { it.copyTo(out) }
                out.flush()
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val written = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
            if (written != file.length()) throw IOException("갤러리에 쓴 크기가 다릅니다 ($written / ${file.length()}바이트)")
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        scan(context, uri, mime)
        uri
    }

    /** Downloads [url] to the cache, checks it, then saves it to the gallery. */
    suspend fun download(context: Context, url: String, displayName: String, mime: String, onProgress: (Float) -> Unit): Uri {
        val tmp = File(context.cacheDir, "download/$displayName")
        try {
            val contentType = downloadToFile(url, tmp, onProgress)
            withContext(Dispatchers.IO) { check(tmp, mime, contentType) }
            return saveFile(context, tmp, displayName, mime)
        } finally {
            tmp.delete()
        }
    }

    /** Returns the server's Content-Type. */
    suspend fun downloadToFile(url: String, file: File, onProgress: (Float) -> Unit): String? {
        val job = coroutineContext[Job]
        return withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            try {
                file.outputStream().use { out -> fetch(url, out, job, onProgress) }
            } catch (e: Throwable) {
                file.delete()
                throw e
            }
        }
    }

    suspend fun copyToFile(context: Context, uri: Uri, file: File) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        (context.contentResolver.openInputStream(uri) ?: throw IOException("파일을 열 수 없습니다")).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
    }

    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** Makes sure the file is what its type says, from its first bytes. */
    private fun check(file: File, mime: String, contentType: String?) {
        val size = file.length()
        val head = ByteArray(16)
        val n = if (size > 0) file.inputStream().use { it.read(head) } else 0
        fun ascii(from: Int, len: Int) = String(head, from, len.coerceAtMost(n - from).coerceAtLeast(0), Charsets.ISO_8859_1)
        val ok = n >= 12 && when (mime) {
            "video/mp4" -> ascii(4, 4) in setOf("ftyp", "moov", "mdat", "free", "wide", "skip")
            "image/gif" -> ascii(0, 4) == "GIF8"
            "image/png" -> head[0] == 0x89.toByte() && ascii(1, 3) == "PNG"
            "image/webp" -> ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP"
            "image/jpeg" -> head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()
            else -> true
        }
        if (!ok) {
            val what = if (size == 0L) "빈 파일" else "${size}바이트, 시작: \"${ascii(0, 12).filter { it.code in 32..126 }}\""
            val type = contentType?.let { ", 서버 형식 $it" }.orEmpty()
            throw IOException("받은 파일이 ${mime.substringAfter('/').uppercase()}가 아닙니다 ($what$type)")
        }
    }

    /** Lets the gallery read the new item's length and thumbnail now rather than later. */
    private fun scan(context: Context, uri: Uri, mime: String) {
        val path = runCatching {
            @Suppress("DEPRECATION")
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: return
        MediaScannerConnection.scanFile(context.applicationContext, arrayOf(path), arrayOf(mime), null)
    }

    private fun fetch(url: String, out: OutputStream, job: Job?, onProgress: (Float) -> Unit): String? {
        Http.client.newCall(Http.get(url)).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("빈 응답")
            val total = body.contentLength()
            copy(body.byteStream(), out, total, job, onProgress)
            return r.header("Content-Type")
        }
    }

    private fun copy(input: InputStream, out: OutputStream, total: Long, job: Job?, onProgress: (Float) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var done = 0L
        var lastReport = 0L
        while (true) {
            if (job != null && !job.isActive) throw CancellationException("취소됨")
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            done += n
            if (total > 0 && done - lastReport > 256 * 1024) {
                lastReport = done
                onProgress(done.toFloat() / total)
            }
        }
        if (total > 0 && done != total) throw IOException("다운로드가 중간에 끊겼습니다 ($done / ${total}바이트)")
        onProgress(1f)
    }
}
