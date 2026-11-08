package com.videoforge.ai.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.content.Intent
import android.media.MediaCodecList
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.videoforge.ai.R
import com.videoforge.ai.benchmark.DeviceBenchmarkViewModel
import com.videoforge.ai.benchmark.DeviceBenchmarkUiState
import com.videoforge.ai.viewmodel.HistoryItem
import com.videoforge.ai.viewmodel.MainTab
import com.videoforge.ai.viewmodel.ProcessingStage
import com.videoforge.ai.viewmodel.SelectedVideoInfo
import com.videoforge.ai.viewmodel.UserSettings
import com.videoforge.ai.viewmodel.VideoCodec
import com.videoforge.ai.viewmodel.VideoContainer
import com.videoforge.ai.viewmodel.VideoOutputResolution
import com.videoforge.ai.viewmodel.VideoPageRoute
import com.videoforge.ai.viewmodel.VideoProcessRequest
import com.videoforge.ai.viewmodel.VideoProcessUiState
import com.videoforge.ai.viewmodel.VideoProcessViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val Night = Color(0xFF0F172A)
private val Slate = Color(0xFF1E293B)
private val Panel = Color(0xCC1E3048)
private val PanelLight = Color(0xAA2A3B55)
private val Aurora = Color(0xFF00E5FF)
private val Violet = Color(0xFF7000FF)
private val Muted = Color(0xFF94A3B8)
private val Good = Color(0xFF61F2C2)
private val Danger = Color(0xFFFF5D78)

@Composable
fun SmoothAITheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Aurora,
            onPrimary = Night,
            secondary = Violet,
            onSecondary = Color.White,
            background = Night,
            onBackground = Color.White,
            surface = Slate,
            onSurface = Color.White,
            surfaceVariant = Color(0xFF26364E),
            onSurfaceVariant = Muted,
            error = Danger
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmoothAIWorkspace(
    processViewModel: VideoProcessViewModel = viewModel(),
    benchmarkViewModel: DeviceBenchmarkViewModel,
    onVideoPicked: (Uri) -> Unit,
    onStartProcess: (VideoProcessRequest) -> Unit,
    onPauseTask: (Boolean) -> Unit,
    onCancelTask: () -> Unit
) {
    val state by processViewModel.uiState.collectAsState()
    val benchmarkState by benchmarkViewModel.uiState.collectAsState()
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> uri?.let(onVideoPicked) }
    )
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    val showNavigation = state.currentPage in setOf(
        VideoPageRoute.Home,
        VideoPageRoute.History,
        VideoPageRoute.Settings
    )

    SmoothAITheme {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                if (showNavigation) {
                    SmoothBottomNavigation(
                        selected = state.currentTab,
                        onSelected = processViewModel::selectTab
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Night,
                                0.55f to Color(0xFF17243A),
                                1f to Slate
                            )
                        )
                    )
                    .padding(innerPadding)
            ) {
                when (state.currentPage) {
                    VideoPageRoute.Home -> HomeWorkspace(
                        video = state.selectedVideo,
                        settings = state.userSettings,
                        statusMessage = state.statusMessage,
                        onPickVideo = {
                            picker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.VideoOnly
                                )
                            )
                        },
                        onNetworkPreview = processViewModel::onNetworkVideoSelected,
                        onOpenParameters = processViewModel::showParameterSheet,
                        onOpenBenchmark = processViewModel::openBenchmark
                    )

                    VideoPageRoute.History -> HistoryWorkspace(
                        history = state.historyList,
                        onOpenResult = processViewModel::openHistoryItem
                    )

                    VideoPageRoute.Settings -> SettingsWorkspace(
                        settings = state.userSettings,
                        onSettingsChanged = processViewModel::updateSettings
                    )

                    VideoPageRoute.Processing -> ProcessingWorkspace(
                        state = state,
                        onPause = {
                            val paused = !state.isPaused
                            processViewModel.setPaused(paused)
                            onPauseTask(paused)
                        },
                        onCancel = {
                            onCancelTask()
                            processViewModel.cancelCurrentTask()
                        }
                    )

                    VideoPageRoute.Result -> ResultWorkspace(
                        state = state,
                        onBackHome = processViewModel::backToHome
                    )

                    VideoPageRoute.Benchmark -> DeviceBenchmarkScreen(
                        state = benchmarkState,
                        onBack = processViewModel::backToHome,
                        onRunBenchmark = benchmarkViewModel::runBenchmark
                    )
                }
            }
        }

        if (state.isParameterSheetVisible && state.selectedVideo != null) {
            ModalBottomSheet(
                onDismissRequest = processViewModel::hideParameterSheet,
                sheetState = sheetState,
                containerColor = Color(0xFF17263D),
                contentColor = Color.White,
                dragHandle = {
                    Box(
                        Modifier
                            .padding(vertical = 12.dp)
                            .size(width = 42.dp, height = 4.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.35f))
                    )
                }
            ) {
                ParameterSheet(
                    settings = state.userSettings,
                    onSettingsChanged = processViewModel::updateSettings,
                    onStart = {
                        val request = processViewModel.createProcessRequest()
                        if (request != null) {
                            onStartProcess(request)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun SmoothBottomNavigation(
    selected: MainTab,
    onSelected: (MainTab) -> Unit
) {
    val items = listOf(
        Triple(MainTab.HOME, Icons.Default.Home, "主页"),
        Triple(MainTab.HISTORY, Icons.Default.History, "记录"),
        Triple(MainTab.SETTINGS, Icons.Default.Settings, "设置")
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 18.dp, end = 18.dp, bottom = 10.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xEE1B2C43), Color(0xEE26334D))))
            .border(1.dp, Aurora.copy(alpha = 0.24f), RoundedCornerShape(32.dp))
    ) {
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
        ) {
            items.forEach { (tab, icon, label) ->
                val active = selected == tab
                NavigationBarItem(
                    selected = active,
                    onClick = { onSelected(tab) },
                    icon = {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = if (active) Aurora else Muted,
                            modifier = Modifier.size(22.dp)
                        )
                    },
                    label = {
                        Text(
                            label,
                            color = if (active) Aurora else Muted,
                            fontSize = 11.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                        indicatorColor = Aurora.copy(alpha = 0.14f)
                    )
                )
            }
        }
    }
}

@Composable
private fun HomeWorkspace(
    video: SelectedVideoInfo?,
    settings: UserSettings,
    statusMessage: String?,
    onPickVideo: () -> Unit,
    onNetworkPreview: (String) -> Unit,
    onOpenParameters: () -> Unit,
    onOpenBenchmark: () -> Unit
) {
    var networkUrl by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(Aurora, Violet))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Night)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("SmoothAI", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("丝滑助手  ·  AI VIDEO STUDIO", fontSize = 10.sp, color = Muted, letterSpacing = 1.3.sp)
            }
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(Color(0x3327DDF5))
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text("RIFE 4.6", color = Aurora, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("让画面，", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Light)
                Text("流畅到每一帧。", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
            }
            Text("01 / STUDIO", color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(18.dp))

        GlassPanel {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(if (video == null) "视频预览" else "正在预览", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(if (video?.isNetworkSource == true) "ONLINE SOURCE" else "LOCAL SOURCE", color = Aurora, fontSize = 9.sp)
            }
            Spacer(Modifier.height(12.dp))
            if (video == null) {
                EmptyVideoPreview(Modifier.fillMaxWidth())
            } else {
                SmoothVideoPlayer(video.uri, Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VideoFile, contentDescription = null, tint = Aurora)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            video.fileName,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "${video.resolution}  ·  ${formatDuration(video.durationMs)}",
                            color = Muted,
                            fontSize = 11.sp
                        )
                    }
                    IconButton(onClick = onPickVideo) {
                        Icon(Icons.Default.Refresh, contentDescription = "更换视频", tint = Aurora)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text("SOURCE FRAME RATE", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
                        Text(
                            video.originalFrameRate?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
                            color = Aurora,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text("FPS", color = Aurora, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onPickVideo,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Aurora, contentColor = Night)
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text(if (video == null) "选择视频" else "更换视频", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onOpenBenchmark,
                modifier = Modifier.height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("性能测试", color = Aurora)
            }
        }

        Spacer(Modifier.height(14.dp))
        GlassPanel {
            Text("网络视频预览", color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("粘贴可直接播放的 MP4 / HLS 地址。需要公网可访问。", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            TextField(
                value = networkUrl,
                onValueChange = { networkUrl = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("https://example.com/video.m3u8", color = Muted) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0x6625364D),
                    unfocusedContainerColor = Color(0x6625364D),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedIndicatorColor = Aurora,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Aurora
                ),
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onNetworkPreview(networkUrl) },
                modifier = Modifier.fillMaxWidth(),
                enabled = networkUrl.startsWith("http://", true) || networkUrl.startsWith("https://", true)
            ) {
                Text("载入网络视频")
            }
        }

        statusMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Danger, fontSize = 12.sp)
        }

        if (video != null) {
            Spacer(Modifier.height(16.dp))
            GlassPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("当前输出方案", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
                        Text(
                            "${settings.targetFps} FPS  ·  ${settings.targetResolution.title}",
                            color = Color.White,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "${settings.videoCodec.title}  /  ${settings.container.title}",
                            color = Aurora,
                            fontSize = 11.sp
                        )
                    }
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Violet, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onOpenParameters,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrushlessAurora)
                ) {
                    Text("配置参数并开始", color = Night, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun EmptyVideoPreview(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val pulse by transition.animateFloat(
        initialValue = 0.20f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF111D31)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                color = Aurora.copy(alpha = pulse * 0.18f),
                radius = size.minDimension * 0.42f,
                center = androidx.compose.ui.geometry.Offset(size.width * 0.22f, size.height * 0.45f)
            )
            drawCircle(
                color = Violet.copy(alpha = pulse * 0.16f),
                radius = size.minDimension * 0.38f,
                center = androidx.compose.ui.geometry.Offset(size.width * 0.80f, size.height * 0.65f)
            )
            repeat(24) { index ->
                val x = (index * 73 % 997) / 997f * size.width
                val y = (index * 139 % 991) / 991f * size.height
                drawCircle(Color.White.copy(alpha = 0.12f), radius = 1.2.dp.toPx(), center = androidx.compose.ui.geometry.Offset(x, y))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Movie, contentDescription = null, tint = Aurora, modifier = Modifier.size(42.dp))
            Spacer(Modifier.height(8.dp))
            Text("选择一个视频开始", color = Color.White, fontWeight = FontWeight.SemiBold)
            Text("支持系统相册选择器与网络直链预览", color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun SmoothVideoPlayer(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(16.dp)),
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                this.player = player
                useController = true
                controllerShowTimeoutMs = 3500
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            }
        },
        update = { it.player = player }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParameterSheet(
    settings: UserSettings,
    onSettingsChanged: (UserSettings) -> Unit,
    onStart: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
            .padding(bottom = 28.dp)
    ) {
        Text("输出参数", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("根据设备能力选择，4K 与 120 FPS 会显著增加负载。", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("目标帧率", color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("${settings.targetFps} FPS", color = Aurora, fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = settings.targetFps.toFloat(),
            onValueChange = { raw ->
                val snapped = (raw / 30f).roundToInt().times(30).coerceIn(30, 120)
                onSettingsChanged(settings.copy(targetFps = snapped))
            },
            valueRange = 30f..120f,
            steps = 2,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Aurora,
                activeTrackColor = Aurora,
                inactiveTrackColor = Color(0xFF40516B)
            )
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(30, 60, 90, 120).forEach {
                Text("${it}", color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }

        Spacer(Modifier.height(18.dp))
        Text("输出分辨率", color = Color.White, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        listOf(
            listOf(VideoOutputResolution.HD_720, VideoOutputResolution.FULL_HD_1080),
            listOf(VideoOutputResolution.QHD_1440, VideoOutputResolution.UHD_4K)
        ).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { resolution ->
                    val selected = settings.targetResolution == resolution
                    GlassChoiceCard(
                        title = resolution.title,
                        subtitle = if (resolution == VideoOutputResolution.UHD_4K) "高负载 · 🔥" else "保持比例",
                        selected = selected,
                        modifier = Modifier.weight(1f),
                        onClick = { onSettingsChanged(settings.copy(targetResolution = resolution)) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        Text(
            "说明：RIFE 4.6 负责 AI 插帧；分辨率调整目前为保持比例的常规缩放，AI 超分尚未接入。",
            color = Muted,
            fontSize = 10.sp
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("保留原始分辨率", color = Muted, fontSize = 12.sp)
            Switch(
                checked = settings.targetResolution == VideoOutputResolution.SOURCE,
                onCheckedChange = {
                    if (it) onSettingsChanged(settings.copy(targetResolution = VideoOutputResolution.SOURCE))
                    else onSettingsChanged(settings.copy(targetResolution = VideoOutputResolution.FULL_HD_1080))
                },
                colors = SwitchDefaults.colors(checkedTrackColor = Aurora, checkedThumbColor = Night)
            )
        }

        Spacer(Modifier.height(14.dp))
        Text("编码器", color = Color.White, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        VideoCodec.entries.forEach { codec ->
            val supported = remember(codec) { isEncoderAvailable(codec.mimeType) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = supported) {
                        onSettingsChanged(settings.copy(videoCodec = codec))
                    }
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = settings.videoCodec == codec,
                    onClick = { onSettingsChanged(settings.copy(videoCodec = codec)) },
                    enabled = supported
                )
                Column(Modifier.weight(1f)) {
                    Text(codec.title, color = if (supported) Color.White else Muted)
                    Text(
                        if (supported) "设备检测到可用编码器" else "当前设备未检测到编码器",
                        color = Muted,
                        fontSize = 10.sp
                    )
                }
                if (codec == VideoCodec.AV1) Text("NEW", color = Aurora, fontSize = 9.sp)
            }
        }

        Spacer(Modifier.height(10.dp))
        Text("封装格式", color = Color.White, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            VideoContainer.entries.forEach { container ->
                val supported = container == VideoContainer.MP4
                FilterChip(
                    selected = settings.container == container,
                    enabled = supported,
                    onClick = { onSettingsChanged(settings.copy(container = container)) },
                    label = { Text(if (supported) container.title else "MKV（待支持）") }
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("目标码率", color = Color.White)
            Spacer(Modifier.weight(1f))
            Text("${settings.bitrateMbps} Mbps", color = Aurora, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = settings.bitrateMbps.toFloat(),
            onValueChange = { onSettingsChanged(settings.copy(bitrateMbps = it.roundToInt().coerceIn(4, 80))) },
            valueRange = 4f..80f,
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Aurora, activeTrackColor = Aurora)
        )

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("保留原视频音频", color = Color.White)
                Text("输出时尝试保持原音轨", color = Muted, fontSize = 10.sp)
            }
            Switch(
                checked = settings.preserveAudio,
                onCheckedChange = { onSettingsChanged(settings.copy(preserveAudio = it)) }
            )
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Aurora, contentColor = Night)
        ) {
            Text("开始处理", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun GlassChoiceCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val borderColor = if (selected) Aurora else Color(0x335A7599)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) Color(0x3323DFF4) else Color(0x44263850))
            .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            if (selected) {
                Spacer(Modifier.weight(1f))
                Icon(Icons.Default.Check, contentDescription = null, tint = Aurora, modifier = Modifier.size(17.dp))
            }
        }
        Text(subtitle, color = if (selected) Aurora else Muted, fontSize = 10.sp)
    }
}

@Composable
private fun HistoryWorkspace(
    history: List<HistoryItem>,
    onOpenResult: (HistoryItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 22.dp)
    ) {
        Text("处理记录", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
        Text("每一次画面提升，都留有记录。", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(20.dp))

        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(112.dp)
                            .clip(CircleShape)
                            .background(Brush.radialGradient(listOf(Violet.copy(alpha = 0.35f), Aurora.copy(alpha = 0.08f), Color.Transparent))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.History, contentDescription = null, tint = Aurora, modifier = Modifier.size(48.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("还没有处理记录", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("完成一次视频处理后，会显示在这里。", color = Muted, fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(history, key = { it.id }) { item ->
                    HistoryCard(item, onClick = { onOpenResult(item) })
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(item: HistoryItem, onClick: () -> Unit) {
    GlassPanel(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VideoThumbnail(
                uri = item.thumbnailUri ?: item.outputUri,
                modifier = Modifier
                    .size(width = 86.dp, height = 64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF101B2D))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.fileName, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${item.outputFps} FPS  ·  ${item.outputResolution}", color = Aurora, fontSize = 11.sp)
                Text("${item.codec.title}  ·  ${formatDate(item.processedAt)}", color = Muted, fontSize = 10.sp)
            }
            Text(formatBytes(item.fileSizeBytes), color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}

@Composable
private fun VideoThumbnail(uri: Uri?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = if (uri == null) null else withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.getFrameAtTime(700_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (_: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "视频缩略图",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(Icons.Default.Movie, contentDescription = null, tint = Aurora, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun SettingsWorkspace(
    settings: UserSettings,
    onSettingsChanged: (UserSettings) -> Unit
) {
    val context = LocalContext.current
    val codecAvailability = remember {
        VideoCodec.entries.associateWith { isEncoderAvailable(it.mimeType) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 22.dp)
    ) {
        Text("系统与设置", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
        Text("让设备在性能与温度之间保持平衡。", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(20.dp))

        GlassPanel {
            Text("智能控制", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            SettingSwitchRow(
                title = "智能温控保护",
                subtitle = "高温时提醒降载；真正暂停需由处理引擎响应",
                checked = settings.isSafeThermal,
                onCheckedChange = { onSettingsChanged(settings.copy(isSafeThermal = it)) }
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
            SettingSwitchRow(
                title = "自动清理垃圾碎片",
                subtitle = "仅清理确认不再使用的本任务临时文件",
                checked = settings.isAutoClean,
                onCheckedChange = { onSettingsChanged(settings.copy(isAutoClean = it)) }
            )
        }

        Spacer(Modifier.height(14.dp))
        GlassPanel {
            Text("默认输出编码", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            VideoCodec.entries.forEach { codec ->
                val supported = codecAvailability[codec] == true
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = supported) {
                            onSettingsChanged(settings.copy(videoCodec = codec))
                        }
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = settings.videoCodec == codec,
                        onClick = { onSettingsChanged(settings.copy(videoCodec = codec)) },
                        enabled = supported
                    )
                    Text(codec.title, color = if (supported) Color.White else Muted, modifier = Modifier.weight(1f))
                    Text(if (supported) "可用" else "不支持", color = if (supported) Good else Muted, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("封装格式", color = Muted, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VideoContainer.entries.forEach { container ->
                    FilterChip(
                        selected = settings.container == container,
                        onClick = { onSettingsChanged(settings.copy(container = container)) },
                        label = { Text(container.title) }
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        RepositoryCard(
            onOpenRepository = { openExternal(context, "https://github.com/sxsbsxs/VideoForge-AI") },
            onCheckUpdate = { openExternal(context, "https://github.com/sxsbsxs/VideoForge-AI/releases") },
            onGetMoreModels = { openExternal(context, "https://github.com/nihui/rife-ncnn-vulkan/tree/master/models") }
        )

        Spacer(Modifier.height(18.dp))
        Text(
            "SMOOTHAI ENGINE  ·  OPEN SOURCE  ·  POWERED BY GITHUB",
            modifier = Modifier.align(Alignment.CenterHorizontally),
            color = Muted,
            fontSize = 9.sp,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Muted, fontSize = 10.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Night,
                checkedTrackColor = Aurora,
                uncheckedThumbColor = Muted
            )
        )
    }
}

@Composable
private fun RepositoryCard(
    onOpenRepository: () -> Unit,
    onCheckUpdate: () -> Unit,
    onGetMoreModels: () -> Unit
) {
    GlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(54.dp).clip(RoundedCornerShape(17.dp)).background(Color(0xFF253750)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_github),
                    contentDescription = "GitHub",
                    modifier = Modifier.size(34.dp)
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text("开源仓库与模型", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("当前模型：rife-v4.6", color = Aurora, fontSize = 11.sp)
                Text("VideoForge-AI · MIT", color = Muted, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onOpenRepository, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Code, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("打开 GitHub 仓库")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onCheckUpdate,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Aurora, contentColor = Night)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(5.dp))
                Text("检查更新")
            }
            OutlinedButton(onClick = onGetMoreModels, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.CloudDownload, contentDescription = null)
                Spacer(Modifier.width(5.dp))
                Text("获取更多模型")
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Powered by SmoothAI Engine (GitHub)", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun ProcessingWorkspace(
    state: VideoProcessUiState,
    onPause: () -> Unit,
    onCancel: () -> Unit
) {
    val progress = state.progressData.progressPercent.coerceIn(0f, 100f)
    val pulseTransition = rememberInfiniteTransition(label = "progressGlow")
    val glow by pulseTransition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.34f,
        animationSpec = infiniteRepeatable(tween(1300), RepeatMode.Reverse),
        label = "glow"
    )
    var cancelSlider by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("AI PROCESSING", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
        state.statusMessage?.let { message ->
            Spacer(Modifier.height(12.dp))
            GlassPanel {
                Text(message, color = Color(0xFFFFD18A), fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(22.dp))
        Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 14.dp.toPx()
                val topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2)
                val circleSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                drawCircle(Aurora.copy(alpha = glow), radius = size.minDimension * 0.46f)
                drawArc(Color(0xFF34445E), -90f, 360f, false, topLeft, circleSize, style = Stroke(stroke, cap = StrokeCap.Round))
                drawArc(
                    Brush.sweepGradient(listOf(Aurora, Violet, Aurora)),
                    -90f,
                    360f * progress / 100f,
                    false,
                    topLeft,
                    circleSize,
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${progress.toInt()}%", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(state.progressData.estimatedRemainingTime, color = Muted, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(26.dp))
        GlassPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Thermostat, contentDescription = null, tint = if (state.progressData.isOverheated) Danger else Aurora)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("设备温度", color = Muted, fontSize = 10.sp)
                    Text(
                        state.progressData.currentTemperatureC?.let { String.format(Locale.US, "%.1f °C", it) } ?: "系统未提供",
                        color = if (state.progressData.isOverheated) Danger else Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(state.progressData.currentStage.name, color = Aurora, fontSize = 10.sp)
            }
        }
        if (state.progressData.isOverheated) {
            Spacer(Modifier.height(12.dp))
            Text(
                "温度较高。建议暂停任务并让设备冷却；自动暂停需等待视频处理引擎接入。",
                color = Danger,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f)) {
                Icon(if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (state.isPaused) "继续" else "暂停")
            }
        }
        Text("滑动到底部取消任务", color = Muted, fontSize = 10.sp)
        Slider(
            value = cancelSlider,
            onValueChange = { cancelSlider = it },
            onValueChangeFinished = {
                if (cancelSlider >= 0.92f) onCancel()
                cancelSlider = 0f
            },
            valueRange = 0f..1f,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Danger,
                activeTrackColor = Danger,
                inactiveTrackColor = Color(0xFF40516B)
            )
        )
    }
}

@Composable
private fun ResultWorkspace(
    state: VideoProcessUiState,
    onBackHome: () -> Unit
) {
    val settings = state.userSettings
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Aurora, Violet))),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Night, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text("处理完成", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("视频已经准备好进行对比预览。", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(18.dp))

        GlassPanel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ResultMetric("${settings.targetFps}", "FPS")
                ResultMetric(settings.targetResolution.title, "RESOLUTION")
                ResultMetric(formatBytes(state.resultFileSizeBytes), "OUTPUT SIZE")
            }
            Text("${settings.videoCodec.title}  ·  ${settings.container.title}", color = Aurora, fontSize = 11.sp)
            if (state.cleanedTempBytes > 0L) {
                Text("已清理 ${formatBytes(state.cleanedTempBytes)} 临时中转文件", color = Good, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(18.dp))
        if (state.selectedVideo != null && state.processedOutputUri != null) {
            VideoComparisonPlayer(
                originalUri = state.selectedVideo.uri,
                processedUri = state.processedOutputUri
            )
        } else {
            GlassPanel {
                Text("原视频 / AI 结果对比", color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "双播放器同步对比模块已就位。接通实际视频导出管线后，这里会同时播放原视频与处理后视频，并共享播放 / 暂停、进度和倍速。",
                    color = Muted,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ComparisonPlaceholder("原视频", Modifier.weight(1f))
                    ComparisonPlaceholder("AI 结果", Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onBackHome,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Aurora, contentColor = Night)
        ) {
            Text("返回主页", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ResultMetric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Aurora, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(label, color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun ComparisonPlaceholder(label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF101B2D))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Movie, contentDescription = null, tint = Aurora, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, fontSize = 11.sp)
        Text("等待视频", color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun VideoComparisonPlayer(
    originalUri: Uri,
    processedUri: Uri
) {
    val context = LocalContext.current
    val originalPlayer = remember(originalUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(originalUri))
            prepare()
            playWhenReady = false
        }
    }
    val processedPlayer = remember(processedUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(processedUri))
            prepare()
            playWhenReady = false
        }
    }

    DisposableEffect(originalPlayer, processedPlayer) {
        onDispose {
            originalPlayer.release()
            processedPlayer.release()
        }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var isSeeking by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(1L) }
    var originalPaneWeight by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(originalPlayer, processedPlayer, isPlaying) {
        while (true) {
            val originalDuration = originalPlayer.duration
            val outputDuration = processedPlayer.duration
            duration = when {
                originalDuration > 0L && outputDuration > 0L -> minOf(originalDuration, outputDuration)
                originalDuration > 0L -> originalDuration
                outputDuration > 0L -> outputDuration
                else -> 1L
            }
            val masterPosition = originalPlayer.currentPosition.coerceIn(0L, duration)
            if (!isSeeking) currentPosition = masterPosition

            if (kotlin.math.abs(processedPlayer.currentPosition - masterPosition) > 120L &&
                processedPlayer.isCurrentMediaItemSeekable && !isSeeking
            ) {
                processedPlayer.seekTo(masterPosition)
            }
            originalPlayer.playWhenReady = isPlaying
            processedPlayer.playWhenReady = isPlaying
            delay(if (isPlaying) 120L else 350L)
        }
    }

    GlassPanel {
        Text("原视频 / AI 结果同步对比", color = Color.White, fontWeight = FontWeight.SemiBold)
        Text(
            "两路视频共享播放、暂停、进度与倍速；拖动下方比例条可调整左右画面宽度。",
            color = Muted,
            fontSize = 10.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ComparisonPlayerTile(
                "原视频",
                originalPlayer,
                Modifier.weight(originalPaneWeight)
            )
            ComparisonPlayerTile(
                "AI 结果",
                processedPlayer,
                Modifier.weight(2f - originalPaneWeight)
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("原片", color = Aurora, fontSize = 10.sp)
            Slider(
                value = originalPaneWeight,
                onValueChange = { originalPaneWeight = it },
                valueRange = 0.65f..1.35f,
                modifier = Modifier.weight(1f),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = Aurora,
                    activeTrackColor = Aurora,
                    inactiveTrackColor = Color(0xFF40516B)
                )
            )
            Text("结果", color = Violet, fontSize = 10.sp)
        }
        Slider(
            value = currentPosition.toFloat().coerceIn(0f, duration.toFloat()),
            onValueChange = {
                isSeeking = true
                currentPosition = it.toLong()
            },
            onValueChangeFinished = {
                val target = currentPosition.coerceIn(0L, duration)
                originalPlayer.seekTo(target)
                processedPlayer.seekTo(target)
                isSeeking = false
            },
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Aurora,
                activeTrackColor = Aurora,
                inactiveTrackColor = Color(0xFF40516B)
            )
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(currentPosition), color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text(formatTime(duration), color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    isPlaying = !isPlaying
                    originalPlayer.playWhenReady = isPlaying
                    processedPlayer.playWhenReady = isPlaying
                }
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Aurora
                )
            }
            Text(if (isPlaying) "同步暂停" else "同步播放", color = Color.White, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            listOf(0.5f, 1f, 1.5f, 2f).forEach { rate ->
                FilterChip(
                    selected = speed == rate,
                    onClick = {
                        speed = rate
                        val parameters = PlaybackParameters(rate)
                        originalPlayer.playbackParameters = parameters
                        processedPlayer.playbackParameters = parameters
                    },
                    label = { Text("${rate}x") }
                )
            }
        }
    }
}

@Composable
private fun ComparisonPlayerTile(
    label: String,
    player: ExoPlayer,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(label, color = Aurora, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        AndroidView(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            update = { it.player = player }
        )
    }
}

@Composable
private fun GlassPanel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(Panel, PanelLight)))
            .border(1.dp, Brush.linearGradient(listOf(Aurora.copy(alpha = 0.30f), Violet.copy(alpha = 0.22f))), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        content = content
    )
}

private val BrushlessAurora = Color(0xFF00DCEC)

private fun isEncoderAvailable(mimeType: String): Boolean {
    return runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
        }
    }.getOrDefault(false)
}

private fun openExternal(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "--:--"
    val totalSeconds = durationMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = totalSeconds % 3600L / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

private fun formatTime(timeMs: Long): String {
    val seconds = (timeMs / 1000L).coerceAtLeast(0L)
    return String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L)
}

private fun formatDate(timestamp: Long): String {
    return runCatching {
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }.getOrDefault("--")
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "—"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) String.format(Locale.US, "%.2f GB", mb / 1024.0)
    else String.format(Locale.US, "%.1f MB", mb)
}
