package com.videoforge.ai

import android.content.Intent
import.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VideoForgeApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoForgeApp() {
    var source by remember { mutableStateOf<Uri?>(null) }
    var fps by remember { mutableStateOf("60 FPS") }
    var resolution by remember { mutableStateOf("1080p") }
    var segment by remember { mutableStateOf("5 秒") }
    var cleanup by remember { mutableStateOf("每段完成后清理") }
    var aiMode by remember { mutableStateOf(true) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        source = uri
    }

    MaterialTheme(colorScheme = lightColorScheme()) {
        Scaffold(topBar = {
            TopAppBar(title = { Text("VideoForge AI") }, actions = {
                IconButton(onClick = { }) { Icon(Icons.Default.Settings, contentDescription = "设置") }
            })
        }) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Text("AI 视频增强", style = MaterialTheme.typography.headlineMedium)
                Text("选择视频，设置输出规格，然后创建处理任务。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedCard(Modifier.fillMaxWidth(), onClick = { picker.launch(arrayOf("video/*")) }) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VideoLibrary, null, Modifier.size(32.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (source == null) "选择源视频" else "已选择视频")
                            Text(source?.lastPathSegment ?: "支持常见视频格式", style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Default.ChevronRight, null)
                    }
                }
                Text("输出设置", style = MaterialTheme.typography.titleLarge)
                SettingDropdown("输出帧率", fps, listOf("原帧率", "30 FPS", "60 FPS", "90 FPS", "120 FPS")) { fps = it }
                SettingDropdown("输出分辨率", resolution, listOf("原分辨率", "720p", "1080p", "1440p", "2K", "4K (3840×2160)")) { resolution = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("RIFE AI 插帧", style = MaterialTheme.typography.titleMedium)
                        Text("通过 AI 生成中间帧", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = aiMode, onCheckedChange = { aiMode = it })
                }
                HorizontalDivider()
                Text("任务与缓存", style = MaterialTheme.typography.titleLarge)
                SettingDropdown("分段长度", segment, listOf("1 秒", "2 秒", "5 秒", "10 秒", "30 秒")) { segment = it }
                SettingDropdown("中间帧清理", cleanup, listOf("每段完成后清理", "全部完成后清理", "保留中间帧")) { cleanup = it }
                Button(
                    onClick = { },
                    enabled = source != null,
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                ) {
                    Icon(Icons.Default.AutoAwesome, null)
                    Spacer(Modifier.width(8.dp))
                    Text("创建增强任务")
                }
                Text(
                    "开发预览 0.1.0 · 当前版本为界面与工程骨架，AI 推理和视频编码引擎尚未接入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingDropdown(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}
