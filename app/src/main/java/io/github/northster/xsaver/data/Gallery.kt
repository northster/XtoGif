package io.github.northster.xsaver.data

import android.content.ContentValues
import android.content.Context
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
 */
object Gallery {
    const val ALBUM = "XSaver"
    private val relativePath = Environment.DIRECTORY_PICTURES + "/" + ALBUM

    private fun collection(mime: String): Uri =
        if (mime.startsWith("video/")) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private suspend fun save(context: Context, displayName: String, mime: String, write: (OutputStream) -> Unit): Uri =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection(mime), values) ?: throw IOException("갤러리에 파일을 만들 수 없습니다")
            try {
                (resolver.openOutputStream(uri) ?: throw IOException("갤러리 파일을 열 수 없습니다")).use(write)
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                uri
            } catch (e: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        }

    /** Streams [url] straight into the gallery. */
    suspend fun download(context: Context, url: String, displayName: String, mime: String, onProgress: (Float) -> Unit): Uri {
        val job = coroutineContext[Job]
        return save(context, displayName, mime) { out -> fetch(url, out, job, onProgress) }
    }

    suspend fun saveFile(context: Context, file: File, displayName: String, mime: String): Uri =
        save(context, displayName, mime) { out -> file.inputStream().use { it.copyTo(out) } }

    suspend fun downloadToFile(url: String, file: File, onProgress: (Float) -> Unit) {
        val job = coroutineContext[Job]
        withContext(Dispatchers.IO) {
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

    private fun fetch(url: String, out: OutputStream, job: Job?, onProgress: (Float) -> Unit) {
        Http.client.newCall(Http.get(url)).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("빈 응답")
            val total = body.contentLength()
            copy(body.byteStream(), out, total, job, onProgress)
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
        onProgress(1f)
    }
}
