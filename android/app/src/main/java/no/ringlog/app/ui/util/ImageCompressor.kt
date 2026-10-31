package no.ringlog.app.ui.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private const val MAX_SIDE = 1600
private const val JPEG_QUALITY = 85

/** Reads [uri] as a JPEG scaled to at most [MAX_SIDE] px; falls back to the original bytes if decoding fails. */
suspend fun readCompressedImage(context: Context, uri: Uri): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        runCatching {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = minOf(1f, MAX_SIDE / maxOf(w, h).toFloat())
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            bitmap.recycle()
            return@withContext out.toByteArray() to "image/jpeg"
        }
    }
    // Android 8.x, or a format ImageDecoder can't read: send as-is and let the server downscale it.
    val mime = resolver.getType(uri) ?: "image/jpeg"
    resolver.openInputStream(uri)?.use { it.readBytes() to mime }
}
