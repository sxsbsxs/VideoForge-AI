package com.videoforge.ai.benchmark

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.videoforge.ai.nativeengine.Rife46Engine
import com.videoforge.ai.nativeengine.Rife46Session
import com.videoforge.ai.nativeengine.RifeComputeBackend
import com.videoforge.ai.nativeengine.Rife46ModelInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class ResolutionBenchmarkResult(
    val label: String,
    val width: Int,
    val height: Int,
    val medianMs: Double? = null,
    val inferenceFps: Double? = null,
    val note: String
) {
    val resolution: String get() = "${width}×${height}"
}

data class DeviceBenchmarkUiState(
    val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
    val chipName: String = Build.SOC_MODEL.takeIf { Build.VERSION.SDK_INT >= 31 && it.isNotBlank() }
        ?: Build.HARDWARE,
    val totalRamMb: Long = 0L,
    val availableRamMb: Long = 0L,
    val vulkanDeviceCount: Int? = null,
    val backendName: String? = null,
    val thermalStatus: String = "读取中",
    val isRunning: Boolean = false,
    val status: String = "测试会调用设备本地 RIFE 4.6 模型",
    val modelProgressPercent: Int? = null,
    val results: List<ResolutionBenchmarkResult> = emptyList(),
    val recommendation: String? = null,
    val errorMessage: String? = null
)

class DeviceBenchmarkViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(DeviceBenchmarkUiState())
    val uiState: StateFlow<DeviceBenchmarkUiState> = _uiState.asStateFlow()

    fun runBenchmark() {
        if (_uiState.value.isRunning) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRunning = true,
                    status = "正在检查设备状态…",
                    modelProgressPercent = null,
                    results = emptyList(),
                    recommendation = null,
                    errorMessage = null,
                    backendName = null,
                    vulkanDeviceCount = null
                )
            }

            try {
                refreshDeviceSnapshot()
                val thermal = readThermalStatus()
                if (thermal != null && thermal >= PowerManager.THERMAL_STATUS_SEVERE) {
                    throw IllegalStateException("设备当前温度等级较高（${thermalLabel(thermal)}），请冷却后再开始测试。")
                }

                _uiState.update { it.copy(status = "正在检查 / 下载 RIFE 4.6 模型…") }
                val modelDirectory = Rife46ModelInstaller.ensureInstalled(getApplication()) { fileName, downloaded, total ->
                    val percent = if (total > 0L) {
                        ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                    } else null
                    _uiState.update {
                        it.copy(
                            status = "模型文件：$fileName",
                            modelProgressPercent = percent
                        )
                    }
                }

                val gpuCount = withContext(Dispatchers.Default) {
                    runCatching { Rife46Engine.getVulkanDeviceCount() }.getOrNull()
                }
                _uiState.update { it.copy(vulkanDeviceCount = gpuCount) }

                val results = mutableListOf<ResolutionBenchmarkResult>()
                var stopAfterMemoryError = false
                val tests = listOf(
                    TestSize("480p", 854, 480, 450L),
                    TestSize("720p", 1280, 720, 750L),
                    TestSize("1080p", 1920, 1080, 1_400L)
                )

                _uiState.update { it.copy(status = "正在创建 RIFE 推理会话…", modelProgressPercent = null) }
                withContext(Dispatchers.Default) {
                    Rife46Engine.createSession(
                        modelDirectory = modelDirectory,
                        preferVulkan = true,
                        allowCpuFallback = true,
                        numThreads = 2
                    ).use { session ->
                        _uiState.update {
                            it.copy(backendName = when (session.backend) {
                                RifeComputeBackend.VULKAN -> "Vulkan GPU"
                                RifeComputeBackend.CPU -> "CPU 回退"
                            })
                        }

                        for (test in tests) {
                            if (stopAfterMemoryError) {
                                results += skipped(test, "前一档出现内存不足，已停止更高分辨率测试")
                                continue
                            }

                            val memory = readMemory()
                            _uiState.update {
                                it.copy(
                                    availableRamMb = memory.second,
                                    status = "正在测试 ${test.label}（${test.width}×${test.height}）…"
                                )
                            }

                            if (memory.second < test.minimumFreeRamMb) {
                                results += skipped(
                                    test,
                                    "可用内存约 ${memory.second} MB，低于安全门槛 ${test.minimumFreeRamMb} MB"
                                )
                                continue
                            }

                            val currentThermal = readThermalStatus()
                            if (currentThermal != null && currentThermal >= PowerManager.THERMAL_STATUS_SEVERE) {
                                results += skipped(test, "温度升高到 ${thermalLabel(currentThermal)}，为保护设备停止后续测试")
                                tests.dropWhile { it != test }.drop(1).forEach { results += skipped(it, "设备温度保护已停止测试") }
                                break
                            }

                            try {
                                val testResult = measureOneSize(session, test)
                                results += testResult
                            } catch (memoryError: OutOfMemoryError) {
                                results += skipped(test, "系统或 GPU 内存不足，已停止更高分辨率测试")
                                stopAfterMemoryError = true
                                System.gc()
                            } catch (error: Exception) {
                                results += skipped(test, error.message ?: "推理测试失败")
                            }

                            _uiState.update { it.copy(results = results.toList()) }
                            System.gc()
                        }
                    }
                }

                val summary = buildRecommendation(results)
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        status = "测试完成",
                        modelProgressPercent = null,
                        results = results,
                        recommendation = summary
                    )
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        status = "测试未完成",
                        modelProgressPercent = null,
                        errorMessage = error.message ?: error.javaClass.simpleName
                    )
                }
            }
        }
    }

    private fun measureOneSize(
        session: Rife46Session,
        test: TestSize
    ): ResolutionBenchmarkResult {
        val byteCountLong = test.width.toLong() * test.height.toLong() * 3L
        require(byteCountLong <= Int.MAX_VALUE) { "测试分辨率过大" }
        val byteCount = byteCountLong.toInt()

        // Synthetic RGB frames avoid importing a personal video. This measures the native
        // RIFE inference path, not video decoding, encoding, or storage throughput.
        val frame0 = ByteArray(byteCount) { index -> ((index * 13 + 37) and 0xff).toByte() }
        val frame1 = ByteArray(byteCount) { index -> ((index * 7 + 149) and 0xff).toByte() }

        session.interpolate(frame0, frame1, test.width, test.height, 0.5f).fill(0)

        val samples = LongArray(3) {
            val start = SystemClock.elapsedRealtimeNanos()
            session.interpolate(frame0, frame1, test.width, test.height, 0.5f).fill(0)
            SystemClock.elapsedRealtimeNanos() - start
        }
        val medianMs = samples.sorted()[1] / 1_000_000.0
        val inferenceFps = if (medianMs > 0.0) 1000.0 / medianMs else 0.0
        return ResolutionBenchmarkResult(
            label = test.label,
            width = test.width,
            height = test.height,
            medianMs = medianMs,
            inferenceFps = inferenceFps,
            note = "单次推理中位数；不包含解码和编码"
        )
    }

    private fun skipped(test: TestSize, reason: String) = ResolutionBenchmarkResult(
        label = test.label,
        width = test.width,
        height = test.height,
        note = reason
    )

    private fun refreshDeviceSnapshot() {
        val memory = readMemory()
        val thermal = readThermalStatus()
        _uiState.update {
            it.copy(
                deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                chipName = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf(String::isNotBlank) ?: Build.HARDWARE else Build.HARDWARE,
                totalRamMb = memory.first,
                availableRamMb = memory.second,
                thermalStatus = thermal?.let(::thermalLabel) ?: "系统未提供温度等级"
            )
        }
    }

    private fun readMemory(): Pair<Long, Long> {
        val manager = getApplication<Application>().getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return (info.totalMem / 1_048_576L) to (info.availMem / 1_048_576L)
    }

    private fun readThermalStatus(): Int? {
        if (Build.VERSION.SDK_INT < 29) return null
        val manager = getApplication<Application>().getSystemService(PowerManager::class.java)
        return runCatching { manager.currentThermalStatus }.getOrNull()
    }

    private fun thermalLabel(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "正常"
        PowerManager.THERMAL_STATUS_LIGHT -> "轻微升温"
        PowerManager.THERMAL_STATUS_MODERATE -> "中度升温"
        PowerManager.THERMAL_STATUS_SEVERE -> "严重发热"
        PowerManager.THERMAL_STATUS_CRITICAL -> "临界温度"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "紧急温度"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "系统即将关机"
        else -> "未知温度等级"
    }

    private fun buildRecommendation(results: List<ResolutionBenchmarkResult>): String {
        val measured = results.filter { it.inferenceFps != null }
        val best = measured.maxByOrNull { it.inferenceFps ?: 0.0 }
            ?: return "没有获得有效推理数据。请关闭其他应用、让设备冷却后重试。"
        val fps = best.inferenceFps ?: 0.0
        val speedNote = when {
            fps >= 90.0 -> "单次推理吞吐较高"
            fps >= 30.0 -> "单次推理吞吐中等"
            else -> "单次推理吞吐偏低"
        }
        return "本轮最快：${best.label}，约 ${String.format(Locale.US, "%.1f", fps)} 次推理/秒（$speedNote）。建议先从该分辨率、60 FPS 目标开始验证；120 FPS 和 1440p/4K 仍需完整视频长时间测试，不能仅凭这一轮推理测试判定稳定可用。"
    }

    private data class TestSize(
        val label: String,
        val width: Int,
        val height: Int,
        val minimumFreeRamMb: Long
    )
}
