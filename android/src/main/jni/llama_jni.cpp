#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"

#define TAG "LlamaCppJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_gamma4_shelfscanner_LlamaCppBridge_nativeInit(
    JNIEnv *env, jobject /* this */,
    jstring modelPath, jint nThreads, jint nCtx) {

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model: %s (threads=%d, ctx=%d)", path, nThreads, nCtx);

    // Initialize llama backend
    llama_backend_init();

    // Load model
    struct llama_model_params model_params = llama_model_default_params();
    struct llama_model *model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (model == nullptr) {
        LOGE("Failed to load model");
        return 0;
    }

    // Create context
    struct llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = nCtx;
    ctx_params.n_threads = nThreads;
    ctx_params.n_threads_batch = nThreads;

    struct llama_context *ctx = llama_new_context_with_model(model, ctx_params);
    if (ctx == nullptr) {
        LOGE("Failed to create context");
        llama_model_free(model);
        return 0;
    }

    LOGI("Model loaded successfully");
    // Store both pointers - pack model ptr in high bits, ctx in low bits
    // Actually, we'll use a simple struct
    struct LlamaState {
        llama_model *model;
        llama_context *ctx;
    };
    auto *state = new LlamaState{model, ctx};
    return reinterpret_cast<jlong>(state);
}

JNIEXPORT jstring JNICALL
Java_com_gamma4_shelfscanner_LlamaCppBridge_nativeGenerate(
    JNIEnv *env, jobject /* this */,
    jlong contextPtr, jstring prompt, jint maxTokens, jfloat temperature) {

    struct LlamaState {
        llama_model *model;
        llama_context *ctx;
    };
    auto *state = reinterpret_cast<LlamaState *>(contextPtr);
    if (state == nullptr || state->ctx == nullptr) {
        return env->NewStringUTF("ERROR: context is null");
    }

    const char *promptStr = env->GetStringUTFChars(prompt, nullptr);

    // Tokenize prompt
    const llama_vocab *vocab = llama_model_get_vocab(state->model);
    int n_prompt_tokens = llama_tokenize(vocab, promptStr, strlen(promptStr), nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_prompt_tokens);
    llama_tokenize(vocab, promptStr, strlen(promptStr), tokens.data(), tokens.size(), true, true);
    env->ReleaseStringUTFChars(prompt, promptStr);

    LOGI("Prompt tokenized: %d tokens, generating up to %d tokens", n_prompt_tokens, maxTokens);

    // Clear KV cache
    llama_kv_cache_clear(state->ctx);

    // Create batch for prompt evaluation
    llama_batch batch = llama_batch_init(tokens.size(), 0, 1);
    for (size_t i = 0; i < tokens.size(); i++) {
        llama_batch_add(batch, tokens[i], i, {0}, false);
    }
    batch.logits[batch.n_tokens - 1] = true;

    // Evaluate prompt
    if (llama_decode(state->ctx, batch) != 0) {
        LOGE("Failed to decode prompt");
        llama_batch_free(batch);
        return env->NewStringUTF("ERROR: decode failed");
    }

    // Generate tokens
    std::string result;
    int n_cur = batch.n_tokens;
    llama_batch_free(batch);

    // Sampler setup
    auto *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(42));

    for (int i = 0; i < maxTokens; i++) {
        llama_token new_token = llama_sampler_sample(smpl, state->ctx, -1);

        // Check for end of generation
        if (llama_vocab_is_eog(vocab, new_token)) {
            break;
        }

        // Convert token to text
        char buf[256];
        int n = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, true);
        if (n > 0) {
            result.append(buf, n);
        }

        // Prepare next batch
        llama_batch next_batch = llama_batch_init(1, 0, 1);
        llama_batch_add(next_batch, new_token, n_cur, {0}, true);
        n_cur++;

        if (llama_decode(state->ctx, next_batch) != 0) {
            LOGE("Failed to decode at position %d", n_cur);
            llama_batch_free(next_batch);
            break;
        }
        llama_batch_free(next_batch);
    }

    llama_sampler_free(smpl);
    LOGI("Generated %zu chars", result.size());
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_gamma4_shelfscanner_LlamaCppBridge_nativeFree(
    JNIEnv *env, jobject /* this */, jlong contextPtr) {

    struct LlamaState {
        llama_model *model;
        llama_context *ctx;
    };
    auto *state = reinterpret_cast<LlamaState *>(contextPtr);
    if (state != nullptr) {
        if (state->ctx) llama_free(state->ctx);
        if (state->model) llama_model_free(state->model);
        delete state;
        LOGI("Model freed");
    }
    llama_backend_free();
}

JNIEXPORT jstring JNICALL
Java_com_gamma4_shelfscanner_LlamaCppBridge_nativeSystemInfo(
    JNIEnv *env, jobject /* this */) {
    const char *info = llama_print_system_info();
    return env->NewStringUTF(info);
}

} // extern "C"
