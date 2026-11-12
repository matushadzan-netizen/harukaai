// native-lib.cpp
// JNI bridge between Kotlin (LlamaBridge.kt) and llama.cpp.
// Everything here runs fully on-device; there is no networking code anywhere in this file.

#include <jni.h>
#include <string>
#include <thread>
#include <vector>
#include <android/log.h>

#include "llama.h"
#include "common.h"

#ifdef LOCALAI_HAS_VISION
#include "llava.h"
#include "clip.h"
#endif

#define LOG_TAG "HarukaAI-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Holds everything needed to keep generating against one loaded model.
struct LocalAIContext {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
#ifdef LOCALAI_HAS_VISION
    clip_ctx* clip = nullptr;
#endif
    int n_threads = 4;
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_localai_app_LlamaBridge_loadModel(JNIEnv* env, jobject /* this */,
                                            jstring jModelPath, jint nThreads, jint nCtx) {
    const char* modelPath = env->GetStringUTFChars(jModelPath, nullptr);

    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    // n_gpu_layers > 0 offloads to GPU when the device has a usable Vulkan/OpenCL backend,
    // and silently falls back to CPU where it doesn't — no code path here ever calls out to a server.
    mparams.n_gpu_layers = 99;

    llama_model* model = llama_load_model_from_file(modelPath, mparams);
    env->ReleaseStringUTFChars(jModelPath, modelPath);

    if (!model) {
        LOGE("Failed to load model");
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = nCtx > 0 ? nCtx : 2048;
    cparams.n_threads = nThreads > 0 ? nThreads : 4;
    cparams.n_threads_batch = cparams.n_threads;

    llama_context* ctx = llama_new_context_with_model(model, cparams);
    if (!ctx) {
        LOGE("Failed to create context");
        llama_free_model(model);
        return 0;
    }

    auto* out = new LocalAIContext();
    out->model = model;
    out->ctx = ctx;
    out->n_threads = cparams.n_threads;
    LOGI("Model loaded OK, ctx=%d threads=%d", cparams.n_ctx, cparams.n_threads);
    return reinterpret_cast<jlong>(out);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_localai_app_LlamaBridge_loadMultimodalProjector(JNIEnv* env, jobject /* this */,
                                                           jlong handle, jstring jMmprojPath) {
#ifdef LOCALAI_HAS_VISION
    auto* lc = reinterpret_cast<LocalAIContext*>(handle);
    if (!lc) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(jMmprojPath, nullptr);
    lc->clip = clip_model_load(path, /*verbosity=*/1);
    env->ReleaseStringUTFChars(jMmprojPath, path);
    if (!lc->clip) {
        LOGE("Failed to load mmproj / clip model");
        return JNI_FALSE;
    }
    LOGI("Vision projector loaded OK");
    return JNI_TRUE;
#else
    LOGE("Built without vision support (llava target missing in this llama.cpp checkout)");
    return JNI_FALSE;
#endif
}

// Simple greedy/temperature sampling + token-by-token callback into Kotlin.
static void run_generation(JNIEnv* env, LocalAIContext* lc, const std::string& prompt,
                            const std::string* imagePath, int maxTokens, jobject onTokenCallback) {
    jclass cbClass = env->GetObjectClass(onTokenCallback);
    // Kotlin lambda (String) -> Unit exposes itself as invoke(Object) at the JVM level.
    jmethodID invokeMethod = env->GetMethodID(cbClass, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");

    const llama_vocab* vocab = llama_model_get_vocab(lc->model);
    bool image_used = false;

#ifdef LOCALAI_HAS_VISION
    if (imagePath && lc->clip) {
        // LLaVA expects the image embedding between the text before and after <image>.
        // The UI supplies the question only, so create the conventional LLaVA user prefix.
        const std::string prefix = "USER: ";
        const std::string suffix = "\n" + prompt + "\nASSISTANT:";
        std::vector<llama_token> before = common_tokenize(vocab, prefix, /*add_special=*/true);
        llama_batch before_batch = llama_batch_get_one(before.data(), (int) before.size());
        if (llama_decode(lc->ctx, before_batch) == 0) {
            llava_image_embed* embed = llava_image_embed_make_with_filename(
                lc->clip, lc->n_threads, imagePath->c_str());
            if (embed) {
                int n_past = (int) before.size();
                if (llava_eval_image_embed(lc->ctx, embed, /*n_batch=*/512, &n_past) == 0) {
                    std::vector<llama_token> after = common_tokenize(vocab, suffix, /*add_special=*/false);
                    llama_batch after_batch = llama_batch_get_one(after.data(), (int) after.size());
                    image_used = llama_decode(lc->ctx, after_batch) == 0;
                }
                llava_image_embed_free(embed);
            } else {
                LOGE("Failed to embed image: %s", imagePath->c_str());
            }
        }
    }
#endif

    if (!image_used) {
        // Text-only path, or fallback if a selected image could not be encoded.
        std::vector<llama_token> tokens = common_tokenize(vocab, prompt, /*add_special=*/true);
        llama_batch batch = llama_batch_get_one(tokens.data(), (int) tokens.size());
        if (llama_decode(lc->ctx, batch) != 0) {
            LOGE("llama_decode failed on prompt");
            return;
        }
    }

    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    for (int i = 0; i < maxTokens; i++) {
        llama_token newToken = llama_sampler_sample(sampler, lc->ctx, -1);
        if (llama_vocab_is_eog(vocab, newToken)) break;

        char buf[256];
        int n = llama_token_to_piece(vocab, newToken, buf, sizeof(buf), 0, true);
        std::string piece(buf, n);

        jstring jPiece = env->NewStringUTF(piece.c_str());
        env->CallObjectMethod(onTokenCallback, invokeMethod, jPiece);
        env->DeleteLocalRef(jPiece);

        llama_batch nextBatch = llama_batch_get_one(&newToken, 1);
        if (llama_decode(lc->ctx, nextBatch) != 0) break;
    }

    llama_sampler_free(sampler);
}

extern "C" JNIEXPORT void JNICALL
Java_com_localai_app_LlamaBridge_generate(JNIEnv* env, jobject /* this */,
                                           jlong handle, jstring jPrompt, jstring jImagePath,
                                           jint maxTokens, jobject onToken) {
    auto* lc = reinterpret_cast<LocalAIContext*>(handle);
    if (!lc) { LOGE("generate() called with null context"); return; }

    const char* promptChars = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(promptChars);
    env->ReleaseStringUTFChars(jPrompt, promptChars);

    std::string imagePath;
    const std::string* imagePathPtr = nullptr;
    if (jImagePath != nullptr) {
        const char* imgChars = env->GetStringUTFChars(jImagePath, nullptr);
        imagePath = imgChars;
        env->ReleaseStringUTFChars(jImagePath, imgChars);
        imagePathPtr = &imagePath;
    }

    run_generation(env, lc, prompt, imagePathPtr, maxTokens, onToken);
}

extern "C" JNIEXPORT void JNICALL
Java_com_localai_app_LlamaBridge_unload(JNIEnv* env, jobject /* this */, jlong handle) {
    auto* lc = reinterpret_cast<LocalAIContext*>(handle);
    if (!lc) return;
#ifdef LOCALAI_HAS_VISION
    if (lc->clip) clip_free(lc->clip);
#endif
    if (lc->ctx) llama_free(lc->ctx);
    if (lc->model) llama_free_model(lc->model);
    delete lc;
}
