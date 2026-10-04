package com.ikelee.aivideo

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 视频处理工具
 * - 提取最后一帧：MediaMetadataRetriever（系统原生）
 * - 拼接视频：AndroidX Media3 Transformer（官方库）
 */
object VideoUtils {

    /**
     * 从视频文件提取最后一帧，保存为 JPG
     * 返回 true 表示成功
     */
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

            // 目标时间点：倒数 0.15 秒
            val targetUs = ((durationMs - 150).coerceAtLeast(0)) * 1000

            // 优先取精确帧
            var frame: Bitmap? = retriever.getFrameAtTime(
                targetUs,
                MediaMetadataRetriever.OPTION_CLOSEST
            )
            // 兜底：取最近关键帧
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

    /**
     * 读取 JPG 文件为 Base64 data URI（供下一段作为首帧使用）
     */
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
     * 拼接多个视频片段为一个
     * 使用 AndroidX Media3 Transformer（后台线程阻塞等待完成）
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

        val items = segments
            .filter { it.exists() && it.length() > 0 }
            .map { seg ->
                EditedMediaItem.Builder(
                    MediaItem.fromUri(Uri.fromFile(seg))
                ).build()
            }

        if (items.isEmpty()) return false

        val latch = CountDownLatch(1)
        val successFlag = booleanArrayOf(false)

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
                    latch.countDown()
                }
            })
            .build()

        return try {
            val composition = Composition.Builder(items).build()
            transformer.start(composition, output.absolutePath)
            // 最长等待 15 分钟
            latch.await(15, TimeUnit.MINUTES)
            successFlag[0] && output.exists() && output.length() > 0
        } catch (e: Exception) {
            false
        }
    }
}
