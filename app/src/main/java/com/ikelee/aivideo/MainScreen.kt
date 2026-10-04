package com.ikelee.aivideo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val settingsRepo = remember { SettingsRepo(ctx) }
    var settings by remember { mutableStateOf(settingsRepo.load()) }

    var prompt by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf(10) }
    var size by remember { mutableStateOf("1920x1080") }
    var fps by remember { mutableStateOf(30) }
    var quality by remember { mutableStateOf("quality") }
    var withAudio by remember { mutableStateOf(true) }
    var watermark by remember { mutableStateOf(false) }

    var showSettings by remember { mutableStateOf(false) }

    val tasks by TaskRepo.tasks.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 视频生成器", fontWeight = FontWeight.SemiBold) },
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
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            if (showSettings) {
                item {
                    SettingsCard(settings) {
                        settings = it
                        settingsRepo.save(it)
                    }
                }
            }

            item {
                Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "生成新视频",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
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
                        DurationChips(duration) { duration = it }

                        Spacer(Modifier.height(12.dp))
                        Text("尺寸", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(6.dp))
                        SizeChips(size) { size = it }

                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("帧率", style = MaterialTheme.typography.labelLarge)
                                Row {
                                    FilterChip(
                                        selected = fps == 30,
                                        onClick = { fps = 30 },
                                        label = { Text("30") }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    FilterChip(
                                        selected = fps == 60,
                                        onClick = { fps = 60 },
                                        label = { Text("60") }
                                    )
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text("画质", style = MaterialTheme.typography.labelLarge)
                                Row {
                                    FilterChip(
                                        selected = quality == "quality",
                                        onClick = { quality = "quality" },
                                        label = { Text("质量") }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    FilterChip(
                                        selected = quality == "speed",
                                        onClick = { quality = "speed" },
                                        label = { Text("速度") }
                                    )
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
                                        segments = (0 until segCount).map {
                                            VideoSegment(it, "", null, segDur)
                                        },
                                        progress = "已加入队列"
                                    )
                                )
                                enqueueWork(ctx, id, prompt, duration, size, fps, quality, withAudio, watermark)
                                prompt = ""
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
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "任务",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            TaskRepo.tasks.value.forEach {
                                if (it.status != TaskStatus.RUNNING) TaskRepo.remove(it.id)
                            }
                        }) {
                            Text("清除已完成")
                        }
                    }
                }
            }

            items(tasks, key = { it.id }) { task ->
                TaskCard(task) { TaskRepo.remove(task.id) }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun DurationChips(current: Int, onSelect: (Int) -> Unit) {
    val options = listOf(5, 10, 20, 30, 40, 50, 60, 120)
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { d ->
            FilterChip(
                selected = current == d,
                onClick = { onSelect(d) },
                label = { Text("${d}s") }
            )
        }
    }
}

@Composable
private fun SizeChips(current: String, onSelect: (String) -> Unit) {
    val options = listOf(
        "1280x720", "720x1280", "1024x1024",
        "1920x1080", "1080x1920", "2048x1080", "3840x2160"
    )
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { s ->
            FilterChip(
                selected = current == s,
                onClick = { onSelect(s) },
                label = { Text(s) }
            )
        }
    }
}

@Composable
private fun SettingsCard(settings: AppSettings, onSave: (AppSettings) -> Unit) {
    var proxy by remember { mutableStateOf(settings.proxyBase) }
    var key by remember { mutableStateOf(settings.apiKey) }
    var model by remember { mutableStateOf(settings.model) }

    Card(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "设置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = proxy,
                onValueChange = { proxy = it },
                label = { Text("代理地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("API Key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("模型名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    onSave(AppSettings(proxyBase = proxy, apiKey = key, model = model))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存")
            }
        }
    }
}

@Composable
private fun TaskCard(task: VideoTask, onRemove: () -> Unit) {
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
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(20.dp)
                                .background(
                                    color = if (seg.localFile != null)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape
                                )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "第 ${seg.index + 1} 段 · ${seg.duration}s" +
                                if (seg.localFile != null) " · 已下载" else "",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            task.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            task.finalFile?.let { file ->
                Spacer(Modifier.height(12.dp))
                Row {
                    Button(
                        onClick = { openVideo(ctx, file) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("播放")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { openVideo(ctx, file) },
                        modifier = Modifier.weight(1f)
                    ) {
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
            ctx,
            ctx.packageName + ".fileprovider",
            file
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
    watermark: Boolean
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
        .build()

    val constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(false)
        .build()

    val request = OneTimeWorkRequestBuilder<VideoWorker>()
        .setInputData(data)
        .setConstraints(constraints)
        .addTag("aivideo")
        .build()

    WorkManager.getInstance(ctx)
        .enqueueUniqueWork(
            "aivideo_$taskId",
            ExistingWorkPolicy.REPLACE,
            request
        )
}
