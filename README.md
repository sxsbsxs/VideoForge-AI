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
- Output settings for H.264 / H.265 / AV1 (subject to device codec capability), MP4, bitrate and optional source-audio retention; MKV is visibly disabled until a Matroska muxer is added
- Synchronized original-versus-processed dual-player comparison UI with shared seek, play/pause and playback speed
- History list with generated video-frame thumbnails
- On-device RIFE 4.6 benchmark screen with device, RAM, Vulkan backend and thermal status
- Extreme-performance and temporary-fragment cleanup preferences
- Canvas ring progress and thermal warning UI
- StateFlow-backed ViewModel
- Pinned RIFE 4.6 native source built with Android NDK and CMake
- JNI bridge for loading RIFE 4.6 and interpolating a pair of RGB24 frames
- End-to-end MediaExtractor / MediaCodec pipeline: YUV decode, RGB24 conversion, timestamp-driven RIFE interpolation, YUV420 encode and MP4 muxing
- Source audio packet reuse with timestamp interleaving, aspect-ratio-preserving output sizing, MediaStore export to Movies/SmoothAI
- Cooperative pause/cancel handling and Android severe-thermal-status wait when thermal protection is enabled
- Vulkan device detection with optional CPU fallback
- First-run RIFE model downloader with file size and Git blob SHA-1 verification

## Device performance test

Open **设备性能测试** from the home screen. The first run downloads the RIFE 4.6 model into app-private storage (about 10.6 MB of model weights plus the parameter file), then benchmarks synthetic RGB frames at 480p, 720p and 1080p. Each size performs one warm-up inference and three timed inferences; the median is reported as single-inference latency and approximate inference calls per second.

The test uses real native RIFE inference, but it does **not** include video decoding, encoding, audio muxing, storage I/O, or a long-duration thermal soak. It is therefore a useful device-side comparison, not a promise that a video can be exported at the displayed inference rate. The test skips a size when available RAM is below its safety threshold and stops when Android reports severe thermal status. 1440p and 4K are intentionally not stress-tested in the first pass.

## SmoothAI media UI

The home screen uses Android's system Photo Picker for local videos and Media3 ExoPlayer for in-app playback. A network URL field accepts direct HTTP(S) video URLs; progressive MP4 and HLS are supported by the included ExoPlayer and HLS modules, subject to the server allowing access and providing playable media. The settings page detects available Android encoders via `MediaCodecList` before enabling H.264, H.265 or AV1 choices.

The result screen now receives the exported output `Uri` from the processing coordinator and opens the synchronized original-versus-output comparison player. Both players share play/pause, seek position and playback speed.

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

## Video processing pipeline and limitations

The app now wires the processing button to `VideoProcessingCoordinator`:

1. `MediaExtractor` reads the source video track and `MediaCodec` decodes frames to flexible YUV420 images.
2. Frames are converted to RGB24 and resized without changing aspect ratio.
3. RIFE 4.6 generates frames at the requested constant-frame-rate timestamps.
4. RGB24 frames are converted to a YUV420 layout accepted by a device encoder and encoded using the selected H.264, H.265 or AV1 MIME type when the device supports the requested dimensions and frame rate.
5. The original compressed audio track is copied when enabled, interleaved by presentation timestamp with encoded video samples, then muxed to MP4.
6. The finished file is published to the system media library under `Movies/SmoothAI` on Android 10 and later. Older supported Android versions use the app-specific Movies directory.

The coordinator prefers a hardware encoder when it advertises the requested size/rate and a supported YUV420 input format. It does not guarantee that every device can encode AV1, 4K or 120 FPS. Large resolutions and high frame rates can exhaust GPU memory, system memory or thermal headroom. The device's codec capabilities are checked before encoding, but a successful CI build does not prove that a particular handset can finish a long video.

Current constraints:
- MP4 is the only export container. MKV is disabled in the parameter sheet until a Matroska muxer is implemented.
- Processing is limited to media that Android's `MediaExtractor` and installed `MediaCodec` components can decode. Preview support in ExoPlayer does not imply every preview URL is processable.
- Resolution changes currently use conventional aspect-ratio-preserving scaling; RIFE 4.6 provides frame interpolation, not AI super-resolution. No AI upscaling model is integrated yet.
- The current YUV-to-RGB path is an SDR conversion path; HDR / wide-gamut metadata and HDR transfer characteristics are not preserved end-to-end.
- Subtitles, chapter metadata, DRM-protected media and interrupted-task resume are not implemented.
- Source audio is copied without transcoding. If the source audio format cannot be added to the MP4 muxer, processing reports an error rather than silently dropping audio.
- The history list is in-memory only. A persistent database and a full recovery/segmented-processing system remain future work.

The RIFE model is downloaded on first use into app-private storage and checksum-verified. Severe Android thermal status causes the coordinator to wait when thermal protection is enabled; this does not bypass Android's own thermal controls.

A successful GitHub Actions APK build validates compilation and packaging only. Runtime throughput, output quality, audio compatibility and thermal stability still require testing on the target Android device.

## Build

Push to `main` or manually run the **Android APK** workflow. The debug APK is uploaded as a GitHub Actions artifact. The native CMake build fetches and pins upstream source code at configure time, so the first native build requires network access.

## Licenses

Original project code is MIT. Native third-party license texts are copied to APK assets during CMake configuration. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
