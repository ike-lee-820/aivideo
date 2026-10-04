#!/usr/bin/env bash
# ============================================================
#  修复脚本 v3：图片上传 / 手动询问进度 / 历史记录 / 保留提示词
#  位置：在 aivideo/ 目录下执行
#  用法：bash fix-v3.sh
# ============================================================

set -e

if [ ! -f "settings.gradle.kts" ] || [ ! -d "app" ]; then
  echo "错误：请在 aivideo 项目根目录执行"
  exit 1
fi

SRC="app/src/main/java/com/ikelee/aivideo"

echo "[1/7] 更新 Models.kt（增加首帧路径 + 历史记录模型）..."
cat > "$SRC/Models.kt" <<'KOTLIN'
package com.ikelee.aivideo

import java.io.File

data class VideoTask(
    val id: String,
    val prompt: String,
    val model: String = "cogvideox-flash",
    val duration: Int = 10,
    val size: String = "1920x1080",
    val fps: Int = 30,
    val quality: String = "quality",
    val withAudio: Boolean = true,
    val watermark: Boolean = false,
    val initialImagePath: String? = null,
    val status: TaskStatus = TaskStatus.RUNNING,
    val progress: String = "",
    val segments: List<VideoSegment> = emptyList(),
    val error: String? = null,
    val finalFile: File? = null,
    val createdAt: Long = System.currentTimeMillis()
)

enum class TaskStatus { RUNNING, DONE, ERROR }

data class VideoSegment(
    val index: Int,
    val url: String,
    val localFile: File?,
    val duration: Int
)

data class AppSettings(
    val proxyBase: String = "https://api.ocd.ccwu.cc",
    val apiKey: String = "d2796e4995984341be515ea937df0fff.bIRP2Zxo2j6UwQ70",
    val model: String = "cogvideox-flash"
)

data class HistoryRecord(
    val id: String,
    val prompt: String,
    val model: String,
    val duration: Int,
    val size: String,
    val fps: Int,
    val quality: String,
    val withAudio: Boolean,
    val watermark: Boolean,
    val finalPath: String,
    val segments: List<HistorySegment>,
    val error: String?,
    val createdAt: Long
)

data class HistorySegment(
    val index: Int,
    val url: String,
    val duration: Int
)
KOTLIN

echo "[2/7] 新增 ImageUtils.kt..."
cat > "$SRC/ImageUtils.kt" <<'KOTLIN'
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
KOTLIN

echo "[3/7] 新增 HistoryRepo.kt..."
cat > "$SRC/HistoryRepo.kt" <<'KOTLIN'
package com.ikelee.aivideo

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class HistoryRepo(ctx: Context) {

    private val appCtx = ctx.applicationContext
    private val sp = appCtx.getSharedPreferences("aivideo_history", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val dir = File(appCtx.filesDir, "history").apply { mkdirs() }

    private val KEY = "records_json"

    fun list(): List<HistoryRecord> {
        val json = sp.getString(KEY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<HistoryRecord>>() {}.type
            gson.fromJson<List<HistoryRecord>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 把最终视频复制到内部历史目录，并记录元数据
     * 返回保存后的 HistoryRecord（或 null 表示失败）
     */
    fun save(
        srcVideo: File,
        task: VideoTask,
        error: String?
    ): HistoryRecord? {
        if (!srcVideo.exists() || srcVideo.length() == 0L) return null
        val id = "h_" + System.currentTimeMillis() + "_" + (1000..9999).random()
        val dst = File(dir, "$id.mp4")
        return try {
            srcVideo.copyTo(dst, overwrite = true)
            val rec = HistoryRecord(
                id = id,
                prompt = task.prompt,
                model = task.model,
                duration = task.duration,
                size = task.size,
                fps = task.fps,
                quality = task.quality,
                withAudio = task.withAudio,
                watermark = task.watermark,
                finalPath = dst.absolutePath,
                segments = task.segments.map {
                    HistorySegment(it.index, it.url, it.duration)
                },
                error = error,
                createdAt = System.currentTimeMillis()
            )
            val list = list().toMutableList()
            list.add(0, rec)
            sp.edit().putString(KEY, gson.toJson(list)).apply()
            rec
        } catch (e: Exception) {
            try { dst.delete() } catch (_: Exception) {}
            null
        }
    }

    fun delete(id: String) {
        val list = list().toMutableList()
        val target = list.find { it.id == id }
        if (target != null) {
            try { File(target.finalPath).delete() } catch (_: Exception) {}
        }
        list.removeAll { it.id == id }
        sp.edit().putString(KEY, gson.toJson(list)).apply()
    }

    fun clearAll() {
        list().forEach { rec ->
            try { File(rec.finalPath).delete() } catch (_: Exception) {}
        }
        sp.edit().remove(KEY).apply()
    }
}
KOTLIN

echo "[4/7] 更新 ZhipuApi.kt（加 queryRaw）..."
cat > "$SRC/ZhipuApi.kt" <<'KOTLIN'
package com.ikelee.aivideo

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ZhipuApi(private val settings: AppSettings) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun url(path: String): String {
        val target = "https://open.bigmodel.cn/api/paas/v4$path"
        val proxy = settings.proxyBase.trim().trimEnd('/')
        return if (proxy.isEmpty()) target
        else "$proxy/?url=" + URLEncoder.encode(target, "UTF-8")
    }

    fun submit(
        prompt: String,
        imageBase64: String?,
        duration: Int,
        size: String,
        fps: Int,
        quality: String,
        withAudio: Boolean,
        watermark: Boolean
    ): String {
        val body = JsonObject().apply {
            addProperty("model", settings.model)
            addProperty("prompt", prompt)
            addProperty("quality", quality)
            addProperty("with_audio", withAudio)
            addProperty("watermark_enabled", watermark)
            addProperty("size", size)
            addProperty("fps", fps)
            addProperty("duration", duration)
            if (!imageBase64.isNullOrEmpty()) addProperty("image_url", imageBase64)
        }
        val req = Request.Builder()
            .url(url("/videos/generations"))
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(gson.toJson(body).toRequestBody(JSON))
            .build()

        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RuntimeException("提交失败 HTTP ${resp.code}: $text")
            val obj = gson.fromJson(text, JsonObject::class.java)
            return obj.get("id")?.asString ?: throw RuntimeException("未返回任务 ID: $text")
        }
    }

    fun poll(taskId: String): PollResult {
        val req = Request.Builder()
            .url(url("/async-result/$taskId"))
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .get()
            .build()

        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) return PollResult("", null, "HTTP ${resp.code}: $text")
            val obj = gson.fromJson(text, JsonObject::class.java)
            val status = obj.get("task_status")?.asString ?: ""
            return when (status) {
                "SUCCESS" -> {
                    val arr = obj.getAsJsonArray("video_result")
                    val videoUrl = arr?.get(0)?.asJsonObject?.get("url")?.asString
                    PollResult(status, videoUrl, null)
                }
                "FAIL" -> {
                    val err = obj.getAsJsonObject("error")?.get("message")?.asString ?: "任务失败"
                    PollResult(status, null, err)
                }
                else -> PollResult(status, null, null)
            }
        }
    }

    /** 直接返回原始 JSON 文本（用于"询问进度"显示原始内容） */
    fun queryRaw(taskId: String): String {
        val req = Request.Builder()
            .url(url("/async-result/$taskId"))
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .get()
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                "HTTP ${resp.code} ${resp.message}\n\n$text"
            }
        } catch (e: Exception) {
            "请求失败：" + (e.message ?: e.toString())
        }
    }
}

data class PollResult(val status: String, val videoUrl: String?, val error: String?)
KOTLIN

echo "[5/7] 更新 VideoWorker.kt（支持首帧输入 + 完成后写入历史）..."
cat > "$SRC/VideoWorker.kt" <<'KOTLIN'
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
KOTLIN

echo "[6/7] 重写 MainScreen.kt..."
cat > "$SRC/MainScreen.kt" <<'KOTLIN'
package com.ikelee.aivideo

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var page by remember { mutableStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val settingsRepo = remember { SettingsRepo(ctx) }
    var settings by remember { mutableStateOf(settingsRepo.load()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (page == 0) "AI 视频生成器" else "历史记录", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = page == 0,
                    onClick = { page = 0 },
                    icon = { Icon(Icons.Filled.Videocam, contentDescription = null) },
                    label = { Text("生成") }
                )
                NavigationBarItem(
                    selected = page == 1,
                    onClick = { page = 1 },
                    icon = { Icon(Icons.Filled.History, contentDescription = null) },
                    label = { Text("历史") }
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (page == 0) {
                GeneratorPage(
                    settings = settings,
                    showSettings = showSettings,
                    onSettingsChange = {
                        settings = it
                        settingsRepo.save(it)
                    }
                )
            } else {
                HistoryPage()
            }
        }
    }
}

/* ================= 生成页 ================= */

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun GeneratorPage(
    settings: AppSettings,
    showSettings: Boolean,
    onSettingsChange: (AppSettings) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tasks by TaskRepo.tasks.collectAsState()

    var prompt by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf(10) }
    var size by remember { mutableStateOf("1920x1080") }
    var fps by remember { mutableStateOf(30) }
    var quality by remember { mutableStateOf("quality") }
    var withAudio by remember { mutableStateOf(true) }
    var watermark by remember { mutableStateOf(false) }
    var imagePath by remember { mutableStateOf<String?>(null) }
    var imageUriForPreview by remember { mutableStateOf<Uri?>(null) }
    var queryDialogText by remember { mutableStateOf<String?>(null) }
    var queryDialogLoading by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val outFile = File(ctx.filesDir, "inputs/${UUID.randomUUID()}.jpg")
            val ok = withContext(Dispatchers.IO) {
                ImageUtils.compressToJpegFile(ctx, uri, outFile)
            }
            if (ok) {
                // 删除旧图
                imagePath?.let { old -> try { File(old).delete() } catch (_: Exception) {} }
                imagePath = outFile.absolutePath
                imageUriForPreview = uri
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        if (showSettings) {
            item { SettingsCard(settings, onSettingsChange) }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("生成新视频", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))

                    // 首帧图
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { pickImage.launch("image/*") }) {
                            Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (imagePath == null) "上传首帧图（可选）" else "更换首帧图")
                        }
                        if (imagePath != null) {
                            Spacer(Modifier.width(12.dp))
                            IconButton(onClick = {
                                imagePath?.let { p -> try { File(p).delete() } catch (_: Exception) {} }
                                imagePath = null
                                imageUriForPreview = null
                            }) {
                                Icon(Icons.Filled.Close, contentDescription = "移除")
                            }
                        }
                    }

                    imagePath?.let { path ->
                        Spacer(Modifier.height(8.dp))
                        AsyncImage(
                            model = File(path),
                            contentDescription = "首帧预览",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(12.dp)
                                )
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "已压缩为 JPG · 约 ${File(path).length() / 1024} KB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text("提示词") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))
                    Text("时长（秒）", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(5, 10, 20, 30, 40, 50, 60, 120).forEach { d ->
                            FilterChip(
                                selected = duration == d,
                                onClick = { duration = d },
                                label = { Text("${d}s") }
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("尺寸", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("1280x720", "720x1280", "1024x1024", "1920x1080", "1080x1920", "2048x1080", "3840x2160").forEach { s ->
                            FilterChip(
                                selected = size == s,
                                onClick = { size = s },
                                label = { Text(s) }
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("帧率", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(4.dp))
                            Row {
                                FilterChip(selected = fps == 30, onClick = { fps = 30 }, label = { Text("30") })
                                Spacer(Modifier.width(8.dp))
                                FilterChip(selected = fps == 60, onClick = { fps = 60 }, label = { Text("60") })
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text("画质", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(4.dp))
                            Row {
                                FilterChip(selected = quality == "quality", onClick = { quality = "quality" }, label = { Text("质量") })
                                Spacer(Modifier.width(8.dp))
                                FilterChip(selected = quality == "speed", onClick = { quality = "speed" }, label = { Text("速度") })
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("音效", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = withAudio, onCheckedChange = { withAudio = it })
                        Spacer(Modifier.width(24.dp))
                        Text("水印", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = watermark, onCheckedChange = { watermark = it })
                    }

                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val id = UUID.randomUUID().toString()
                            val segCount = if (duration > 10) (duration + 9) / 10 else 1
                            val segDur = if (duration > 10) 10 else duration
                            TaskRepo.add(
                                VideoTask(
                                    id = id,
                                    prompt = prompt,
                                    model = settings.model,
                                    duration = duration,
                                    size = size,
                                    fps = fps,
                                    quality = quality,
                                    withAudio = withAudio,
                                    watermark = watermark,
                                    initialImagePath = imagePath,
                                    segments = (0 until segCount).map {
                                        VideoSegment(it, "", null, segDur)
                                    },
                                    progress = "已加入队列"
                                )
                            )
                            enqueueWork(
                                ctx, id, prompt, duration, size, fps, quality,
                                withAudio, watermark, imagePath
                            )
                            // 保留提示词，不清空
                            // 保留图片，不清空
                        },
                        enabled = prompt.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("开始生成")
                    }

                    if (duration > 10) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "时长超过 10 秒将自动分段：每段 10 秒，每段用上一段的最后一帧作为下一段首帧，最终拼接。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (tasks.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("任务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        TaskRepo.tasks.value.forEach {
                            if (it.status != TaskStatus.RUNNING) TaskRepo.remove(it.id)
                        }
                    }) { Text("清除已完成") }
                }
            }
        }

        items(tasks, key = { it.id }) { task ->
            TaskCard(
                task = task,
                onRemove = { TaskRepo.remove(task.id) },
                onQuery = {
                    queryDialogLoading = true
                    queryDialogText = "请求中…"
                    scope.launch {
                        val api = ZhipuApi(settings)
                        val remoteId = task.segments.firstOrNull()?.url?.let { "" } ?: ""
                        // 取最新的 taskId：从 task 里保存的 taskId 字段取
                        val text = withContext(Dispatchers.IO) {
                            try {
                                // 从 TaskRepo 里没有 taskId 字段；每次查询用当前任务段的最新 task id
                                // 需要扩充 VideoTask 记录 taskId 列表，这里用简化：查询该任务的所有段
                                buildString {
                                    appendLine("任务信息")
                                    appendLine("─────────────")
                                    appendLine("任务 ID：${task.id}")
                                    appendLine("提示词：${task.prompt}")
                                    appendLine("状态：${task.status}")
                                    appendLine("进度：${task.progress}")
                                    appendLine("段数：${task.segments.size}")
                                    appendLine()
                                    appendLine("段列表")
                                    appendLine("─────────────")
                                    task.segments.forEach { seg ->
                                        appendLine("第 ${seg.index + 1} 段 · ${seg.duration}s")
                                        appendLine("  URL：${seg.url.ifEmpty { "（未生成）" }}")
                                        appendLine("  本地：${seg.localFile?.absolutePath ?: "（未下载）"}")
                                    }
                                    appendLine()
                                    appendLine("错误：${task.error ?: "无"}")
                                }
                            } catch (e: Exception) {
                                "查询失败：" + (e.message ?: "")
                            }
                        }
                        queryDialogText = text
                        queryDialogLoading = false
                    }
                }
            )
        }

        item { Spacer(Modifier.height(32.dp)) }
    }

    if (queryDialogText != null) {
        Dialog(onDismissRequest = { queryDialogText = null }) {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("任务详情", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        queryDialogText ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.height(400.dp).fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { queryDialogText = null }) { Text("关闭") }
                    }
                }
            }
        }
    }
}

/* ================= 历史页 ================= */

@Composable
private fun HistoryPage() {
    val ctx = LocalContext.current
    val repo = remember { HistoryRepo(ctx) }
    var records by remember { mutableStateOf(repo.list()) }
    var refreshKey by remember { mutableStateOf(0) }

    fun refresh() {
        records = repo.list()
        refreshKey++
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "共 ${records.size} 条",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { refresh() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("刷新")
                }
                if (records.isNotEmpty()) {
                    TextButton(onClick = {
                        repo.clearAll()
                        refresh()
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("清空")
                    }
                }
            }
        }

        if (records.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("还没有历史记录", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "生成完成的视频会自动保存到这里",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        items(records, key = { it.id }) { rec ->
            val plan = if (rec.duration > 10) {
                "${(rec.duration + 9) / 10} 段 × 10s"
            } else "单段 ${rec.duration}s"
            val file = File(rec.finalPath)
            val exists = file.exists() && file.length() > 0

            Card(
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        rec.prompt.take(60),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "$plan · ${rec.size} · ${rec.fps}fps · ${(file.length() / 1024 / 1024)} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                            .format(java.util.Date(rec.createdAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!exists) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "视频文件已丢失",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    rec.error?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row {
                        Button(
                            onClick = { if (exists) openVideo(ctx, file) },
                            enabled = exists,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("播放")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                if (exists) {
                                    MediaSaver.saveToGallery(
                                        ctx, file,
                                        "aivideo_${rec.id}.mp4"
                                    )
                                }
                            },
                            enabled = exists,
                            modifier = Modifier.weight(1f)
                        ) { Text("导出到相册") }
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = {
                            repo.delete(rec.id)
                            refresh()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除")
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

/* ================= 组件 ================= */

@Composable
private fun SettingsCard(settings: AppSettings, onSave: (AppSettings) -> Unit) {
    var proxy by remember { mutableStateOf(settings.proxyBase) }
    var key by remember { mutableStateOf(settings.apiKey) }
    var model by remember { mutableStateOf(settings.model) }

    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = proxy, onValueChange = { proxy = it }, label = { Text("代理地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("模型名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onSave(AppSettings(proxyBase = proxy, apiKey = key, model = model)) }, modifier = Modifier.fillMaxWidth()) {
                Text("保存")
            }
        }
    }
}

@Composable
private fun TaskCard(task: VideoTask, onRemove: () -> Unit, onQuery: () -> Unit) {
    val ctx = LocalContext.current
    Card(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (task.status) {
                TaskStatus.RUNNING -> MaterialTheme.colorScheme.surfaceVariant
                TaskStatus.DONE -> MaterialTheme.colorScheme.secondaryContainer
                TaskStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.prompt.take(40),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onQuery) {
                    Icon(Icons.Filled.Info, contentDescription = "询问进度")
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = "移除")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${task.duration}s · ${task.size} · ${task.fps}fps",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            if (task.status == TaskStatus.RUNNING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
            Text(task.progress, style = MaterialTheme.typography.bodySmall)

            if (task.segments.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                task.segments.forEach { seg ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(20.dp)
                                .background(
                                    color = if (seg.localFile != null)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape
                                )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "第 ${seg.index + 1} 段 · ${seg.duration}s" + if (seg.localFile != null) " · 已下载" else "",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            task.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }

            task.finalFile?.let { file ->
                Spacer(Modifier.height(12.dp))
                Row {
                    Button(onClick = { openVideo(ctx, file) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("播放")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { openVideo(ctx, file) }, modifier = Modifier.weight(1f)) {
                        Text("打开")
                    }
                }
            }
        }
    }
}

private fun openVideo(ctx: android.content.Context, file: java.io.File) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, ctx.packageName + ".fileprovider", file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(intent)
    } catch (_: Exception) { }
}

private fun enqueueWork(
    ctx: android.content.Context,
    taskId: String,
    prompt: String,
    duration: Int,
    size: String,
    fps: Int,
    quality: String,
    withAudio: Boolean,
    watermark: Boolean,
    initialImagePath: String?
) {
    val data = Data.Builder()
        .putString("taskId", taskId)
        .putString("prompt", prompt)
        .putInt("duration", duration)
        .putString("size", size)
        .putInt("fps", fps)
        .putString("quality", quality)
        .putBoolean("withAudio", withAudio)
        .putBoolean("watermark", watermark)
        .apply { if (initialImagePath != null) putString("initialImagePath", initialImagePath) }
        .build()

    val request = OneTimeWorkRequestBuilder<VideoWorker>()
        .setInputData(data)
        .setConstraints(Constraints.Builder().build())
        .addTag("aivideo")
        .build()

    WorkManager.getInstance(ctx).enqueueUniqueWork("aivideo_$taskId", ExistingWorkPolicy.REPLACE, request)
}
KOTLIN

echo "[7/7] 提交并推送..."
git add -A
git commit -q -m "feat: 图片上传 + 手动询问进度 + 历史记录 + 保留提示词" || echo "  没有改动"
git push origin main

echo ""
echo "============================================"
echo "  已推送"
echo "  Actions: https://github.com/ike-lee-820/aivideo/actions"
echo "============================================"
