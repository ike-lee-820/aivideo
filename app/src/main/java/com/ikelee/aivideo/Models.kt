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
    // 最近一次轮询的 API 原始返回（JSON 字符串）
    val lastPollRaw: String = "",
    // 最近一次提交的 API 原始返回
    val lastSubmitRaw: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

enum class TaskStatus { RUNNING, DONE, ERROR }

data class VideoSegment(
    val index: Int,
    val url: String,
    val localFile: File?,
    val duration: Int,
    val remoteId: String = "",
    val pollRaw: String = ""
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
