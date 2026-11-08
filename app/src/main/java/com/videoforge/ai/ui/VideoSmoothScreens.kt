package com.videoforge.ai.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.videoforge.ai.benchmark.DeviceBenchmarkViewModel
import com.videoforge.ai.viewmodel.VideoProcessRequest
import com.videoforge.ai.viewmodel.VideoProcessViewModel

@Composable
fun VideoSmoothAITheme(content: @Composable () -> Unit) {
    SmoothAITheme(content)
}

@Composable
fun VideoSmoothAIApp(
    processViewModel: VideoProcessViewModel = viewModel(),
    onVideoPicked: (Uri) -> Unit,
    onStartProcess: (VideoProcessRequest) -> Unit,
    onPauseTask: (Boolean) -> Unit,
    onCancelTask: () -> Unit,
    onShareOutput: (Uri) -> Unit,
    benchmarkViewModel: DeviceBenchmarkViewModel
) {
    SmoothAIWorkspace(
        processViewModel = processViewModel,
        benchmarkViewModel = benchmarkViewModel,
        onVideoPicked = onVideoPicked,
        onStartProcess = onStartProcess,
        onPauseTask = onPauseTask,
        onCancelTask = onCancelTask,
        onShareOutput = onShareOutput
    )
}
