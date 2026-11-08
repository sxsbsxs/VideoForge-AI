package com.videoforge.ai

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Observer
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.videoforge.ai.benchmark.DeviceBenchmarkViewModel
import com.videoforge.ai.processing.ProcessingRuntime
import com.videoforge.ai.processing.VideoProcessingWorker
import com.videoforge.ai.nativeengine.Rife46ModelInstaller
import com.videoforge.ai.ui.VideoSmoothAIApp
import com.videoforge.ai.ui.VideoSmoothAITheme
import com.videoforge.ai.viewmodel.ProcessingEngine
import com.videoforge.ai.viewmodel.ProcessingStage
import com.videoforge.ai.viewmodel.SelectedVideoInfo
import com.videoforge.ai.viewmodel.VideoProcessRequest
import com.videoforge.ai.viewmodel.VideoProcessViewModel
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val processViewModel: VideoProcessViewModel by viewModels()
    private val benchmarkViewModel: DeviceBenchmarkViewModel by viewModels()
    private val workManager by lazy { WorkManager.getInstance(applicationContext) }
    private val taskPreferences by lazy {
        getSharedPreferences("smoothai_active_task", MODE_PRIVATE)
    }
    private var activeWorkId: UUID? = null
    private var observedWork: LiveData<WorkInfo?>? = null
    private var workObserver: Observer<WorkInfo?>? = null
    private var preparationJob: Job? = null
    private var batteryReceiverRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            val rawTemperature = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            processViewModel.updateTemperature(
                if (rawTemperature > 0) rawTemperature / 10f else null
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(15, 23, 42)
        window.navigationBarColor = android.graphics.Color.rgb(15, 23, 42)

        taskPreferences.getString(VideoProcessingWorker.PREF_WORK_ID, null)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?.let { id ->
                activeWorkId = id
                processViewModel.restoreActiveProcessing()
                observeProcessingWork(id)
            }

        setContent {
            VideoSmoothAITheme {
                VideoSmoothAIApp(
                    processViewModel = processViewModel,
                    onVideoPicked = ::loadVideoMetadata,
                    onStartProcess = ::onStartProcess,
                    onPauseTask = ::onPauseTask,
                    onCancelTask = ::onCancelTask,
                    onShareOutput = ::shareOutput,
                    benchmarkViewModel = benchmarkViewModel
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!batteryReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            batteryReceiverRegistered = true
        }
    }

    override fun onStop() {
        if (batteryReceiverRegistered) {
            runCatching { unregisterReceiver(batteryReceiver) }
            batteryReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        workObserver?.let { observer -> observedWork?.removeObserver(observer) }
        workObserver = null
        observedWork = null
        super.onDestroy()
    }

    private fun loadVideoMetadata(uri: Uri) {
        lifecycleScope.launch {
            val info = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { readVideoInfo(uri) }
            if (info != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                processViewModel.onVideoSelected(info)
            } else {
                processViewModel.setStatusMessage("无法读取该视频的元数据，请尝试选择本地视频文件。")
            }
        }
    }

    private fun readVideoInfo(uri: Uri): SelectedVideoInfo? {
        val displayName = runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(
                    cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                ) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "未命名视频"

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val duration = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            var width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0
            var height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                val originalWidth = width
                width = height
                height = originalWidth
            }
            val frameRate = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE
            )?.toFloatOrNull()

            SelectedVideoInfo(
                uri = uri,
                fileName = displayName,
                durationMs = duration,
                width = width,
                height = height,
                originalFrameRate = frameRate
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun onStartProcess(request: VideoProcessRequest) {
        preparationJob?.cancel()
        ProcessingRuntime.paused = false
        processViewModel.setPaused(false)
        processViewModel.setStatusMessage("正在检查已有任务…")

        preparationJob = lifecycleScope.launch {
            try {
                // Never replace an unfinished conversion just because the user pressed
                // the home-page start button again. Reattach to that exact WorkManager job.
                val existingWork = withContext(Dispatchers.IO) {
                    workManager.getWorkInfosForUniqueWork(VideoProcessingWorker.TAG)
                        .get()
                        .firstOrNull { !it.state.isFinished }
                }

                if (existingWork != null) {
                    attachToExistingWork(existingWork)
                    processViewModel.showProcessing()
                    processViewModel.setStatusMessage("检测到已有任务，已恢复原任务进度，没有重新开始。")
                    return@launch
                }

                // The previous unique work is finished; remove only its observer/reference.
                if (activeWorkId != null) clearObservedWork()

                val modelInstalled = if (request.processingEngine == ProcessingEngine.RIFE_AI) {
                    withContext(Dispatchers.IO) {
                        Rife46ModelInstaller.isInstalled(applicationContext)
                    }
                } else true

                val requiresNetwork = request.source.isNetworkSource ||
                    (request.processingEngine == ProcessingEngine.RIFE_AI && !modelInstalled)
                val constraints = Constraints.Builder().apply {
                    if (requiresNetwork) setRequiredNetworkType(NetworkType.CONNECTED)
                }.build()

                val work = OneTimeWorkRequestBuilder<VideoProcessingWorker>()
                    .setInputData(VideoProcessingWorker.encodeRequest(request))
                    .setConstraints(constraints)
                    .addTag(VideoProcessingWorker.TAG)
                    .build()

                // KEEP is a second line of defence against accidental replacement.
                val operation = workManager.enqueueUniqueWork(
                    VideoProcessingWorker.TAG,
                    ExistingWorkPolicy.KEEP,
                    work
                )
                withContext(Dispatchers.IO) { operation.result.get() }

                // Resolve the effective unique work ID. If another start raced us,
                // this will point to the work that WorkManager actually kept.
                val effectiveWork = withContext(Dispatchers.IO) {
                    workManager.getWorkInfosForUniqueWork(VideoProcessingWorker.TAG)
                        .get()
                        .firstOrNull { !it.state.isFinished }
                }
                if (effectiveWork != null) {
                    attachToExistingWork(effectiveWork)
                } else {
                    activeWorkId = work.id
                    taskPreferences.edit()
                        .putString(VideoProcessingWorker.PREF_WORK_ID, work.id.toString())
                        .apply()
                    observeProcessingWork(work.id)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                processViewModel.processingFailed(
                    "无法启动或恢复后台任务：${failure.message ?: failure.javaClass.simpleName}"
                )
            } finally {
                preparationJob = null
            }
        }
    }

    private fun attachToExistingWork(info: WorkInfo) {
        activeWorkId = info.id
        taskPreferences.edit()
            .putString(VideoProcessingWorker.PREF_WORK_ID, info.id.toString())
            .apply()
        processViewModel.attachActiveRequest(
            VideoProcessingWorker.decodeRequest(info.inputData)
        )
        observeProcessingWork(info.id)
    }

    private fun observeProcessingWork(id: UUID) {
        workObserver?.let { observer -> observedWork?.removeObserver(observer) }
        val liveData = workManager.getWorkInfoByIdLiveData(id)
        val observer = Observer<WorkInfo?> { info ->
            if (info == null) {
                // The saved UUID may be stale after an interrupted enqueue or app upgrade.
                // Recover by the stable unique-work name instead of leaving a dead progress screen.
                lifecycleScope.launch {
                    val recovered = withContext(Dispatchers.IO) {
                        workManager.getWorkInfosForUniqueWork(VideoProcessingWorker.TAG)
                            .get()
                            .firstOrNull { !it.state.isFinished }
                    }
                    if (recovered != null) {
                        attachToExistingWork(recovered)
                    } else if (activeWorkId == id) {
                        clearObservedWork()
                        processViewModel.processingFailed("没有找到正在运行的任务。请重新选择视频。")
                    }
                }
                return@Observer
            }

            if (!info.state.isFinished) {
                processViewModel.attachActiveRequest(
                    VideoProcessingWorker.decodeRequest(info.inputData)
                )
            }

            if (info.state == WorkInfo.State.RUNNING) {
                val data = info.progress
                val hasProgress = data.keyValueMap.containsKey(VideoProcessingWorker.KEY_PROGRESS)
                if (hasProgress) {
                    val stageName = data.getString(VideoProcessingWorker.KEY_STAGE)
                    val stage = runCatching {
                        enumValueOf<ProcessingStage>(stageName ?: ProcessingStage.Interpolation.name)
                    }.getOrDefault(ProcessingStage.Interpolation)
                    processViewModel.updateProgress(
                        data.getFloat(VideoProcessingWorker.KEY_PROGRESS, 0f),
                        data.getString(VideoProcessingWorker.KEY_ETA) ?: "后台正在处理",
                        stage
                    )
                }
                if (data.keyValueMap.containsKey(VideoProcessingWorker.KEY_STATUS)) {
                    processViewModel.setStatusMessage(data.getString(VideoProcessingWorker.KEY_STATUS))
                }
            }

            when (info.state) {
                WorkInfo.State.SUCCEEDED -> {
                    val outputUri = info.outputData.getString(VideoProcessingWorker.KEY_OUTPUT_URI)
                        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                    if (outputUri != null) {
                        processViewModel.finishProcessing(
                            outputUri = outputUri,
                            fileSizeBytes = info.outputData.getLong(VideoProcessingWorker.KEY_OUTPUT_SIZE, 0L),
                            cleanedTempBytes = info.outputData.getLong(VideoProcessingWorker.KEY_CLEANED_BYTES, 0L)
                        )
                    } else {
                        processViewModel.processingFailed("后台任务已结束，但没有返回有效的视频文件。")
                    }
                    clearObservedWork()
                }
                WorkInfo.State.FAILED -> {
                    val error = info.outputData.getString(VideoProcessingWorker.KEY_ERROR)
                        ?: "后台视频处理失败。"
                    processViewModel.processingFailed("处理失败：$error")
                    clearObservedWork()
                }
                WorkInfo.State.CANCELLED -> {
                    processViewModel.cancelCurrentTask()
                    clearObservedWork()
                }
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING -> Unit
            }
        }
        observedWork = liveData
        workObserver = observer
        liveData.observe(this, observer)
    }

    private fun clearObservedWork() {
        taskPreferences.edit().remove(VideoProcessingWorker.PREF_WORK_ID).apply()
        activeWorkId = null
        workObserver?.let { observer -> observedWork?.removeObserver(observer) }
        workObserver = null
        observedWork = null
    }

    private fun onPauseTask(shouldPause: Boolean) {
        if (processViewModel.uiState.value.userSettings.processingEngine == ProcessingEngine.FFMPEG) {
            processViewModel.setStatusMessage("FFmpeg 模式暂不支持安全暂停；可以取消任务后重新处理。")
            return
        }
        ProcessingRuntime.paused = shouldPause
        processViewModel.setPaused(shouldPause)
        processViewModel.setStatusMessage(if (shouldPause) "已暂停，当前帧完成。" else "继续处理…")
    }

    private fun onCancelTask() {
        preparationJob?.cancel()
        preparationJob = null
        ProcessingRuntime.paused = false
        activeWorkId?.let { workManager.cancelWorkById(it) }
        clearObservedWork()
        processViewModel.cancelCurrentTask()
    }

    private fun shareOutput(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, "分享 SmoothAI 处理结果"))
        }.onFailure {
            processViewModel.setStatusMessage("无法打开系统分享面板：${it.message ?: "未知错误"}")
        }
    }
}
