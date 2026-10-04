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
