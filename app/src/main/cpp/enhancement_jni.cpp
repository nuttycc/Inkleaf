#include <jni.h>

#include <android/bitmap.h>
#include <android/log.h>

#include <algorithm>
#include <cstring>
#include <mutex>
#include <new>
#include <string>

#include "cpu.h"
#include "gpu.h"
#include "mat.h"
#include "realcugan.h"
#include "waifu2x.h"

namespace {

constexpr const char* LOG_TAG = "InkleafEnhancement";
constexpr int RESULT_OK = 0;
constexpr int ERROR_INVALID_SESSION = 1;
constexpr int ERROR_BITMAP_FORMAT = 2;
constexpr int ERROR_BITMAP_SIZE = 3;
constexpr int ERROR_BITMAP_LOCK = 4;
constexpr int ERROR_ALLOCATION = 5;
constexpr int ERROR_INFERENCE = 6;

enum class ModelKind {
    RealCuganNoSe,
    RealCuganConservative,
    Waifu2xUpconv7,
};

struct EnhancementSession {
    EnhancementSession(ModelKind model_kind, bool vulkan)
        : kind(model_kind), uses_vulkan(vulkan) {}

    ModelKind kind;
    bool uses_vulkan;
    RealCUGAN* realcugan = nullptr;
    Waifu2x* waifu2x = nullptr;
    std::mutex inference_mutex;

    ~EnhancementSession() {
        delete realcugan;
        delete waifu2x;
    }
};

class UtfChars {
public:
    UtfChars(JNIEnv* env, jstring value) : env_(env), value_(value) {
        chars_ = value ? env->GetStringUTFChars(value, nullptr) : nullptr;
    }

    ~UtfChars() {
        if (chars_) env_->ReleaseStringUTFChars(value_, chars_);
    }

    const char* get() const { return chars_; }

private:
    JNIEnv* env_;
    jstring value_;
    const char* chars_ = nullptr;
};

ModelKind parse_model_kind(const std::string& model_id, bool* valid) {
    *valid = true;
    if (model_id == "realcugan-2x-nose") return ModelKind::RealCuganNoSe;
    if (model_id == "realcugan-2x-conservative") return ModelKind::RealCuganConservative;
    if (model_id == "waifu2x-upconv7-anime-2x") return ModelKind::Waifu2xUpconv7;
    *valid = false;
    return ModelKind::RealCuganNoSe;
}

int choose_tile_size(bool uses_vulkan) {
    if (!uses_vulkan) return 100;
    const ncnn::VulkanDevice* device = ncnn::get_gpu_device(0);
    if (!device) return 32;
    return device->get_heap_budget() > 200 ? 100 : 32;
}

EnhancementSession* create_session(
    ModelKind kind,
    const char* param_path,
    const char* model_path,
    bool prefer_vulkan
) {
    const bool use_vulkan = prefer_vulkan && ncnn::get_gpu_count() > 0;
    const int gpu_id = use_vulkan ? 0 : -1;
    const int thread_count = std::max(1, std::min(4, ncnn::get_big_cpu_count()));
    const int tile_size = choose_tile_size(use_vulkan);

    EnhancementSession* session =
        new (std::nothrow) EnhancementSession(kind, use_vulkan);
    if (!session) return nullptr;

    int load_result = -1;
    if (kind == ModelKind::Waifu2xUpconv7) {
        session->waifu2x = new (std::nothrow) Waifu2x(gpu_id, false, thread_count);
        if (session->waifu2x) {
            load_result = session->waifu2x->load(param_path, model_path);
            session->waifu2x->noise = -1;
            session->waifu2x->scale = 2;
            session->waifu2x->tilesize = tile_size;
            session->waifu2x->prepadding = 7;
        }
    } else {
        session->realcugan = new (std::nothrow) RealCUGAN(gpu_id, false, thread_count);
        if (session->realcugan) {
            load_result = session->realcugan->load(param_path, model_path);
            session->realcugan->noise = kind == ModelKind::RealCuganNoSe ? 0 : -1;
            session->realcugan->scale = 2;
            session->realcugan->tilesize = tile_size;
            session->realcugan->prepadding = 18;
            session->realcugan->syncgap =
                kind == ModelKind::RealCuganConservative ? 3 : 0;
        }
    }

    if (load_result != 0) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Model load failed: %d", load_result);
        delete session;
        return nullptr;
    }
    return session;
}

int process_bitmap(
    JNIEnv* env,
    EnhancementSession* session,
    jobject input_bitmap,
    jobject output_bitmap
) {
    AndroidBitmapInfo input_info{};
    AndroidBitmapInfo output_info{};
    if (
        AndroidBitmap_getInfo(env, input_bitmap, &input_info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, output_bitmap, &output_info) != ANDROID_BITMAP_RESULT_SUCCESS
    ) {
        return ERROR_BITMAP_FORMAT;
    }
    if (
        input_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        output_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888
    ) {
        return ERROR_BITMAP_FORMAT;
    }
    if (
        output_info.width != input_info.width * 2 ||
        output_info.height != input_info.height * 2
    ) {
        return ERROR_BITMAP_SIZE;
    }

    const int input_width = static_cast<int>(input_info.width);
    const int input_height = static_cast<int>(input_info.height);
    ncnn::Mat input(input_width, input_height, static_cast<size_t>(4u), 4);
    ncnn::Mat output(input_width * 2, input_height * 2, static_cast<size_t>(4u), 4);
    if (input.empty() || output.empty()) {
        return ERROR_ALLOCATION;
    }

    void* input_pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, input_bitmap, &input_pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return ERROR_BITMAP_LOCK;
    }
    const size_t input_row_bytes = static_cast<size_t>(input_width) * 4u;
    const bool input_is_premultiplied =
        (input_info.flags & ANDROID_BITMAP_FLAGS_ALPHA_MASK) ==
        ANDROID_BITMAP_FLAGS_ALPHA_PREMUL;
    for (int row = 0; row < input_height; ++row) {
        auto* destination = static_cast<unsigned char*>(input.data) + row * input_row_bytes;
        const auto* source = static_cast<unsigned char*>(input_pixels) + row * input_info.stride;
        if (!input_is_premultiplied) {
            std::memcpy(destination, source, input_row_bytes);
            continue;
        }
        for (int column = 0; column < input_width; ++column) {
            const int offset = column * 4;
            const unsigned int alpha = source[offset + 3];
            destination[offset + 3] = static_cast<unsigned char>(alpha);
            if (alpha == 0 || alpha == 255) {
                destination[offset] = source[offset];
                destination[offset + 1] = source[offset + 1];
                destination[offset + 2] = source[offset + 2];
                continue;
            }
            destination[offset] = static_cast<unsigned char>(
                std::min(255u, (source[offset] * 255u + alpha / 2u) / alpha)
            );
            destination[offset + 1] = static_cast<unsigned char>(
                std::min(255u, (source[offset + 1] * 255u + alpha / 2u) / alpha)
            );
            destination[offset + 2] = static_cast<unsigned char>(
                std::min(255u, (source[offset + 2] * 255u + alpha / 2u) / alpha)
            );
        }
    }
    AndroidBitmap_unlockPixels(env, input_bitmap);

    int process_result = -1;
    {
        std::lock_guard<std::mutex> guard(session->inference_mutex);
        if (session->realcugan) {
            process_result = session->realcugan->process(input, output);
        } else if (session->waifu2x) {
            process_result = session->waifu2x->process(input, output);
        }
    }

    if (process_result != 0 || output.empty()) return ERROR_INFERENCE;

    void* output_pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, output_bitmap, &output_pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return ERROR_BITMAP_LOCK;
    }
    const int output_height = static_cast<int>(output_info.height);
    const size_t output_row_bytes = static_cast<size_t>(output_info.width) * 4u;
    const bool output_is_premultiplied =
        (output_info.flags & ANDROID_BITMAP_FLAGS_ALPHA_MASK) ==
        ANDROID_BITMAP_FLAGS_ALPHA_PREMUL;
    for (int row = 0; row < output_height; ++row) {
        auto* destination = static_cast<unsigned char*>(output_pixels) + row * output_info.stride;
        const auto* source = static_cast<unsigned char*>(output.data) + row * output_row_bytes;
        if (!output_is_premultiplied) {
            std::memcpy(destination, source, output_row_bytes);
            continue;
        }
        for (unsigned int column = 0; column < output_info.width; ++column) {
            const unsigned int offset = column * 4;
            const unsigned int alpha = source[offset + 3];
            destination[offset + 3] = static_cast<unsigned char>(alpha);
            destination[offset] = static_cast<unsigned char>(
                (source[offset] * alpha + 127u) / 255u
            );
            destination[offset + 1] = static_cast<unsigned char>(
                (source[offset + 1] * alpha + 127u) / 255u
            );
            destination[offset + 2] = static_cast<unsigned char>(
                (source[offset + 2] * alpha + 127u) / 255u
            );
        }
    }

    AndroidBitmap_unlockPixels(env, output_bitmap);
    return RESULT_OK;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    ncnn::create_gpu_instance();
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM*, void*) {
    ncnn::destroy_gpu_instance();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_exio_inkleaf_data_enhancement_NativeEnhancementBridge_nativeGpuCount(
    JNIEnv*,
    jobject
) {
    return ncnn::get_gpu_count();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_exio_inkleaf_data_enhancement_NativeEnhancementBridge_nativeCreateSession(
    JNIEnv* env,
    jobject,
    jstring model_id,
    jstring param_path,
    jstring model_path,
    jboolean prefer_vulkan
) {
    UtfChars model_id_chars(env, model_id);
    UtfChars param_path_chars(env, param_path);
    UtfChars model_path_chars(env, model_path);
    if (!model_id_chars.get() || !param_path_chars.get() || !model_path_chars.get()) return 0;

    bool valid = false;
    const ModelKind kind = parse_model_kind(model_id_chars.get(), &valid);
    if (!valid) return 0;

    EnhancementSession* session = create_session(
        kind,
        param_path_chars.get(),
        model_path_chars.get(),
        prefer_vulkan == JNI_TRUE
    );
    if (!session && prefer_vulkan == JNI_TRUE) {
        session = create_session(kind, param_path_chars.get(), model_path_chars.get(), false);
    }
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_exio_inkleaf_data_enhancement_NativeEnhancementBridge_nativeSessionUsesVulkan(
    JNIEnv*,
    jobject,
    jlong handle
) {
    const EnhancementSession* session = reinterpret_cast<EnhancementSession*>(handle);
    return session && session->uses_vulkan ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_exio_inkleaf_data_enhancement_NativeEnhancementBridge_nativeEnhance(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject input_bitmap,
    jobject output_bitmap
) {
    EnhancementSession* session = reinterpret_cast<EnhancementSession*>(handle);
    if (!session) return ERROR_INVALID_SESSION;
    return process_bitmap(env, session, input_bitmap, output_bitmap);
}

extern "C" JNIEXPORT void JNICALL
Java_com_exio_inkleaf_data_enhancement_NativeEnhancementBridge_nativeDestroySession(
    JNIEnv*,
    jobject,
    jlong handle
) {
    delete reinterpret_cast<EnhancementSession*>(handle);
}
