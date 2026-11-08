#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cstdint>
#include <fstream>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>

#include "gpu.h"
#include "rife.h"

namespace {

constexpr const char* kLogTag = "VideoForgeRIFE";

struct RifeSession {
    std::unique_ptr<RIFE> model;
    bool use_vulkan = false;
    std::mutex inference_mutex;
};

std::once_flag g_gpu_init_once;
std::mutex g_sessions_mutex;
std::unordered_map<jlong, std::shared_ptr<RifeSession>> g_sessions;
jlong g_next_handle = 1;

void ensure_gpu_instance() {
    std::call_once(g_gpu_init_once, []() {
        ncnn::create_gpu_instance();
    });
}

void throw_java(JNIEnv* env, const char* class_name, const std::string& message) {
    jclass exception_class = env->FindClass(class_name);
    if (exception_class != nullptr) {
        env->ThrowNew(exception_class, message.c_str());
        env->DeleteLocalRef(exception_class);
    }
}

std::string to_utf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return std::string();
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return std::string();
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

bool file_exists(const std::string& path) {
    std::ifstream stream(path.c_str(), std::ios::binary);
    return stream.good();
}

std::shared_ptr<RifeSession> find_session(jlong handle) {
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    const auto it = g_sessions.find(handle);
    return it == g_sessions.end() ? nullptr : it->second;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_videoforge_ai_nativeengine_Rife46Engine_nativeGetGpuCount(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    ensure_gpu_instance();
    return std::max(0, ncnn::get_gpu_count());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_videoforge_ai_nativeengine_Rife46Engine_nativeCreateModel(
        JNIEnv* env,
        jobject /*thiz*/,
        jstring model_directory,
        jboolean prefer_vulkan,
        jboolean allow_cpu_fallback,
        jint num_threads) {
    const std::string model_dir = to_utf8(env, model_directory);
    if (model_dir.empty()) {
        throw_java(env, "java/lang/IllegalArgumentException", "Model directory is empty.");
        return 0;
    }

    const std::string param_path = model_dir + "/flownet.param";
    const std::string bin_path = model_dir + "/flownet.bin";
    if (!file_exists(param_path) || !file_exists(bin_path)) {
        throw_java(env, "java/lang/IllegalStateException",
                   "RIFE 4.6 model files are missing. Install the model before creating the engine.");
        return 0;
    }

    ensure_gpu_instance();
    const int gpu_count = std::max(0, ncnn::get_gpu_count());
    int gpu_id = -1;
    if (prefer_vulkan == JNI_TRUE && gpu_count > 0) {
        gpu_id = ncnn::get_default_gpu_index();
        if (gpu_id < 0 || gpu_id >= gpu_count) gpu_id = 0;
    } else if (prefer_vulkan == JNI_TRUE && allow_cpu_fallback != JNI_TRUE) {
        throw_java(env, "java/lang/UnsupportedOperationException",
                   "No Vulkan compute device was found on this Android device.");
        return 0;
    }

    try {
        std::shared_ptr<RifeSession> session = std::make_shared<RifeSession>();
        session->use_vulkan = gpu_id >= 0;
        const int thread_count = std::max(1, std::min(4, static_cast<int>(num_threads)));
        // RIFE v4.6 is selected by the final rife_v4=true constructor argument.
        session->model.reset(new RIFE(gpu_id, false, false, false, thread_count, false, true));
        const int load_result = session->model->load(model_dir);
        if (load_result != 0) {
            session->model.reset();
            throw_java(env, "java/lang/IllegalStateException",
                       "Failed to load RIFE 4.6 flownet.param/flownet.bin.");
            return 0;
        }

        std::lock_guard<std::mutex> lock(g_sessions_mutex);
        const jlong handle = g_next_handle++;
        g_sessions.emplace(handle, session);
        __android_log_print(ANDROID_LOG_INFO, kLogTag, "RIFE 4.6 session created (%s).",
                            session->use_vulkan ? "Vulkan" : "CPU");
        return handle;
    } catch (const std::exception& error) {
        throw_java(env, "java/lang/IllegalStateException", error.what());
    } catch (...) {
        throw_java(env, "java/lang/IllegalStateException", "Unknown native RIFE initialization error.");
    }
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_videoforge_ai_nativeengine_Rife46Engine_nativeSessionUsesVulkan(
        JNIEnv* env, jobject /*thiz*/, jlong handle) {
    const std::shared_ptr<RifeSession> session = find_session(handle);
    if (!session) {
        throw_java(env, "java/lang/IllegalStateException", "RIFE session is no longer available.");
        return JNI_FALSE;
    }
    return session->use_vulkan ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_videoforge_ai_nativeengine_Rife46Engine_nativeInterpolate(
        JNIEnv* env,
        jobject /*thiz*/,
        jlong handle,
        jbyteArray frame0,
        jbyteArray frame1,
        jint width,
        jint height,
        jfloat timestep) {
    const std::shared_ptr<RifeSession> session = find_session(handle);
    if (!session || !session->model) {
        throw_java(env, "java/lang/IllegalStateException", "RIFE session is no longer available.");
        return nullptr;
    }

    if (width <= 0 || height <= 0 || width > 8192 || height > 8192 ||
        timestep < 0.f || timestep > 1.f) {
        throw_java(env, "java/lang/IllegalArgumentException", "Invalid frame dimensions or timestep.");
        return nullptr;
    }

    const int64_t expected_size_64 = static_cast<int64_t>(width) *
                                     static_cast<int64_t>(height) * 3;
    if (expected_size_64 <= 0 || expected_size_64 > 100000000) {
        throw_java(env, "java/lang/IllegalArgumentException", "RGB frame is too large.");
        return nullptr;
    }
    const jsize expected_size = static_cast<jsize>(expected_size_64);
    if (frame0 == nullptr || frame1 == nullptr ||
        env->GetArrayLength(frame0) != expected_size ||
        env->GetArrayLength(frame1) != expected_size) {
        throw_java(env, "java/lang/IllegalArgumentException",
                   "Both input frames must be tightly packed RGB24 byte arrays.");
        return nullptr;
    }

    jbyte* pixels0 = env->GetByteArrayElements(frame0, nullptr);
    if (pixels0 == nullptr) return nullptr;
    jbyte* pixels1 = env->GetByteArrayElements(frame1, nullptr);
    if (pixels1 == nullptr) {
        env->ReleaseByteArrayElements(frame0, pixels0, JNI_ABORT);
        return nullptr;
    }

    ncnn::Mat input0(width, height, static_cast<void*>(pixels0), static_cast<size_t>(3), 3);
    ncnn::Mat input1(width, height, static_cast<void*>(pixels1), static_cast<size_t>(3), 3);
    ncnn::Mat output(width, height, static_cast<size_t>(3), 3);

    int process_result = -1;
    {
        std::lock_guard<std::mutex> lock(session->inference_mutex);
        process_result = session->model->process(input0, input1, timestep, output);
    }

    env->ReleaseByteArrayElements(frame0, pixels0, JNI_ABORT);
    env->ReleaseByteArrayElements(frame1, pixels1, JNI_ABORT);

    if (process_result != 0 || output.data == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "RIFE frame interpolation failed.");
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(expected_size);
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(
        result,
        0,
        expected_size,
        reinterpret_cast<const jbyte*>(output.data)
    );
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_videoforge_ai_nativeengine_Rife46Engine_nativeReleaseModel(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    std::shared_ptr<RifeSession> removed;
    {
        std::lock_guard<std::mutex> lock(g_sessions_mutex);
        const auto it = g_sessions.find(handle);
        if (it != g_sessions.end()) {
            removed = it->second;
            g_sessions.erase(it);
        }
    }
    removed.reset();
}
