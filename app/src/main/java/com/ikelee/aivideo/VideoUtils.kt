package com.ikelee.aivideo

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object VideoUtils {

    /** 提取视频最后一帧为 JPG */
    fun extractLastFrame(video: File, output: File): Boolean {
        if (!video.exists() || video.length() == 0L) return false
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(video.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            val targetUs = ((durationMs - 150).coerceAtLeast(0)) * 1000

            var frame: Bitmap? = retriever.getFrameAtTime(
                targetUs,
                MediaMetadataRetriever.OPTION_CLOSEST
            )
            if (frame == null) {
                frame = retriever.getFrameAtTime(
                    targetUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
            }
            if (frame == null) return false

            ByteArrayOutputStream().use { baos ->
                frame.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                output.writeBytes(baos.toByteArray())
            }
            frame.recycle()
            output.exists() && output.length() > 0
        } catch (e: Exception) {
            false
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /** JPG → data URI Base64 */
    fun jpgToBase64DataUri(jpg: File): String? {
        if (!jpg.exists() || jpg.length() == 0L) return null
        return try {
            "data:image/jpeg;base64," +
                Base64.encodeToString(jpg.readBytes(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 拼接多个视频
     * 使用 Media3 Transformer + EditedMediaItemSequence
     */
    fun concat(context: Context, segments: List<File>, output: File): Boolean {
        if (segments.isEmpty()) return false
        if (output.exists()) output.delete()

        // 单段直接复制
        if (segments.size == 1) {
            return try {
                segments[0].copyTo(output, overwrite = true)
                output.exists() && output.length() > 0
            } catch (e: Exception) {
                false
            }
        }

        val validSegments = segments.filter { it.exists() && it.length() > 0 }
        if (validSegments.isEmpty()) return false

        val editedItems: List<EditedMediaItem> = validSegments.map { seg ->
            EditedMediaItem.Builder(
                MediaItem.fromUri(Uri.fromFile(seg))
            ).build()
        }

        // 用 EditedMediaItemSequence 把所有片段放进同一个序列
        val sequence = EditedMediaItemSequence(editedItems)
        val composition = Composition.Builder(sequence).build()

        val latch = CountDownLatch(1)
        val successFlag = booleanArrayOf(false)
        val errorMsg = arrayOfNulls<String>(1)

        val transformer = Transformer.Builder(context.applicationContext)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(
                    composition: Composition,
                    exportResult: ExportResult
                ) {
                    successFlag[0] = true
                    latch.countDown()
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exception: ExportException
                ) {
                    successFlag[0] = false
                    errorMsg[0] = exception.message
                    latch.countDown()
                }
            })
            .build()

        return try {
            transformer.start(composition, output.absolutePath)
            latch.await(15, TimeUnit.MINUTES)
            successFlag[0] && output.exists() && output.length() > 0
        } catch (e: Exception) {
            false
        }
    }
}
