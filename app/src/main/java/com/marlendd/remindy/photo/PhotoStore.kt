package com.marlendd.remindy.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/**
 * Фото места вещи: файлы в ПРИВАТНОЙ папке `filesDir/photos/` (другим приложениям и
 * галерее не видны; в UI показ за гейтом; не шифруются и в бэкап не входят – решения
 * пользователя). В БД хранится только ИМЯ файла (`Item.photoFile`) – резолвим здесь.
 *
 * Камера пишет в файл по FileProvider-URI, затем [compressInPlace] ужимает снимок
 * (полнокадровые 8-12 МБ ни к чему) и запекает EXIF-поворот в пиксели – дальше файл
 * можно показывать без всякой EXIF-логики.
 */
object PhotoStore {

    private const val DIR = "photos"
    private const val MAX_DIM = 1600      // большая сторона после сжатия
    private const val JPEG_QUALITY = 85

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    fun newFile(context: Context): File = File(dir(context), "place_${UUID.randomUUID()}.jpg")

    fun fileFor(context: Context, name: String): File = File(dir(context), name)

    /** content://-URI для системной камеры (authority из манифеста). */
    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun delete(context: Context, name: String?) {
        if (name.isNullOrEmpty()) return
        runCatching { fileFor(context, name).delete() }
    }

    /** Полная зачистка (восстановление из бэкапа: фото в копию не входят – все осиротели). */
    fun deleteAll(context: Context) {
        dir(context).listFiles()?.forEach { runCatching { it.delete() } }
    }

    /**
     * Ужимает файл камеры до [MAX_DIM] по большей стороне + поворачивает по EXIF.
     * При любой ошибке молча оставляет оригинал – фото остаётся рабочим, просто большим.
     */
    fun compressInPlace(file: File) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching

            val decoded = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_DIM)
                },
            ) ?: return@runCatching

            var bmp = decoded
            val maxSide = maxOf(bmp.width, bmp.height)
            if (maxSide > MAX_DIM) {
                val scale = MAX_DIM.toFloat() / maxSide
                bmp = Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            }
            val rotation = exifRotation(file)
            if (rotation != 0f) {
                val m = Matrix().apply { postRotate(rotation) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }

            // Атомарно: во временный + rename, чтобы обрыв не оставил битый jpg
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }

    /** Битмап для показа, ужатый до [maxDim] (миниатюра/просмотр). Null – файла нет/битый. */
    fun decodeScaled(context: Context, name: String, maxDim: Int): Bitmap? = runCatching {
        val f = fileFor(context, name)
        if (!f.exists()) return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        BitmapFactory.decodeFile(
            f.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            },
        )
    }.getOrNull()

    private fun sampleSize(w: Int, h: Int, maxDim: Int): Int {
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= maxDim) sample *= 2
        return sample
    }

    // android.media.ExifInterface помечен deprecated в пользу androidx-артефакта, но нам
    // нужно только чтение ориентации – ради этого не тянем новую зависимость.
    @Suppress("DEPRECATION")
    private fun exifRotation(file: File): Float = runCatching {
        when (
            android.media.ExifInterface(file.absolutePath).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL,
            )
        ) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)
}
