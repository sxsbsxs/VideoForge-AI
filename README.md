# SmoothAI (丝滑助手)

Open-source Android video-smoothing app built with Kotlin, Jetpack Compose, Material 3 and MVVM.

## Current implementation

- Home / Settings / Processing / Result screens
- Android Photo Picker video selection and source metadata display
- 30 / 60 / 90 / 120 FPS output preference
- Output resolution preference: source / 720p / 1080p / 1440p / 4K
- SmoothAI dark aurora workspace: Home / History / Settings tabs with a Material 3 NavigationBar
- System Photo Picker video import and in-app Media3 ExoPlayer preview
- Direct network MP4 / HLS preview for supported URLs
- Output settings for H.264 / H.265 / AV1 (only enabled when an encoder is detected), MP4 / MKV, bitrate and audio retention
- Synchronized original-versus-processed dual-player comparison UI with shared seek, play/pause and playback speed
- History list with generated video-frame thumbnails
- On-device RIFE 4.6 benchmark screen with device, RAM, Vulkan backend and thermal status
- Extreme-performance and temporary-fragment cleanup preferences
- Canvas ring progress and thermal warning UI
- StateFlow-backed ViewModel
- Pinned RIFE 4.6 native source built with Android NDK and CMake
- JNI bridge for loading RIFE 4.6 and interpolating a pair of RGB24 frames
- Vulkan device detection with optional CPU fallback
- First-run RIFE model downloader with file size and Git blob SHA-1 verification

## Device performance test

Open **设备性能测试** from the home screen. The first run downloads the RIFE 4.6 model into app-private storage (about 10.6 MB of model weights plus the parameter file), then benchmarks synthetic RGB frames at 480p, 720p and 1080p. Each size performs one warm-up inference and three timed inferences; the median is reported as single-inference latency and approximate inference calls per second.

The test uses real native RIFE inference, but it does **not** include video decoding, encoding, audio muxing, storage I/O, or a long-duration thermal soak. It is therefore a useful device-side comparison, not a promise that a video can be exported at the displayed inference rate. The test skips a size when available RAM is below its safety threshold and stops when Android reports severe thermal status. 1440p and 4K are intentionally not stress-tested in the first pass.

## SmoothAI media UI

The home screen uses Android's system Photo Picker for local videos and Media3 ExoPlayer for in-app playback. A network URL field accepts direct HTTP(S) video URLs; progressive MP4 and HLS are supported by the included ExoPlayer and HLS modules, subject to the server allowing access and providing playable media. The settings page detects available Android encoders via `MediaCodecList` before enabling H.264, H.265 or AV1 choices.

The result screen includes a synchronized dual-player comparison component. Both players share playback state, seek position and playback speed. It becomes active when a processing coordinator supplies the output `Uri` through `VideoProcessViewModel.finishProcessing(...)`.

History currently lives in the ViewModel state for the active app process; persistent database storage is a separate follow-up. Battery temperature is read from Android's battery status broadcast and shown as a device-temperature indicator. This is battery temperature, not a direct measurement of the SoC/GPU junction temperature.

## Native architecture

- `app/src/main/cpp/CMakeLists.txt`: downloads pinned upstream RIFE, ncnn, libwebp and glslang source revisions and builds the RIFE core as a static library plus an Android JNI shared library
- `app/src/main/cpp/rife_jni.cpp`: creates RIFE v4.6 sessions and exposes synchronous RGB24 frame-pair interpolation to Kotlin
- `nativeengine/Rife46Engine.kt`: Kotlin session API and Vulkan/CPU backend selection
- `nativeengine/Rife46ModelInstaller.kt`: first-run model installation and integrity verification

Native build currently targets **arm64-v8a** and requires Android NDK 27.0.12077973 plus CMake 3.22.1. The Vulkan path uses Android's `libvulkan.so`; devices without a usable Vulkan compute device can select the CPU fallback.

## Model installation and use

The model is not bundled inside the APK. On first use, call:

```kotlin
val modelDir = Rife46ModelInstaller.ensureInstalled(context)
val session = Rife46Engine.createSession(
    modelDirectory = modelDir,
    preferVulkan = true,
    allowCpuFallback = true
)
```

Then pass two tightly packed RGB24 frames to `session.interpolate(frame0, frame1, width, height, timestep = 0.5f)`. Always close the session when the task ends.

The model files are downloaded from the pinned upstream repository revision and checked against their upstream Git blob IDs before installation. The model directory is stored inside app-private storage.

## Important scope boundary

The native RIFE 4.6 inference core and JNI frame-pair interface are now present. **Full video-file processing is not complete yet.** The app still needs a MediaCodec/FFmpeg decode-and-encode coordinator to:
- decode source video into RGB frames,
- schedule intermediate timestamps for 60/120 FPS,
- preserve source audio and synchronize output timestamps,
- write the final video and recover interrupted segments.

The FPS, resolution, codec, container, bitrate and audio-retention controls are currently stored in `VideoProcessRequest`; they are not yet applied to an exported file. The synchronized comparison player is implemented, but it needs an actual output `Uri` from the processing coordinator. The existing `onStartProcess`, `onPauseTask` and `onCancelTask` callbacks remain integration points for the decode/encode coordinator. The UI explicitly reports that the export pipeline is not connected rather than pretending to produce a finished video. The "极速发热模式" switch does not bypass Android thermal safety; thermal monitoring and throttling still need to be implemented in the coordinator.

## Build

Push to `main` or manually run the **Android APK** workflow. The debug APK is uploaded as a GitHub Actions artifact. The native CMake build fetches and pins upstream source code at configure time, so the first native build requires network access.

## Licenses

Original project code is MIT. Native third-party license texts are copied to APK assets during CMake configuration. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
