# VideoSmoothAI

Open-source Android video-smoothing app UI built with Kotlin, Jetpack Compose, Material 3 and MVVM.

## Included UI

- Home screen with Android Photo Picker video selection
- Video metadata card (name, duration, resolution and source frame rate when metadata is available)
- 60 FPS / 120 FPS parameter selection
- Extreme-performance and temporary-fragment cleanup switches
- Processing screen with a Canvas ring progress indicator
- Thermal-warning banner, pause/resume and cancel callback hooks
- System light/dark theme support
- ViewModel backed by StateFlow

## Architecture

- `viewmodel/VideoProcessViewModel.kt`: UI state, navigation state and user actions
- `ui/VideoSmoothScreens.kt`: Home, Settings, Processing and Result composables
- `MainActivity.kt`: Activity Result video picker, background metadata extraction and engine callback integration points

## Video engine status

This repository currently implements the UI and state-management layer only. It does **not** yet decode, interpolate, upscale, encode or export video. Connect the native RIFE / Vulkan and FFmpeg processing engine through the callbacks in `MainActivity`:

- `onStartProcess(request)`
- `onPauseTask(shouldPause)`
- `onCancelTask()`

The processing screen can be driven by calling the ViewModel's `updateProgress(...)` and `setOverheated(...)` methods from the future processing coordinator.

The "极速发热模式" switch is only a user preference at this stage; it does not override Android thermal limits. Thermal monitoring and safe throttling must be implemented by the processing coordinator.

## Build

Push to `main` or run the **Android APK** GitHub Actions workflow. The debug APK is uploaded as an Actions artifact.
