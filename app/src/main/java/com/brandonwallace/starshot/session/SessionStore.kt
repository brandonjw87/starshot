package com.brandonwallace.starshot.session

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.DngCreator
import android.location.Location
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import com.brandonwallace.starshot.camera.CameraOption
import com.brandonwallace.starshot.camera.Frame
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream

data class SessionEntry(val folder: String, val cover: Uri?, val files: Int, val dateAdded: Long)

/** Writes a session's files to Pictures/StarShot/<folder>/ via MediaStore (no storage permission needed). */
class SessionStore(private val ctx: Context, val folder: String) {
    val relPath = "${Environment.DIRECTORY_PICTURES}/StarShot/$folder/"
    private val resolver = ctx.contentResolver
    val written = ArrayList<Uri>()

    fun write(name: String, mime: String, block: (OutputStream) -> Unit): Uri {
        val uri = insert(name, mime, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), relPath)
            ?: insert(name, mime, MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "${Environment.DIRECTORY_DOWNLOADS}/StarShot/$folder/")
            ?: error("Could not create $name in MediaStore")
        try {
            resolver.openOutputStream(uri)!!.use { os -> val b = BufferedOutputStream(os, 1 shl 20); block(b); b.flush() }
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }; throw e
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        written += uri
        return uri
    }

    private fun insert(name: String, mime: String, collection: Uri, path: String): Uri? = runCatching {
        resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })
    }.getOrNull()

    fun writeDng(name: String, opt: CameraOption, frame: Frame, location: Location?): Uri =
        write(name, "image/x-adobe-dng") { os ->
            DngCreator(opt.chars, frame.sensorResult).use { d ->
                d.setOrientation(opt.exifOrientation)
                location?.let { d.setLocation(it) }
                d.writeImage(os, frame.image)
            }
        }

    fun copyFile(f: File, name: String, mime: String): Uri = write(name, mime) { os -> f.inputStream().use { it.copyTo(os, 1 shl 20) } }

    fun delete(uri: Uri) { runCatching { resolver.delete(uri, null, null) } }

    companion object {
        fun freeBytes(): Long = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(0L)

        fun sessions(ctx: Context): List<SessionEntry> {
            val col = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED)
            val groups = LinkedHashMap<String, MutableList<Triple<Uri, String, Long>>>()
            ctx.contentResolver.query(col, proj, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("${Environment.DIRECTORY_PICTURES}/StarShot/%"), "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(col, c.getLong(0))
                    val folder = (c.getString(1) ?: "").trimEnd('/').substringAfterLast('/')
                    groups.getOrPut(folder) { ArrayList() } += Triple(uri, c.getString(2) ?: "", c.getLong(3))
                }
            }
            return groups.map { (folder, items) ->
                val cover = items.firstOrNull { it.second == "image/jpeg" }?.first
                SessionEntry(folder, cover, items.size, items.maxOf { it.third })
            }
        }
    }
}
