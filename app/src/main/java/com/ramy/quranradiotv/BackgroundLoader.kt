package com.ramy.quranradiotv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves the background image the user picked in Settings. Custom images may
 * be a content:// pick from device storage, a plain file path, or an http(s)
 * URL; remote ones are cached on disk so they still appear without a network.
 */
object BackgroundLoader {

    private const val CACHE_NAME = "custom_background.img"
    private const val MAX_EDGE = 1920

    sealed interface Result {
        object UseDefault : Result
        object None : Result
        data class Custom(val bitmap: Bitmap) : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun load(context: Context, prefs: Prefs): Result = withContext(Dispatchers.IO) {
        when (prefs.backgroundMode) {
            Prefs.BG_NONE -> Result.None
            Prefs.BG_CUSTOM -> loadCustom(context, prefs.customBackgroundUri)
            else -> Result.UseDefault
        }
    }

    private fun loadCustom(context: Context, uriString: String?): Result {
        if (uriString.isNullOrBlank()) return Result.UseDefault
        return try {
            val bitmap = when {
                uriString.startsWith("http://") || uriString.startsWith("https://") ->
                    decodeRemote(context, uriString)
                else -> decodeLocal(context, Uri.parse(uriString))
            }
            if (bitmap != null) Result.Custom(bitmap)
            else Result.Failed(context.getString(R.string.bg_load_failed))
        } catch (e: SecurityException) {
            // Persistable permission was revoked (e.g. the picked file moved).
            Result.Failed(context.getString(R.string.bg_permission_lost))
        } catch (e: Exception) {
            Result.Failed(context.getString(R.string.bg_load_failed))
        }
    }

    private fun decodeLocal(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.RGB_565 // backgrounds don't need alpha; halves memory
        }
        return openStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun decodeRemote(context: Context, url: String): Bitmap? {
        val cache = File(context.filesDir, CACHE_NAME)
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = true
            }
            connection.inputStream.use { input ->
                cache.outputStream().use { output -> input.copyTo(output) }
            }
            connection.disconnect()
        } catch (e: Exception) {
            // Fall through to whatever was cached from a previous successful load.
            if (!cache.exists()) throw e
        }
        if (!cache.exists()) return null
        return decodeLocal(context, Uri.fromFile(cache))
    }

    private fun openStream(context: Context, uri: Uri): InputStream? = when (uri.scheme) {
        null, "file" -> uri.path?.let { File(it).takeIf(File::exists)?.inputStream() }
        else -> context.contentResolver.openInputStream(uri)
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= MAX_EDGE || height / (sample * 2) >= MAX_EDGE) {
            sample *= 2
        }
        return sample
    }

    fun clearCache(context: Context) {
        File(context.filesDir, CACHE_NAME).delete()
    }
}
