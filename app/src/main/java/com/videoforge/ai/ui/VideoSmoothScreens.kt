package com.videoforge.ai.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.videoforge.ai.viewmodel.ProcessingStage
import com.videoforge.ai.viewmodel.VideoPageRoute
import com.videoforge.ai.viewmodel.VideoProcessRequest
import com.videoforge.ai.viewmodel.VideoProcessUiState
import com.videoforge.ai.viewmodel.VideoProcessViewModel
import java.util.Locale

@Composable
fun VideoSmoothAIApp(
    processViewModel: VideoProcessViewModel = viewModel(),
    onVideoPicked: (Uri) -> Unit,
    onStartProcess: (VideoProcessRequest) -> Unit,
    onPauseTask: (Boolean) -> Unit,
    onCancelTask: () -> Unit
) {
    val state by processViewModel.uiState.collectAsState()

    VideoSmoothAITheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            when (state.currentPage) {
                VideoPageRoute.Home -> HomeScreen(
                    state = state,
                    onVideoPicked = onVideoPicked,
                    onNext = processViewModel::openSettings
                )
                VideoPageRoute.Settings -> SettingsScreen(
                    state = state,
                    onBack = processViewModel::backToHome,
                    onFrameRateChange = processViewModel::setTargetFrameRate,
                    onExtremeHeatModeChange = processViewModel::setExtremeHeatMode,
                    onAutoCleanChange = processViewModel::setAutoCleanFragments,
                    onStartProcess = {
                        processViewModel.createProcessRequest()?.let(onStartProcess)
                    }
                )
                VideoPageRoute.Processing -> ProcessingScreen(
                    state = state,
                    onPauseTask = {
                        val nextPaused = !state.isPaused
                        processViewModel.setPaused(nextPaused)
                        onPauseTask(nextPaused)
                    },
                    onCancelTask = {
                        onCancelTask()
                        processViewModel.cancelCurrentTask()
                    }
                )
                VideoPageRoute.Result -> ResultScreen(
                    onBackHome = processViewModel::backToHome
                )
            }
        }
    }
}

@Composable
fun HomeScreen(
    state: VideoProcessUiState,
    onVideoPicked: (Uri) -> Unit,
    onNext: () -> Unit
) {
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> uri?.let(onVideoPicked) }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        Text(
            text = "VideoSmoothAI",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "让每一帧画面都更加流畅",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(28.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .drawBehind {
                    val dash = PathEffect.dashPathEffect(
                        floatArrayOf(14.dp.toPx(), 9.dp.toPx()),
                        0f
                    )
                    drawRoundRect(
                        color = dashedOutlineColor.copy(alpha = 0.8f),
                        style = Stroke(width = 1.8.dp.toPx(), pathEffect = dash),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(26.dp.toPx())
                    )
                }
                .clickable {
                    videoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(34.dp)
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text("选择一个视频", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "点击从相册导入视频",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        state.selectedVideo?.let { video ->
            Spacer(Modifier.height(20.dp))
            SelectedVideoCard(
                fileName = video.fileName,
                duration = formatDuration(video.durationMs),
                resolution = video.resolution,
                frameRate = video.originalFrameRate?.let { String.format(Locale.US, "%.2f FPS", it) } ?: "未知"
            )
        }

        Spacer(Modifier.weight(1f))
        Button(
            onClick = onNext,
            enabled = state.selectedVideo != null,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text("下一步：调整参数", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SelectedVideoCard(
    fileName: String,
    duration: String,
    resolution: String,
    frameRate: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VideoFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("已选择视频", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                VideoMetaItem("时长", duration)
                VideoMetaItem("分辨率", resolution)
                VideoMetaItem("原帧率", frameRate)
            }
        }
    }
}

@Composable
private fun VideoMetaItem(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SettingsScreen(
    state: VideoProcessUiState,
    onBack: () -> Unit,
    onFrameRateChange: (Int) -> Unit,
    onExtremeHeatModeChange: (Boolean) -> Unit,
    onAutoCleanChange: (Boolean) -> Unit,
    onStartProcess: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
            }
            Spacer(Modifier.width(4.dp))
            Text("处理参数", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(28.dp))
        Text("目标帧率", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(60, 120).forEach { fps ->
                FilterChip(
                    selected = state.targetFrameRate == fps,
                    onClick = { onFrameRateChange(fps) },
                    label = { Text("${fps} FPS") },
                    leadingIcon = if (state.targetFrameRate == fps) {
                        { Icon(Icons.Default.CheckCircle, contentDescription = null) }
                    } else null,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(28.dp))
        SettingSwitchRow(
            icon = Icons.Default.LocalFireDepartment,
            title = "极速发热模式",
            subtitle = "提高处理强度，设备温度可能快速上升",
            checked = state.isExtremeHeatMode,
            onCheckedChange = onExtremeHeatModeChange
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SettingSwitchRow(
            icon = Icons.Default.CloudUpload,
            title = "自动清理碎片",
            subtitle = "确认片段写入成功后，清理本任务生成的临时文件",
            checked = state.autoCleanFragments,
            onCheckedChange = onAutoCleanChange
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onStartProcess,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            shape = RoundedCornerShape(18.dp),
            enabled = state.selectedVideo != null
        ) {
            Text("开始 AI 处理", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SettingSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun ProcessingScreen(
    state: VideoProcessUiState,
    onPauseTask: () -> Unit,
    onCancelTask: () -> Unit
) {
    val progress = state.progressPercent.coerceIn(0f, 100f)
    val stageText = when (state.currentStage) {
        ProcessingStage.Extraction -> "正在提取视频帧..."
        ProcessingStage.Interpolation -> "正在 AI 计算插帧..."
        ProcessingStage.Encoding -> "正在编码输出视频..."
        ProcessingStage.Completed -> "处理已完成"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("正在处理", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))

        Box(
            modifier = Modifier.size(264.dp),
            contentAlignment = Alignment.Center
        ) {
            val trackColor = MaterialTheme.colorScheme.surfaceVariant
            val progressColor = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxSize()) {
                val strokeWidth = 18.dp.toPx()
                val inset = strokeWidth / 2f
                val arcSize = androidx.compose.ui.geometry.Size(
                    width = size.width - strokeWidth,
                    height = size.height - strokeWidth
                )
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = 360f * progress / 100f,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (state.isPaused) "已暂停" else "${progress.toInt()}%",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (state.isPaused) "等待继续" else state.estimatedRemainingTime,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(30.dp))
        Text(stageText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text(
            "目标 ${state.targetFrameRate} FPS",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (state.isOverheated) {
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "手机温度过高，已自动暂停散热，剩余时间将顺延",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onPauseTask,
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(if (state.isPaused) "继续" else "暂停")
            }
            Button(
                onClick = onCancelTask,
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text("取消")
            }
        }
    }
}

@Composable
private fun ResultScreen(onBackHome: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(76.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(18.dp))
        Text("处理完成", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "视频处理任务已结束。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onBackHome, modifier = Modifier.fillMaxWidth()) {
            Text("返回主页")
        }
    }
}

@Composable
fun VideoSmoothAITheme(content: @Composable () -> Unit) {
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = if (darkTheme) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors, content = content)
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}
