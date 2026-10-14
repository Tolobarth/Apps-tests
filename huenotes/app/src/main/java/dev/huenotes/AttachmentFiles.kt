package dev.huenotes

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

object AttachmentFiles {
    /** Longest edge of stored photos and drawings: plenty for a note, small enough to sync. */
    const val MAX_EDGE = 1280

    private fun open(r: ContentResolver, uri: Uri): InputStream = r.openInputStream(uri) ?: throw IOException("Can't open $uri")

    /** Decodes [uri], fixes its EXIF rotation, scales it down, and writes a JPEG to [out]. */
    fun importImage(r: ContentResolver, uri: Uri, out: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(r, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Not an image")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        var bmp = open(r, uri).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw IOException("Can't decode image")
        val orientation = try {
            open(r, uri).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
        bmp = fit(oriented(bmp, orientation), MAX_EDGE)
        if (bmp.hasAlpha()) bmp = onWhite(bmp)
        FileOutputStream(out).use { if (!bmp.compress(Bitmap.CompressFormat.JPEG, 85, it)) throw IOException("Can't write image") }
    }

    /** Writes a drawing as PNG, scaled down to [MAX_EDGE] if needed. */
    fun saveDrawing(bmp: Bitmap, out: File) {
        FileOutputStream(out).use { if (!fit(bmp, MAX_EDGE).compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("Can't write drawing") }
    }

    internal fun oriented(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    internal fun fit(src: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxEdge) return src
        val s = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(src, maxOf(1, (src.width * s).toInt()), maxOf(1, (src.height * s).toInt()), true)
    }

    private fun onWhite(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(src, 0f, 0f, null) }
        return out
    }

    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** A small decoded copy for cards and the editor, cached; null while the file is missing (e.g. not yet synced). */
    fun thumb(file: File, maxEdge: Int): Bitmap? {
        if (!file.exists()) return null
        val key = "${file.path}:${file.lastModified()}:$maxEdge"
        cache.get(key)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.also { cache.put(key, it) }
    }

    fun duration(ms: Long): String {
        val s = (ms / 1000).toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }
}
