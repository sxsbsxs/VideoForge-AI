package com.videoforge.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.videoforge.ai.benchmark.DeviceBenchmarkUiState
import com.videoforge.ai.benchmark.ResolutionBenchmarkResult
import java.util.Locale

@Composable
fun DeviceBenchmarkScreen(
    state: DeviceBenchmarkUiState,
    onBack: () -> Unit,
    onRunBenchmark: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !state.isRunning) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
            }
            Text(
                "设备性能测试",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(state.deviceName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("芯片：${state.chipName}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "内存：${formatMemory(state.totalRamMb)}，当前可用：${formatMemory(state.availableRamMb)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Vulkan 设备：${state.vulkanDeviceCount?.toString() ?: "尚未检测"}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "实际计算后端：${state.backendName ?: "尚未运行测试"}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text("温度状态：${state.thermalStatus}", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("RIFE 4.6 推理基准", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "使用本地生成的测试画面，不会读取或上传你的相册视频。依次测试 480p、720p、1080p；可用内存不足或温度过高时会自动跳过后续档位。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        if (state.isRunning) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Spacer(Modifier.padding(horizontal = 6.dp))
                Text(state.status, style = MaterialTheme.typography.bodyMedium)
            }
            state.modelProgressPercent?.let { progress ->
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("模型文件下载：$progress%", style = MaterialTheme.typography.labelMedium)
            }
        } else {
            Text(state.status, style = MaterialTheme.typography.bodyMedium)
        }

        state.errorMessage?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                "测试未完成：$message",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onRunBenchmark,
            enabled = !state.isRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.results.isEmpty()) "开始性能测试" else "重新测试")
        }

        if (state.results.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text("测试结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            state.results.forEach { result ->
                BenchmarkResultCard(result)
                Spacer(Modifier.height(8.dp))
            }
        }

        state.recommendation?.let { recommendation ->
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("本机档位建议", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(recommendation, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))
        Text(
            "注意：这里测的是单次 RIFE 推理吞吐，不包含视频解码、编码、读写和长时间散热表现。它不能直接等同于最终导出 FPS。首轮不自动压测 1440p/4K，避免大分辨率造成内存或 GPU 驱动崩溃。4K 120 FPS 需要另做完整长时间测试才能判断。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        OutlinedButton(
            onClick = onBack,
            enabled = !state.isRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("返回主页")
        }
    }
}

@Composable
private fun BenchmarkResultCard(result: ResolutionBenchmarkResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${result.label} · ${result.resolution}", fontWeight = FontWeight.SemiBold)
                val rate = result.inferenceFps
                Text(
                    rate?.let { "${String.format(Locale.US, "%.1f", it)} 次/秒" } ?: "未完成",
                    color = if (rate != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
            }
            result.medianMs?.let {
                Spacer(Modifier.height(4.dp))
                Text("单次推理中位数：${String.format(Locale.US, "%.1f", it)} ms", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(4.dp))
            Text(result.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatMemory(megabytes: Long): String {
    if (megabytes <= 0L) return "未知"
    return String.format(Locale.US, "%.1f GB", megabytes / 1024.0)
}
