package com.akismogh.videocompressor

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Files handed between native code and the web app.
 *
 * Inputs (recordings, shared videos) live in cacheDir/media and are served to the
 * page at https://appassets.androidplatform.net/media/<name>. Only the newest input
 * is kept — the page copies it into a Blob as soon as it picks it up.
 */
object MediaFiles {
    const val HOST = "https://appassets.androidplatform.net"
    const val SAVE_FOLDER = "Movies/Video Compressor"

    fun inputDir(ctx: Context): File = File(ctx.cacheDir, "media").apply { mkdirs() }

    private fun outputDir(ctx: Context): File = File(ctx.cacheDir, "out").apply { mkdirs() }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    /** Clears old inputs and returns the base file name for a new recording's segments. */
    fun newRecordingBase(ctx: Context): String {
        clearDir(inputDir(ctx))
        return "screen-${stamp()}"
    }

    fun newOutputFile(ctx: Context, name: String): File {
        clearDir(outputDir(ctx))
        return File(outputDir(ctx), safeName(name, "compressed.mp4"))
    }

    fun describe(file: File, displayName: String = file.name, mime: String = "video/mp4"): String =
        JSONObject()
            .put("url", "$HOST/media/${Uri.encode(file.name)}")
            .put("name", displayName)
            .put("mime", mime)
            .toString()

    /** Copies a shared content:// video into the input dir; returns the page descriptor. */
    fun importShared(ctx: Context, uri: Uri): String {
        val resolver = ctx.contentResolver
        val mime = resolver.getType(uri) ?: "video/mp4"
        var display: String? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) display = it.getString(0)
        }
        val ext = when {
            mime.contains("quicktime") -> "mov"
            mime.contains("webm") -> "webm"
            mime.contains("3gpp") -> "3gp"
            else -> "mp4"
        }
        val name = safeName(display ?: "shared-${stamp()}.$ext", "shared.$ext")
        clearDir(inputDir(ctx))
        val target = File(inputDir(ctx), name)
        resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { input.copyTo(it) }
        } ?: throw IllegalStateException("Couldn't open the shared video")
        return describe(target, name, mime)
    }

    /** Publishes a finished MP4 to the gallery and returns its MediaStore uri. */
    fun saveToGallery(ctx: Context, file: File): Uri {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, SAVE_FOLDER)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Couldn't create the gallery entry")
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun safeName(name: String, fallback: String): String {
        val cleaned = name.replace(Regex("[^\\w.\\- ]"), "_").trim().take(120)
        return cleaned.ifEmpty { fallback }
    }

    private fun clearDir(dir: File) {
        dir.listFiles()?.forEach { it.delete() }
    }
}
