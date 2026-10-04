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
    var detailTask by remember { mutableStateOf<VideoTask?>(null) }

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
                onQuery = { detailTask = task }
            )
        }

        item { Spacer(Modifier.height(32.dp)) }
    }

    detailTask?.let { t ->
        TaskDetailDialog(task = t, onDismiss = { detailTask = null })
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


@Composable
private fun TaskDetailDialog(task: VideoTask, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("任务详情", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Box(Modifier.height(480.dp).fillMaxWidth()) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            val sb = StringBuilder()
                            sb.appendLine("【任务信息】")
                            sb.appendLine("任务 ID：${task.id}")
                            sb.appendLine("提示词：${task.prompt}")
                            sb.appendLine("模型：${task.model}")
                            sb.appendLine("时长：${task.duration}s")
                            sb.appendLine("尺寸：${task.size}")
                            sb.appendLine("帧率：${task.fps}fps")
                            sb.appendLine("画质：${task.quality}")
                            sb.appendLine("音效：${task.withAudio}")
                            sb.appendLine("水印：${task.watermark}")
                            sb.appendLine("状态：${task.status}")
                            sb.appendLine("进度：${task.progress}")
                            sb.appendLine("错误：${task.error ?: "无"}")
                            sb.appendLine()
                            sb.appendLine("【段列表】")
                            task.segments.forEach { seg ->
                                sb.appendLine("第 ${seg.index + 1} 段 · ${seg.duration}s")
                                if (seg.remoteId.isNotEmpty()) sb.appendLine("  远端 ID：${seg.remoteId}")
                                sb.appendLine("  URL：${seg.url.ifEmpty { "（未生成）" }}")
                                sb.appendLine("  本地：${seg.localFile?.absolutePath ?: "（未下载）"}")
                                sb.appendLine()
                            }
                            sb.appendLine("【最近一次提交原始返回】")
                            sb.appendLine(task.lastSubmitRaw.ifEmpty { "（暂无）" })
                            sb.appendLine()
                            sb.appendLine("【最近一次轮询原始返回】")
                            sb.appendLine(task.lastPollRaw.ifEmpty { "（暂无）" })
                            Text(
                                sb.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}
