package com.videoforge.ai.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

enum class MainTab { HOME, HISTORY, SETTINGS }

enum class VideoPageRoute { Home, History, Settings, Processing, Result, Benchmark }

enum class VideoOutputResolution(val title: String) {
    SOURCE("原始"),
    HD_720("720P"),
    FULL_HD_1080("1080P"),
    QHD_1440("2K"),
    UHD_4K("4K")
}

enum class VideoCodec(val title: String, val mimeType: String) {
    H264("H.264 / AVC", "video/avc"),
    H265("H.265 / HEVC", "video/hevc"),
    AV1("AV1", "video/av01")
}

enum class VideoContainer(val title: String) {
    MP4("MP4"),
    MKV("MKV")
}

enum class ProcessingStage { Extraction, Interpolation, Encoding, Completed }

data class SelectedVideoInfo(
    val uri: Uri,
    val fileName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val originalFrameRate: Float? = null,
    val isNetworkSource: Boolean = false,
    val thumbnailUri: Uri? = null
) {
    val resolution: String
        get() = if (width > 0 && height > 0) "${width}×${height}" else "未知"
}

data class UserSettings(
    val targetFps: Int = 60,
    val targetResolution: VideoOutputResolution = VideoOutputResolution.FULL_HD_1080,
    val videoCodec: VideoCodec = VideoCodec.H265,
    val container: VideoContainer = VideoContainer.MP4,
    val bitrateMbps: Int = 16,
    val isSafeThermal: Boolean = true,
    val isAutoClean: Boolean = true,
    val preserveAudio: Boolean = true
)

data class VideoProcessRequest(
    val source: SelectedVideoInfo,
    val targetFrameRate: Int,
    val outputResolution: VideoOutputResolution,
    val videoCodec: VideoCodec,
    val container: VideoContainer,
    val bitrateMbps: Int,
    val isSafeThermal: Boolean,
    val autoCleanFragments: Boolean,
    val preserveAudio: Boolean
)

data class HistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val thumbnailUri: Uri? = null,
    val fileName: String,
    val processedAt: Long = System.currentTimeMillis(),
    val outputFps: Int,
    val outputResolution: String,
    val codec: VideoCodec,
    val fileSizeBytes: Long,
    val outputUri: Uri? = null
)

data class ProgressData(
    val progressPercent: Float = 0f,
    val currentTemperatureC: Float? = null,
    val estimatedRemainingTime: String = "等待开始",
    val currentStage: ProcessingStage = ProcessingStage.Extraction,
    val isOverheated: Boolean = false
)

data class VideoProcessUiState(
    val currentTab: MainTab = MainTab.HOME,
    val currentPage: VideoPageRoute = VideoPageRoute.Home,
    val selectedVideo: SelectedVideoInfo? = null,
    val userSettings: UserSettings = UserSettings(),
    val historyList: List<HistoryItem> = emptyList(),
    val progressData: ProgressData = ProgressData(),
    val isParameterSheetVisible: Boolean = false,
    val isPaused: Boolean = false,
    val processedOutputUri: Uri? = null,
    val resultFileSizeBytes: Long = 0L,
    val cleanedTempBytes: Long = 0L,
    val statusMessage: String? = null
) {
    val targetFrameRate: Int get() = userSettings.targetFps
    val outputResolution: VideoOutputResolution get() = userSettings.targetResolution
    val isExtremeHeatMode: Boolean get() = !userSettings.isSafeThermal
    val autoCleanFragments: Boolean get() = userSettings.isAutoClean
    val progressPercent: Float get() = progressData.progressPercent
    val estimatedRemainingTime: String get() = progressData.estimatedRemainingTime
    val currentStage: ProcessingStage get() = progressData.currentStage
    val isOverheated: Boolean get() = progressData.isOverheated
}

class VideoProcessViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(VideoProcessUiState())
    val uiState: StateFlow<VideoProcessUiState> = _uiState.asStateFlow()

    fun onVideoSelected(video: SelectedVideoInfo) {
        _uiState.update {
            it.copy(
                currentTab = MainTab.HOME,
                currentPage = VideoPageRoute.Home,
                selectedVideo = video,
                isParameterSheetVisible = true,
                statusMessage = null
            )
        }
    }

    fun onNetworkVideoSelected(rawUrl: String) {
        val cleanUrl = rawUrl.trim()
        if (!cleanUrl.startsWith("https://", true) && !cleanUrl.startsWith("http://", true)) {
            _uiState.update { it.copy(statusMessage = "请输入以 http:// 或 https:// 开头的视频直链") }
            return
        }
        val uri = runCatching { Uri.parse(cleanUrl) }.getOrNull() ?: return
        if (uri.host.isNullOrBlank()) {
            _uiState.update { it.copy(statusMessage = "网络地址无效") }
            return
        }
        onVideoSelected(
            SelectedVideoInfo(
                uri = uri,
                fileName = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: uri.host.orEmpty(),
                durationMs = 0L,
                width = 0,
                height = 0,
                originalFrameRate = null,
                isNetworkSource = true
            )
        )
    }

    fun selectTab(tab: MainTab) {
        _uiState.update {
            it.copy(
                currentTab = tab,
                currentPage = when (tab) {
                    MainTab.HOME -> VideoPageRoute.Home
                    MainTab.HISTORY -> VideoPageRoute.History
                    MainTab.SETTINGS -> VideoPageRoute.Settings
                },
                isParameterSheetVisible = false
            )
        }
    }

    fun backToHome() {
        _uiState.update {
            it.copy(
                currentTab = MainTab.HOME,
                currentPage = VideoPageRoute.Home,
                isParameterSheetVisible = false
            )
        }
    }

    fun openBenchmark() {
        _uiState.update {
            it.copy(
                currentTab = MainTab.HOME,
                currentPage = VideoPageRoute.Benchmark,
                isParameterSheetVisible = false
            )
        }
    }

    fun hideParameterSheet() {
        _uiState.update { it.copy(isParameterSheetVisible = false) }
    }

    fun showParameterSheet() {
        if (_uiState.value.selectedVideo != null) {
            _uiState.update { it.copy(isParameterSheetVisible = true) }
        }
    }

    fun updateSettings(settings: UserSettings) {
        _uiState.update { it.copy(userSettings = settings) }
    }

    fun setTargetFrameRate(value: Int) {
        if (value in setOf(30, 60, 90, 120)) {
            updateSettings(_uiState.value.userSettings.copy(targetFps = value))
        }
    }

    fun setOutputResolution(value: VideoOutputResolution) {
        updateSettings(_uiState.value.userSettings.copy(targetResolution = value))
    }

    fun setExtremeHeatMode(enabled: Boolean) {
        updateSettings(_uiState.value.userSettings.copy(isSafeThermal = !enabled))
    }

    fun setAutoCleanFragments(enabled: Boolean) {
        updateSettings(_uiState.value.userSettings.copy(isAutoClean = enabled))
    }

    fun updateTemperature(celsius: Float?) {
        _uiState.update { current ->
            val warning = current.userSettings.isSafeThermal && celsius != null && celsius >= 43f
            current.copy(
                progressData = current.progressData.copy(
                    currentTemperatureC = celsius,
                    isOverheated = warning
                )
            )
        }
    }

    fun createProcessRequest(): VideoProcessRequest? {
        val current = _uiState.value
        val source = current.selectedVideo ?: return null
        val settings = current.userSettings
        val request = VideoProcessRequest(
            source = source,
            targetFrameRate = settings.targetFps,
            outputResolution = settings.targetResolution,
            videoCodec = settings.videoCodec,
            container = settings.container,
            bitrateMbps = settings.bitrateMbps,
            isSafeThermal = settings.isSafeThermal,
            autoCleanFragments = settings.isAutoClean,
            preserveAudio = settings.preserveAudio
        )
        _uiState.update {
            it.copy(
                currentTab = MainTab.HOME,
                currentPage = VideoPageRoute.Processing,
                isParameterSheetVisible = false,
                progressData = ProgressData(),
                isPaused = false,
                processedOutputUri = null,
                resultFileSizeBytes = 0L,
                cleanedTempBytes = 0L,
                statusMessage = null
            )
        }
        return request
    }

    fun updateProgress(percent: Float, remainingTime: String, stage: ProcessingStage) {
        _uiState.update { current ->
            current.copy(
                progressData = current.progressData.copy(
                    progressPercent = percent.coerceIn(0f, 100f),
                    estimatedRemainingTime = remainingTime,
                    currentStage = stage
                ),
                currentPage = if (stage == ProcessingStage.Completed) {
                    VideoPageRoute.Result
                } else current.currentPage
            )
        }
    }

    fun finishProcessing(outputUri: Uri, fileSizeBytes: Long, cleanedTempBytes: Long = 0L) {
        val current = _uiState.value
        val source = current.selectedVideo ?: return
        val settings = current.userSettings
        val item = HistoryItem(
            thumbnailUri = source.thumbnailUri ?: source.uri,
            fileName = source.fileName,
            outputFps = settings.targetFps,
            outputResolution = settings.targetResolution.title,
            codec = settings.videoCodec,
            fileSizeBytes = fileSizeBytes,
            outputUri = outputUri
        )
        _uiState.update {
            it.copy(
                currentPage = VideoPageRoute.Result,
                processedOutputUri = outputUri,
                resultFileSizeBytes = fileSizeBytes,
                cleanedTempBytes = cleanedTempBytes,
                historyList = listOf(item) + it.historyList,
                progressData = it.progressData.copy(
                    progressPercent = 100f,
                    estimatedRemainingTime = "已完成",
                    currentStage = ProcessingStage.Completed
                )
            )
        }
    }

    fun addHistoryItem(item: HistoryItem) {
        _uiState.update { it.copy(historyList = listOf(item) + it.historyList) }
    }

    fun setPaused(paused: Boolean) {
        _uiState.update { it.copy(isPaused = paused) }
    }

    fun cancelCurrentTask() {
        _uiState.update {
            it.copy(
                currentTab = MainTab.HOME,
                currentPage = VideoPageRoute.Home,
                progressData = it.progressData.copy(
                    progressPercent = 0f,
                    estimatedRemainingTime = "已取消"
                ),
                isPaused = false
            )
        }
    }

    fun setStatusMessage(message: String?) {
        _uiState.update { it.copy(statusMessage = message) }
    }
}
