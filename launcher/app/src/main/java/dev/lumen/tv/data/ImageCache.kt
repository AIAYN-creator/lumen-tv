package dev.lumen.tv.data

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Small images (team crests) cached in memory and on disk, decoded at icon size. */
object ImageCache {

    private val memory = ConcurrentHashMap<String, ImageBitmap>()

    suspend fun load(context: Context, url: String, sizePx: Int = 128): ImageBitmap? {
        memory[url]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = File(context.cacheDir, "img_" + url.hashCode().toUInt().toString(16))
                if (!file.exists()) {
                    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8_000
                        readTimeout = 10_000
                    }
                    connection.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= sizePx) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()
                    ?.also { memory[url] = it }
            }.getOrNull()
        }
    }
}
