package it.noxia.leicaai

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GallerySaver {
    data class SavedPhoto(
        val uri: Uri,
        val displayName: String,
        val dateAddedSeconds: Long
    )

    fun saveFile(context: Context, source: File, displayName: String): Uri =
        save(context, displayName) { output -> source.inputStream().use { it.copyTo(output) } }

    fun saveBytes(context: Context, bytes: ByteArray, displayName: String): Uri =
        save(context, displayName) { output -> output.write(bytes) }

    fun saveEditedBytes(
        context: Context,
        bytes: ByteArray,
        displayName: String,
        source: File,
        preserveSourceCaptureTime: Boolean = true
    ): Uri {
        val tempDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val temp = File(tempDir, "edited_${System.currentTimeMillis()}.jpg")
        try {
            FileOutputStream(temp).use { it.write(bytes) }
            copyUsefulExif(source, temp, preserveSourceCaptureTime)
            val uri = saveFile(context, temp, displayName)
            if (!preserveSourceCaptureTime) {
                markImportedResultAsNew(context, uri, System.currentTimeMillis())
            }
            return uri
        } finally {
            temp.delete()
        }
    }

    fun listEditedPhotos(context: Context): List<SavedPhoto> {
        migrateLegacyPhotos(context)
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )
        val selection =
            "(${MediaStore.Images.Media.RELATIVE_PATH} = ? AND " +
                "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?) OR " +
                "(${MediaStore.Images.Media.RELATIVE_PATH} = ? AND " +
                "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?)"
        val selectionArgs = arrayOf(
            Environment.DIRECTORY_PICTURES + "/NXT-CAM/",
            "NXTCAM_%",
            Environment.DIRECTORY_PICTURES + "/Leica AI/",
            "LeicaAI_%"
        )
        val photos = mutableListOf<SavedPhoto>()
        context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                photos += SavedPhoto(
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn)),
                    displayName = cursor.getString(nameColumn),
                    dateAddedSeconds = cursor.getLong(dateColumn)
                )
            }
        }
        return photos
    }

    fun delete(context: Context, uri: Uri): Boolean =
        context.contentResolver.delete(uri, null, null) > 0

    private fun migrateLegacyPhotos(context: Context) {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME
        )
        resolver.query(
            collection,
            projection,
            "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND " +
                "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf(
                Environment.DIRECTORY_PICTURES + "/Leica AI/",
                "LeicaAI_%"
            ),
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                val oldName = cursor.getString(nameColumn)
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, oldName.replaceFirst("LeicaAI_", "NXTCAM_"))
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/NXT-CAM"
                    )
                }
                runCatching { resolver.update(uri, values, null, null) }
            }
        }
    }

    fun markImportedResultAsNew(context: Context, uri: Uri, createdAtMillis: Long) {
        val resolver = context.contentResolver
        runCatching {
            resolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                val exif = ExifInterface(descriptor.fileDescriptor)
                val date = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                    .format(Date(createdAtMillis))
                exif.setAttribute(ExifInterface.TAG_DATETIME, date)
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, date)
                exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, date)
                exif.saveAttributes()
            }
        }
        runCatching {
            resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DATE_TAKEN, createdAtMillis)
                    put(MediaStore.Images.Media.DATE_MODIFIED, createdAtMillis / 1_000L)
                },
                null,
                null
            )
        }
    }

    private fun copyUsefulExif(
        source: File,
        destination: File,
        preserveSourceCaptureTime: Boolean
    ) {
        runCatching {
            val from = ExifInterface(source.absolutePath)
            val to = ExifInterface(destination.absolutePath)
            val tags = buildList {
                if (preserveSourceCaptureTime) {
                    add(ExifInterface.TAG_DATETIME)
                    add(ExifInterface.TAG_DATETIME_ORIGINAL)
                    add(ExifInterface.TAG_DATETIME_DIGITIZED)
                    add(ExifInterface.TAG_OFFSET_TIME)
                    add(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
                    add(ExifInterface.TAG_OFFSET_TIME_DIGITIZED)
                }
                addAll(listOf(
                ExifInterface.TAG_MAKE,
                ExifInterface.TAG_MODEL,
                ExifInterface.TAG_LENS_MAKE,
                ExifInterface.TAG_LENS_MODEL,
                ExifInterface.TAG_F_NUMBER,
                ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                ExifInterface.TAG_FOCAL_LENGTH,
                ExifInterface.TAG_FLASH,
                ExifInterface.TAG_GPS_LATITUDE,
                ExifInterface.TAG_GPS_LATITUDE_REF,
                ExifInterface.TAG_GPS_LONGITUDE,
                ExifInterface.TAG_GPS_LONGITUDE_REF,
                ExifInterface.TAG_GPS_ALTITUDE,
                ExifInterface.TAG_GPS_ALTITUDE_REF,
                ExifInterface.TAG_GPS_TIMESTAMP,
                ExifInterface.TAG_GPS_DATESTAMP
                ))
            }
            tags.forEach { tag -> from.getAttribute(tag)?.let { to.setAttribute(tag, it) } }
            to.setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL.toString()
            )
            to.setAttribute(ExifInterface.TAG_SOFTWARE, "NXT-CAM")
            to.setAttribute(ExifInterface.TAG_USER_COMMENT, "NXT-CAM photographic development")
            to.saveAttributes()
        }
    }

    private fun save(
        context: Context,
        displayName: String,
        writer: (java.io.OutputStream) -> Unit
    ): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/NXT-CAM"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create the gallery file")

        try {
            resolver.openOutputStream(uri)?.use(writer)
                ?: error("Unable to open the gallery file")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
