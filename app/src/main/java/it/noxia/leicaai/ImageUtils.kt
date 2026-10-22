package it.noxia.leicaai

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

data class PreparedImage(
    val file: File,
    val width: Int,
    val height: Int,
    val outputSize: String
)

object ImageUtils {
    fun copyUriToPending(context: Context, uri: Uri): File {
        val inputDir = File(context.filesDir, "pending").apply { mkdirs() }
        val output = File(inputDir, "import_${UUID.randomUUID()}.source")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(output).use { input.copyTo(it) }
        } ?: error("Unable to open the selected photograph")
        require(output.length() > 0L) { "The selected photograph is empty" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            output.delete()
            error("The photograph format is unsupported")
        }
        return output
    }

    fun createBlackAndWhiteCopy(context: Context, source: File): File {
        val orientation = ExifInterface(source.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        val original = BitmapFactory.decodeFile(source.absolutePath)
            ?: error("Unable to convert the photograph to black and white")
        val monochrome = Bitmap.createBitmap(
            original.width,
            original.height,
            Bitmap.Config.ARGB_8888
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        Canvas(monochrome).drawBitmap(original, 0f, 0f, paint)

        val outputDir = source.parentFile ?: File(context.filesDir, "pending").apply { mkdirs() }
        val output = File(outputDir, "original_bn_${System.currentTimeMillis()}.jpg")
        FileOutputStream(output).use { stream ->
            check(monochrome.compress(Bitmap.CompressFormat.JPEG, 96, stream)) {
                "Unable to save the black-and-white photograph"
            }
        }
        original.recycle()
        monochrome.recycle()

        ExifInterface(output.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return output
    }

    fun prepareForUpload(
        context: Context,
        source: File,
        quality: PhotoQuality
    ): PreparedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unreadable image" }

        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / sampleSize > 3072) {
            sampleSize *= 2
        }

        var bitmap = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: error("Unable to decode the photograph")

        val orientation = ExifInterface(source.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation != 0f) {
            val rotated = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                Matrix().apply { postRotate(rotation) },
                true
            )
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }

        val uploadLongEdge = if (quality == PhotoQuality.MAX) 3072 else 2048
        val longest = max(bitmap.width, bitmap.height)
        if (longest > uploadLongEdge) {
            val scale = uploadLongEdge.toFloat() / longest
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).roundToInt(),
                (bitmap.height * scale).roundToInt(),
                true
            )
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }

        val uploadDir = File(context.cacheDir, "uploads").apply { mkdirs() }
        val uploadFile = File(uploadDir, "upload_${System.currentTimeMillis()}.jpg")
        FileOutputStream(uploadFile).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)) {
                "Unable to prepare the photograph"
            }
        }

        val width = bitmap.width
        val height = bitmap.height
        bitmap.recycle()

        return PreparedImage(
            uploadFile,
            width,
            height,
            chooseOutputSize(width, height, quality)
        )
    }

    private fun chooseOutputSize(width: Int, height: Int, quality: PhotoQuality): String {
        val longEdge = if (quality == PhotoQuality.MAX) 2560 else 1536
        val shortEdge = if (width >= height) {
            ((longEdge * height.toFloat() / width) / 16f).roundToInt() * 16
        } else {
            ((longEdge * width.toFloat() / height) / 16f).roundToInt() * 16
        }.coerceIn(640, longEdge)

        return if (width >= height) "${longEdge}x$shortEdge" else "${shortEdge}x$longEdge"
    }
}
