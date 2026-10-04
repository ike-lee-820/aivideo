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
            val id = obj.get("id")?.asString
                ?: throw RuntimeException("未返回任务 ID: $text")
            return id
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
}

data class PollResult(val status: String, val videoUrl: String?, val error: String?)
