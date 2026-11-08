# VideoForge AI

An open-source Android video enhancement app. The project is being built in stages.

## Current status

- [x] Android / Kotlin / Jetpack Compose project scaffold
- [x] GitHub Actions debug APK build workflow
- [x] Source video picker and output configuration UI
- [ ] RIFE 4.6 native inference (NCNN + Vulkan)
- [ ] FFmpeg decode / encode and audio preservation
- [ ] AI super-resolution model integration
- [ ] Segmented processing, verified checkpoints and resume
- [ ] Android thermal monitoring and safe throttling
- [ ] Task history and storage-safe temporary-file cleanup

**Important:** 4K output selection in this early preview is configuration UI only. It does not yet perform AI upscaling. The RIFE and FFmpeg processing pipeline is not connected yet.

## Build

Push to `main` or run the **Android APK** workflow manually. The debug APK is uploaded as a workflow artifact.

## Target output options

The planned UI supports original / 30 / 60 / 90 / 120 FPS and original / 720p / 1080p / 1440p / 2K / 4K output while preserving source aspect ratio.

## License

MIT for original project code. Third-party components and model weights will retain their own licenses; notices will be added before bundling them.
