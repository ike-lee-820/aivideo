package com.ikelee.aivideo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class VideoWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val settingsRepo = SettingsRepo(applicationContext)
    private val historyRepo = HistoryRepo(applicationContext)

    private val http = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val taskId = inputData.getString("taskId") ?: return@withContext Result.failure()
        val prompt = inputData.getString("prompt") ?: ""
        val duration = inputData.getInt("duration", 10)
        val size = inputData.getString("size") ?: "1920x1080"
        val fps = inputData.getInt("fps", 30)
        val quality = inputData.getString("quality") ?: "quality"
        val withAudio = inputData.getBoolean("withAudio", true)
        val watermark = inputData.getBoolean("watermark", false)
        val initialImagePath = inputData.getString("initialImagePath")

        val settings = settingsRepo.load()
        if (settings.apiKey.isBlank()) {
            TaskRepo.update(taskId) { it.copy(status = TaskStatus.ERROR, error = "API Key 未配置") }
            return@withContext Result.failure()
        }

        val api = ZhipuApi(settings)
        val workDir = File(applicationContext.filesDir, "tasks/$taskId").apply { mkdirs() }
        val segmentsDir = File(workDir, "segments").apply { mkdirs() }
        val framesDir = File(workDir, "frames").apply { mkdirs() }

        val isMulti = duration > 10
        val totalSegments = if (isMulti) (duration + 9) / 10 else 1
        val segDuration = if (isMulti) 10 else duration

        TaskRepo.update(taskId) {
            it.copy(
                status = TaskStatus.RUNNING,
                progress = "准备中，共 $totalSegments 段",
                segments = (0 until totalSegments).map { i -> VideoSegment(i, "", null, segDuration) }
            )
        }
        setForeground(buildNotification("准备中，共 $totalSegments 段", 0, totalSegments))

        // 首帧：用户上传的图片作为第 1 段的首帧
        var currentImageBase64: String? =
            if (!initialImagePath.isNullOrEmpty()) {
                val f = File(initialImagePath)
                if (f.exists()) ImageUtils.fileToDataUri(f) else null
            } else null

        val segmentFiles = mutableListOf<File>()

        for (i in 0 until totalSegments) {
            val segIndex = i + 1
            try {
                updateProgress(taskId, "第 $segIndex/$totalSegments 段：提交任务", i, totalSegments)
                val remoteId = api.submit(
                    prompt = prompt,
                    imageBase64 = currentImageBase64,
                    duration = segDuration,
                    size = size,
                    fps = fps,
                    quality = quality,
                    withAudio = withAudio,
                    watermark = watermark
                )

                var videoUrl: String? = null
                var attempts = 0
                while (attempts < 100) {
                    delay(3000)
                    attempts++
                    updateProgress(
                        taskId,
                        "第 $segIndex/$totalSegments 段：轮询第 $attempts 次",
                        i, totalSegments
                    )
                    val result = try { api.poll(remoteId) } catch (_: Exception) { null }
                    when (result?.status) {
                        "SUCCESS" -> { videoUrl = result.videoUrl; break }
                        "FAIL" -> throw RuntimeException(result.error ?: "任务失败")
                        else -> continue
                    }
                }

                if (videoUrl.isNullOrEmpty()) throw RuntimeException("轮询超时")

                updateProgress(taskId, "第 $segIndex/$totalSegments 段：下载视频", i, totalSegments)
                val segFile = File(segmentsDir, "seg_$i.mp4")
                downloadToFile(videoUrl, segFile)
                segmentFiles.add(segFile)

                TaskRepo.update(taskId) { t ->
                    val newSegs = t.segments.toMutableList()
                    if (i < newSegs.size) {
                        newSegs[i] = VideoSegment(i, videoUrl, segFile, segDuration)
                    }
                    t.copy(segments = newSegs)
                }

                if (i < totalSegments - 1) {
                    updateProgress(taskId, "第 $segIndex/$totalSegments 段：提取最后一帧", i, totalSegments)
                    val frameFile = File(framesDir, "frame_$i.jpg")
                    currentImageBase64 = if (VideoUtils.extractLastFrame(segFile, frameFile)) {
                        ImageUtils.fileToDataUri(frameFile)
                    } else null
                }
            } catch (e: Exception) {
                val errMsg = "第 $segIndex 段失败：${e.message}"
                TaskRepo.update(taskId) { it.copy(status = TaskStatus.ERROR, error = errMsg) }
                if (segmentFiles.isNotEmpty()) {
                    updateProgress(taskId, "部分失败，拼接已成功段", i, totalSegments)
                    tryConcat(taskId, segmentFiles, workDir, errMsg)
                }
                return@withContext Result.failure()
            }
        }

        if (segmentFiles.isNotEmpty()) {
            updateProgress(taskId, "拼接 $totalSegments 段", totalSegments - 1, totalSegments)
            tryConcat(taskId, segmentFiles, workDir, null)
        }

        TaskRepo.update(taskId) { it.copy(status = TaskStatus.DONE, progress = "完成") }
        Result.success()
    }

    private fun tryConcat(taskId: String, segments: List<File>, workDir: File, error: String?) {
        val finalFile = File(workDir, "final.mp4")
        val ok = VideoUtils.concat(applicationContext, segments, finalFile)
        if (ok && finalFile.exists() && finalFile.length() > 0) {
            TaskRepo.update(taskId) { it.copy(finalFile = finalFile) }
            try {
                MediaSaver.saveToGallery(
                    applicationContext, finalFile,
                    "aivideo_${taskId.take(8)}_${System.currentTimeMillis()}.mp4"
                )
            } catch (_: Exception) {}
            // 保存到历史
            val task = TaskRepo.tasks.value.find { it.id == taskId }
            if (task != null) {
                historyRepo.save(finalFile, task, error)
            }
        }
    }

    private fun downloadToFile(url: String, output: File) {
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("下载失败 HTTP ${resp.code}")
            resp.body?.byteStream()?.use { input ->
                output.outputStream().use { input.copyTo(it) }
            }
        }
    }

    private suspend fun updateProgress(taskId: String, msg: String, idx: Int, total: Int) {
        TaskRepo.update(taskId) { it.copy(progress = msg) }
        setForeground(buildNotification(msg, idx, total))
    }

    private fun buildNotification(msg: String, idx: Int, total: Int): ForegroundInfo {
        val channelId = "aivideo_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "视频生成", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val notif = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("AI 视频生成中")
            .setContentText(msg)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(total.coerceAtLeast(1), idx, false)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(1, notif)
        }
    }
}
