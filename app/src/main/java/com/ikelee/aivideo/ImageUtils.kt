package com.ikelee.aivideo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

object ImageUtils {

    /**
     * 从 URI 读图 → 缩放（保持宽高比）→ 压成 JPEG → 保存到 outputFile
     * 目标：最长边 <= maxDim，文件 <= targetBytes
     */
    fun compressToJpegFile(
        context: Context,
        uri: Uri,
        outputFile: File,
        maxDim: Int = 1920,
        targetBytes: Long = 1024L * 1024L
    ): Boolean {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return false
            val original = input.use { BitmapFactory.decodeStream(it) } ?: return false

            val srcW = original.width
            val srcH = original.height
            if (srcW <= 0 || srcH <= 0) {
                original.recycle()
                return false
            }

            // 等比缩放
            var w = srcW
            var h = srcH
            if (w > maxDim || h > maxDim) {
                if (w >= h) {
                    h = (h.toLong() * maxDim / w).toInt().coerceAtLeast(1)
                    w = maxDim
                } else {
                    w = (w.toLong() * maxDim / h).toInt().coerceAtLeast(1)
                    h = maxDim
                }
            }

            val scaled = if (w != srcW || h != srcH) {
                Bitmap.createScaledBitmap(original, w, h, true).also {
                    if (it !== original) original.recycle()
                }
            } else original

            // 白底（透明图转 JPG 避免黑块）
            val rgb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(rgb)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(scaled, 0f, 0f, null)
            if (scaled !== rgb) scaled.recycle()

            // 迭代质量压缩
            outputFile.parentFile?.mkdirs()
            var quality = 92
            var bytes: ByteArray
            while (true) {
                val baos = ByteArrayOutputStream()
                rgb.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                bytes = baos.toByteArray()
                if (bytes.size <= targetBytes || quality <= 30) break
                quality -= 8
            }

            // 依然太大 → 等比缩小再压
            var shrinkW = w
            var shrinkH = h
            var shrinkRounds = 0
            var currentBitmap = rgb
            while (bytes.size > targetBytes && (shrinkW > 320 || shrinkH > 320) && shrinkRounds < 8) {
                shrinkW = (shrinkW * 0.8).toInt().coerceAtLeast(1)
                shrinkH = (shrinkH * 0.8).toInt().coerceAtLeast(1)
                val next = Bitmap.createScaledBitmap(currentBitmap, shrinkW, shrinkH, true)
                if (next !== currentBitmap) currentBitmap.recycle()
                currentBitmap = next
                val baos = ByteArrayOutputStream()
                currentBitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                bytes = baos.toByteArray()
                shrinkRounds++
            }

            outputFile.writeBytes(bytes)
            currentBitmap.recycle()
            outputFile.exists() && outputFile.length() > 0
        } catch (e: Exception) {
            false
        }
    }

    /** JPG 文件 → data URI Base64 */
    fun fileToDataUri(file: File): String? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            "data:image/jpeg;base64," +
                Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }
}
