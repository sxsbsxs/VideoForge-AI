# Third-party notices

The Android native build fetches and statically links the following upstream projects. Their license texts are copied into `app/src/main/assets/licenses/` during CMake configuration and included in the APK.

| Component | Upstream | Pinned source | License |
|---|---|---|---|
| RIFE ncnn Vulkan | https://github.com/nihui/rife-ncnn-vulkan | `a7532fc3f9f8f008cd6eecd6f2ffe2a9698e0cf7` | MIT |
| ncnn | https://github.com/Tencent/ncnn | `b4ba207c18d3103d6df890c0e3a97b469b196b26` | BSD-3-Clause plus separately identified third-party notices in its license file |
| libwebp | https://github.com/webmproject/libwebp | `5abb55823bb6196a918dd87202b2f32bbaff4c18` | BSD-3-Clause |
| glslang | https://github.com/KhronosGroup/glslang | `86ff4bca1ddc7e2262f119c16e7228d0efb67610` | Multiple licenses; see upstream LICENSE.txt copied into the APK |
| FFmpegKit Android maintained fork | https://github.com/ffmpegkit-maintained/ffmpeg | `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9` | LGPL-3.0; non-GPL artifact. Its AAR bundles `THIRD-PARTY-NOTICES.txt`; see the upstream notice and license terms |

The RIFE v4.6 model files are downloaded from the same pinned RIFE repository commit at first use. The installer checks their official Git blob IDs before making them available to the native engine.

The native build deliberately pins upstream revisions so CI builds do not silently move to a different RIFE or ncnn version. Review third-party notices again whenever these revisions are changed.

The FFmpegKit dependency is the non-GPL `full` artifact. It is distributed under LGPL-3.0; the project does not use the `-gpl` artifact. The AAR's bundled third-party notice file remains applicable and should be included with redistributed builds.
