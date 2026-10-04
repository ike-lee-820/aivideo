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
