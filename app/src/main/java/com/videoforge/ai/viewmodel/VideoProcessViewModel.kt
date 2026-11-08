package com.videoforge.ai.viewmodel

import android.net.Uri
import android.app.Application
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import androidx.lifecycle.AndroidViewModel
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

enum class ProcessingEngine(val title: String, val subtitle: String) {
    RIFE_AI("RIFE 4.6 AI", "神经网络光流插帧 · Vulkan / CPU"),
    FFMPEG("FFmpeg", "运动估算插帧 · 转码 / 封装 / 滤镜")
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
    val preserveAudio: Boolean = true,
    val processingEngine: ProcessingEngine = ProcessingEngine.RIFE_AI
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
    val preserveAudio: Boolean,
    val processingEngine: ProcessingEngine
)

data class HistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val thumbnailUri: Uri? = null,
    val fileName: String,
    val originalUri: Uri? = null,
    val processedAt: Long = System.currentTimeMillis(),
    val outputFps: Int,
    val outputResolution: String,
    val codec: VideoCodec,
    val fileSizeBytes: Long,
    val outputUri: Uri? = null,
    val processingEngine: ProcessingEngine = ProcessingEngine.RIFE_AI,
    val container: VideoContainer = VideoContainer.MP4
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

class VideoProcessViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("smoothai_persistent_state", Context.MODE_PRIVATE)
    private val restored = restorePersistentState()
    private val _uiState = MutableStateFlow(
        VideoProcessUiState(
            userSettings = restored.first,
            historyList = restored.second
        )
    )
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
        persistSettings(settings)
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
            preserveAudio = settings.preserveAudio,
            processingEngine = settings.processingEngine
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
            originalUri = source.uri,
            outputFps = settings.targetFps,
            outputResolution = settings.targetResolution.title,
            codec = settings.videoCodec,
            fileSizeBytes = fileSizeBytes,
            outputUri = outputUri,
            processingEngine = settings.processingEngine,
            container = settings.container
        )
        val newHistory = listOf(item) + current.historyList
        _uiState.update {
            it.copy(
                currentPage = VideoPageRoute.Result,
                processedOutputUri = outputUri,
                resultFileSizeBytes = fileSizeBytes,
                cleanedTempBytes = cleanedTempBytes,
                historyList = newHistory,
                progressData = it.progressData.copy(
                    progressPercent = 100f,
                    estimatedRemainingTime = "已完成",
                    currentStage = ProcessingStage.Completed
                )
            )
        }
        persistHistory(newHistory)
    }

    fun openHistoryItem(item: HistoryItem) {
        val sourceUri = item.originalUri ?: item.thumbnailUri
        val source = sourceUri?.let {
            SelectedVideoInfo(
                uri = it,
                fileName = item.fileName,
                durationMs = 0L,
                width = 0,
                height = 0
            )
        }
        val resolution = when (item.outputResolution.uppercase()) {
            "720P", "720P" -> VideoOutputResolution.HD_720
            "1080P" -> VideoOutputResolution.FULL_HD_1080
            "2K", "1440P" -> VideoOutputResolution.QHD_1440
            "4K", "2160P" -> VideoOutputResolution.UHD_4K
            else -> VideoOutputResolution.SOURCE
        }
        _uiState.update { current ->
            current.copy(
                currentTab = MainTab.HOME,
                currentPage = VideoPageRoute.Result,
                selectedVideo = source ?: current.selectedVideo,
                processedOutputUri = item.outputUri,
                resultFileSizeBytes = item.fileSizeBytes,
                userSettings = current.userSettings.copy(
                    targetFps = item.outputFps,
                    targetResolution = resolution,
                    videoCodec = item.codec,
                    processingEngine = item.processingEngine,
                    container = item.container
                )
            )
        }
    }

    fun addHistoryItem(item: HistoryItem) {
        val updated = listOf(item) + _uiState.value.historyList
        _uiState.update { it.copy(historyList = updated) }
        persistHistory(updated)
    }

    fun removeHistoryItem(item: HistoryItem) {
        val updated = _uiState.value.historyList.filterNot { it.id == item.id }
        _uiState.update { it.copy(historyList = updated) }
        persistHistory(updated)
    }

    fun clearHistory() {
        _uiState.update { it.copy(historyList = emptyList()) }
        persistHistory(emptyList())
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

    fun processingFailed(message: String) {
        _uiState.update {
            it.copy(
                currentTab = MainTab.HOME,
                currentPage = VideoPageRoute.Home,
                isParameterSheetVisible = false,
                isPaused = false,
                statusMessage = message
            )
        }
    }

    fun setStatusMessage(message: String?) {
        _uiState.update { it.copy(statusMessage = message) }
    }

    private fun persistSettings(settings: UserSettings) {
        preferences.edit()
            .putInt("fps", settings.targetFps)
            .putString("resolution", settings.targetResolution.name)
            .putString("codec", settings.videoCodec.name)
            .putString("container", settings.container.name)
            .putInt("bitrate", settings.bitrateMbps)
            .putBoolean("safeThermal", settings.isSafeThermal)
            .putBoolean("autoClean", settings.isAutoClean)
            .putBoolean("preserveAudio", settings.preserveAudio)
            .putString("engine", settings.processingEngine.name)
            .apply()
    }

    private fun persistHistory(items: List<HistoryItem>) {
        val array = JSONArray()
        items.take(200).forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("thumbnailUri", item.thumbnailUri?.toString() ?: JSONObject.NULL)
                    put("fileName", item.fileName)
                    put("originalUri", item.originalUri?.toString() ?: JSONObject.NULL)
                    put("processedAt", item.processedAt)
                    put("fps", item.outputFps)
                    put("resolution", item.outputResolution)
                    put("codec", item.codec.name)
                    put("size", item.fileSizeBytes)
                    put("outputUri", item.outputUri?.toString() ?: JSONObject.NULL)
                    put("engine", item.processingEngine.name)
                    put("container", item.container.name)
                }
            )
        }
        preferences.edit().putString("history", array.toString()).apply()
    }

    private fun restorePersistentState(): Pair<UserSettings, List<HistoryItem>> {
        val settings = runCatching {
            UserSettings(
                targetFps = preferences.getInt("fps", 60).coerceIn(30, 120),
                targetResolution = enumOrDefault(
                    preferences.getString("resolution", null), VideoOutputResolution.FULL_HD_1080
                ),
                videoCodec = enumOrDefault(preferences.getString("codec", null), VideoCodec.H265),
                container = enumOrDefault(preferences.getString("container", null), VideoContainer.MP4),
                bitrateMbps = preferences.getInt("bitrate", 16).coerceIn(2, 100),
                isSafeThermal = preferences.getBoolean("safeThermal", true),
                isAutoClean = preferences.getBoolean("autoClean", true),
                preserveAudio = preferences.getBoolean("preserveAudio", true),
                processingEngine = enumOrDefault(
                    preferences.getString("engine", null), ProcessingEngine.RIFE_AI
                )
            )
        }.getOrDefault(UserSettings())
        val history = runCatching {
            val json = JSONArray(preferences.getString("history", "[]").orEmpty())
            buildList {
                for (index in 0 until json.length()) {
                    val row = json.getJSONObject(index)
                    add(
                        HistoryItem(
                            id = row.optString("id").ifBlank { UUID.randomUUID().toString() },
                            thumbnailUri = row.optNullableUri("thumbnailUri"),
                            fileName = row.optString("fileName", "视频"),
                            originalUri = row.optNullableUri("originalUri"),
                            processedAt = row.optLong("processedAt", System.currentTimeMillis()),
                            outputFps = row.optInt("fps", 60),
                            outputResolution = row.optString("resolution", "原始"),
                            codec = enumOrDefault(row.optString("codec", null), VideoCodec.H264),
                            fileSizeBytes = row.optLong("size", 0L),
                            outputUri = row.optNullableUri("outputUri"),
                            processingEngine = enumOrDefault(
                                row.optString("engine", null), ProcessingEngine.RIFE_AI
                            ),
                            container = enumOrDefault(
                                row.optString("container", null), VideoContainer.MP4
                            )
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
        return settings to history
    }

    private fun JSONObject.optNullableUri(key: String): android.net.Uri? {
        val value = optString(key, "").takeIf { it.isNotBlank() && it != "null" } ?: return null
        return runCatching { android.net.Uri.parse(value) }.getOrNull()
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String?, fallback: T): T {
        return runCatching { enumValueOf<T>(value ?: "") }.getOrDefault(fallback)
    }
}
