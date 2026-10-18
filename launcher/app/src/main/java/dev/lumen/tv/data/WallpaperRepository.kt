package dev.lumen.tv.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * User photos live in the app's external files dir:
 * /sdcard/Android/data/dev.lumen.tv/files/wallpapers (pushed with adb for now).
 */
object WallpaperRepository {

    private val extensions = setOf("jpg", "jpeg", "png", "webp")

    fun files(context: Context): List<File> =
        context.getExternalFilesDir("wallpapers")
            ?.listFiles { file -> file.extension.lowercase() in extensions }
            ?.sortedBy { it.name }
            .orEmpty()

    /** Decodes at roughly screen size (the Chromecast HD only has ~1.4 GB of RAM) and applies EXIF rotation. */
    suspend fun decode(file: File, maxWidth: Int = 1920, maxHeight: Int = 1080): ImageBitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxWidth && bounds.outHeight / (sample * 2) >= maxHeight) {
                    sample *= 2
                }
                val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: return@runCatching null
                bitmap.rotated(exifDegrees(file)).asImageBitmap()
            }.getOrNull()
        }

    private fun exifDegrees(file: File): Int =
        when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    private fun Bitmap.rotated(degrees: Int): Bitmap {
        if (degrees == 0) return this
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true).also { recycle() }
    }
}
