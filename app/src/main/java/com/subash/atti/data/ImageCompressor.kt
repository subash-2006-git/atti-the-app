package com.subash.atti.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shrinks a picked photo to a JPEG small enough to send quickly over Bluetooth,
 * the way chat apps do: longest side at most [MAX_SIDE] px, upright, no transparency.
 */
object ImageCompressor {
    const val MAX_SIDE = 1600
    private const val QUALITY = 82

    data class Compressed(val file: File, val width: Int, val height: Int)

    /** Returns null if the photo can't be read (deleted, unsupported format, …). */
    fun compress(context: Context, uri: Uri, out: File): Compressed? = runCatching {
        val decoded = decode(context, uri) ?: return null
        val bitmap = if (decoded.hasAlpha()) onWhite(decoded) else decoded
        out.parentFile?.mkdirs()
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        Compressed(out, bitmap.width, bitmap.height).also { bitmap.recycle() }
    }.getOrNull()

    /** The size to scale a [width]×[height] image to so its longest side fits [maxSide]. */
    fun targetSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        val scale = minOf(1f, maxSide / max(width, height).toFloat())
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    private fun decode(context: Context, uri: Uri): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies the EXIF rotation and reads HEIC as well as JPEG and PNG.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val (w, h) = targetSize(info.size.width, info.size.height)
                decoder.setTargetSize(w, h)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            decodeLegacy(context, uri)
        }

    private fun decodeLegacy(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Decode at roughly the right size first to save memory, then scale exactly.
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null

        val rotation = resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        val (w, h) = targetSize(raw.width, raw.height)
        val matrix = Matrix().apply {
            postScale(w / raw.width.toFloat(), h / raw.height.toFloat())
            postRotate(rotation.toFloat())
        }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            .also { if (it !== raw) raw.recycle() }
    }

    /** JPEG has no transparency, so put see-through images on white instead of black. */
    private fun onWhite(source: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(result).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, 0f, 0f, null)
        }
        source.recycle()
        return result
    }
}
