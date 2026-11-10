#include <jni.h>
#include <android/log.h>
#include <sched.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "MeshChan.Llm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_context *ctxNeg = nullptr;  // second context for guidance, created on first use
    const llama_vocab *vocab = nullptr;
    std::string tmpl;
    std::atomic<bool> cancel{false};
    int n_threads = 4;
    uint32_t n_ctx = 0;
    std::vector<llama_token> cached;     // tokens currently in the KV cache (seq 0)
    std::vector<llama_token> cachedNeg;  // same for ctxNeg
    int prompt_tokens = 0;
    double prompt_ms = 0;
    int gen_tokens = 0;
    double gen_ms = 0;
    int reused_tokens = 0;
    int threads_used = 0;
};

// Android may confine us to the little cores when the screen is off; never run more threads than allowed CPUs.
int allowedCpus() {
    cpu_set_t set;
    CPU_ZERO(&set);
    if (sched_getaffinity(0, sizeof(set), &set) != 0) return 0;
    return CPU_COUNT(&set);
}

Engine *engine(jlong handle) { return reinterpret_cast<Engine *>(handle); }

void throwIllegalState(JNIEnv *env, const std::string &msg) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), msg.c_str());
}

std::string bytesToString(JNIEnv *env, jbyteArray arr) {
    if (arr == nullptr) return {};
    jsize len = env->GetArrayLength(arr);
    std::string out(static_cast<size_t>(len), '\0');
    env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

jbyteArray stringToBytes(JNIEnv *env, const std::string &s) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

// Length of the longest prefix of s that ends on a complete UTF-8 sequence.
size_t completeUtf8Prefix(const std::string &s) {
    size_t n = s.size();
    for (size_t back = 1; back <= 4 && back <= n; ++back) {
        auto c = static_cast<unsigned char>(s[n - back]);
        if ((c & 0xC0) == 0x80) continue;  // continuation byte
        size_t need = (c < 0x80) ? 1 : ((c & 0xE0) == 0xC0) ? 2 : ((c & 0xF0) == 0xE0) ? 3 : ((c & 0xF8) == 0xF0) ? 4 : 1;
        return back >= need ? n : n - back;
    }
    return n;
}

void logCallback(ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_WARN || level == GGML_LOG_LEVEL_ERROR) LOGW("%s", text);
}

double msSince(std::chrono::steady_clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
}

llama_context *newContext(llama_model *model, uint32_t nCtx, int nThreads) {
    auto cp = llama_context_default_params();
    cp.n_ctx = nCtx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    return llama_init_from_model(model, cp);
}

bool tokenize(const llama_vocab *vocab, const std::string &text, std::vector<llama_token> &out) {
    int32_t n = -llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), nullptr, 0, true, true);
    out.resize(static_cast<size_t>(n));
    return llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), out.data(), n, true, true) >= 0;
}

// Decodes [tokens] into [ctx], reusing the longest cached prefix. Returns the number of reused tokens, or -1 on failure.
int32_t prefill(Engine *e, llama_context *ctx, std::vector<llama_token> &cached, const std::vector<llama_token> &tokens) {
    const auto nPrompt = static_cast<int32_t>(tokens.size());
    llama_memory_t mem = llama_get_memory(ctx);
    int32_t keep = 0;
    // Keep at least one token to decode so there are fresh logits.
    while (keep < static_cast<int32_t>(cached.size()) && keep < nPrompt - 1 && cached[keep] == tokens[keep]) ++keep;
    if (keep == 0 || !llama_memory_seq_rm(mem, 0, keep, -1)) {
        llama_memory_clear(mem, true);
        keep = 0;
    }
    cached.assign(tokens.begin(), tokens.begin() + keep);
    const int32_t nBatch = 512;
    for (int32_t i = keep; i < nPrompt; i += nBatch) {
        if (e->cancel) return -1;
        int32_t n = std::min(nBatch, nPrompt - i);
        llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(tokens.data()) + i, n);
        if (llama_decode(ctx, batch) != 0) {
            llama_memory_clear(mem, true);
            cached.clear();
            return -1;
        }
        cached.insert(cached.end(), tokens.begin() + i, tokens.begin() + i + n);
    }
    return keep;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL Java_com_meshie4LoRa_llm_LlamaNative_init(JNIEnv *, jclass) {
    llama_log_set(logCallback, nullptr);
    llama_backend_init();
}

JNIEXPORT jlong JNICALL Java_com_meshie4LoRa_llm_LlamaNative_load(JNIEnv *env, jclass, jstring path, jint nCtx, jint nThreads, jboolean repack) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    // Repacked weights are faster on ARM but live in anonymous RAM; without it they stay mmapped and evictable.
    mp.use_extra_bufts = repack;
    llama_model *model = llama_model_load_from_file(p, mp);
    env->ReleaseStringUTFChars(path, p);
    if (!model) return 0;

    auto cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(nCtx);
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        llama_model_free(model);
        return 0;
    }
    auto *e = new Engine();
    e->model = model;
    e->ctx = ctx;
    e->n_ctx = static_cast<uint32_t>(nCtx);
    e->vocab = llama_model_get_vocab(model);
    e->n_threads = nThreads;
    if (const char *t = llama_model_chat_template(model, nullptr)) e->tmpl = t;
    LOGI("Model loaded: ctx=%u threads=%d repack=%d template=%s", llama_n_ctx(ctx), nThreads, repack, e->tmpl.empty() ? "none" : "yes");
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL Java_com_meshie4LoRa_llm_LlamaNative_free(JNIEnv *, jclass, jlong handle) {
    Engine *e = engine(handle);
    if (!e) return;
    if (e->ctxNeg) llama_free(e->ctxNeg);
    llama_free(e->ctx);
    llama_model_free(e->model);
    delete e;
}

JNIEXPORT jstring JNICALL Java_com_meshie4LoRa_llm_LlamaNative_info(JNIEnv *env, jclass, jlong handle) {
    Engine *e = engine(handle);
    char desc[256] = {0};
    llama_model_desc(e->model, desc, sizeof(desc));
    std::string out = std::string("desc=") + desc +
                      "\nparams=" + std::to_string(llama_model_n_params(e->model)) +
                      "\nsize=" + std::to_string(llama_model_size(e->model)) +
                      "\nctx_train=" + std::to_string(llama_model_n_ctx_train(e->model)) +
                      "\nctx=" + std::to_string(llama_n_ctx(e->ctx)) +
                      "\ntemplate=" + (e->tmpl.empty() ? "none" : "builtin") +
                      "\nthink=" + (e->tmpl.find("enable_thinking") != std::string::npos ? "1" : "0");
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jbyteArray JNICALL Java_com_meshie4LoRa_llm_LlamaNative_applyTemplate(
        JNIEnv *env, jclass, jlong handle, jobjectArray roles, jobjectArray contents) {
    Engine *e = engine(handle);
    jsize n = env->GetArrayLength(roles);
    std::vector<std::string> roleStore, contentStore;
    roleStore.reserve(n);
    contentStore.reserve(n);
    for (jsize i = 0; i < n; ++i) {
        auto r = reinterpret_cast<jstring>(env->GetObjectArrayElement(roles, i));
        const char *rc = env->GetStringUTFChars(r, nullptr);
        roleStore.emplace_back(rc);
        env->ReleaseStringUTFChars(r, rc);
        contentStore.push_back(bytesToString(env, reinterpret_cast<jbyteArray>(env->GetObjectArrayElement(contents, i))));
    }
    std::vector<llama_chat_message> msgs;
    for (jsize i = 0; i < n; ++i) msgs.push_back({roleStore[i].c_str(), contentStore[i].c_str()});

    auto apply = [&](const char *tmpl, std::string &out) -> bool {
        std::vector<char> buf(8192);
        int32_t len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
        if (len > static_cast<int32_t>(buf.size())) {
            buf.resize(static_cast<size_t>(len));
            len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
        }
        if (len < 0) return false;
        out.assign(buf.data(), static_cast<size_t>(len));
        return true;
    };

    std::string out;
    if (e->tmpl.empty() || !apply(e->tmpl.c_str(), out)) {
        if (!e->tmpl.empty()) LOGW("Model chat template not supported, falling back to chatml");
        apply("chatml", out);
    }
    return stringToBytes(env, out);
}

JNIEXPORT jboolean JNICALL Java_com_meshie4LoRa_llm_LlamaNative_generate(
        JNIEnv *env, jclass, jlong handle, jbyteArray promptBytes, jint maxTokens,
        jfloat temp, jfloat topP, jint topK, jfloat minP, jfloat repeatPenalty, jint seed,
        jbyteArray grammarBytes, jbyteArray negBytes, jfloat cfgScale, jobject callback) {
    Engine *e = engine(handle);
    e->cancel = false;
    e->prompt_tokens = e->gen_tokens = 0;
    e->prompt_ms = e->gen_ms = 0;

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "([B)Z");

    std::string prompt = bytesToString(env, promptBytes);
    std::string grammar = bytesToString(env, grammarBytes);
    std::string negPrompt = bytesToString(env, negBytes);
    // Classifier-free guidance: logits = neg + scale * (pos - neg), where neg is the prompt without the persona.
    const bool cfg = !negPrompt.empty() && cfgScale > 1.01f && grammar.empty();

    int allowed = allowedCpus();
    e->threads_used = std::max(1, allowed > 0 ? std::min(e->n_threads, allowed) : e->n_threads);
    llama_set_n_threads(e->ctx, e->threads_used, e->threads_used);

    std::vector<llama_token> tokens;
    if (!tokenize(e->vocab, prompt, tokens)) {
        throwIllegalState(env, "Tokenization failed");
        return JNI_FALSE;
    }
    const auto nPrompt = static_cast<int32_t>(tokens.size());
    const int32_t nCtx = static_cast<int32_t>(llama_n_ctx(e->ctx));
    if (nPrompt >= nCtx - 4) {
        throwIllegalState(env, "Prompt too long: " + std::to_string(nPrompt) + " tokens, context is " + std::to_string(nCtx));
        return JNI_FALSE;
    }
    std::vector<llama_token> negTokens;
    if (cfg) {
        if (!e->ctxNeg) e->ctxNeg = newContext(e->model, e->n_ctx, e->n_threads);
        if (!e->ctxNeg || !tokenize(e->vocab, negPrompt, negTokens) || static_cast<int32_t>(negTokens.size()) >= nCtx - 4) {
            throwIllegalState(env, "Guidance setup failed");
            return JNI_FALSE;
        }
        llama_set_n_threads(e->ctxNeg, e->threads_used, e->threads_used);
    }

    auto t0 = std::chrono::steady_clock::now();
    int32_t keep = prefill(e, e->ctx, e->cached, tokens);
    if (keep < 0) {
        if (!e->cancel) throwIllegalState(env, "Prompt decode failed");
        return JNI_FALSE;
    }
    e->reused_tokens = keep;
    e->prompt_tokens = nPrompt - keep;
    if (cfg) {
        int32_t keepNeg = prefill(e, e->ctxNeg, e->cachedNeg, negTokens);
        if (keepNeg < 0) {
            if (!e->cancel) throwIllegalState(env, "Guidance prompt decode failed");
            return JNI_FALSE;
        }
        e->prompt_tokens += static_cast<int32_t>(negTokens.size()) - keepNeg;
    }
    e->prompt_ms = msSince(t0);

    auto sp = llama_sampler_chain_default_params();
    llama_sampler *smpl = llama_sampler_chain_init(sp);
    if (!grammar.empty()) {
        llama_sampler *g = llama_sampler_init_grammar(e->vocab, grammar.c_str(), "root");
        if (!g) {
            llama_sampler_free(smpl);
            throwIllegalState(env, "Invalid grammar");
            return JNI_FALSE;
        }
        llama_sampler_chain_add(smpl, g);
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(e->vocab), 64, repeatPenalty, 0.0f, 0.0f));
    if (topK > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    if (minP > 0) llama_sampler_chain_add(smpl, llama_sampler_init_min_p(minP, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(static_cast<uint32_t>(seed)));

    auto t1 = std::chrono::steady_clock::now();
    std::string pending;
    char piece[256];
    bool keepGoing = true;
    int32_t nPast = nPrompt;
    int32_t nPastNeg = static_cast<int32_t>(negTokens.size());
    const int32_t nVocab = llama_vocab_n_tokens(e->vocab);
    std::vector<llama_token_data> cand(cfg ? static_cast<size_t>(nVocab) : 0);
    for (int32_t i = 0; i < maxTokens && keepGoing && !e->cancel; ++i) {
        llama_token tok;
        if (cfg) {
            const float *pos = llama_get_logits_ith(e->ctx, -1);
            const float *neg = llama_get_logits_ith(e->ctxNeg, -1);
            for (int32_t t = 0; t < nVocab; ++t) cand[t] = {t, neg[t] + cfgScale * (pos[t] - neg[t]), 0.0f};
            llama_token_data_array arr = {cand.data(), cand.size(), -1, false};
            llama_sampler_apply(smpl, &arr);
            tok = arr.data[arr.selected].id;
            llama_sampler_accept(smpl, tok);
        } else {
            tok = llama_sampler_sample(smpl, e->ctx, -1);
        }
        if (llama_vocab_is_eog(e->vocab, tok)) break;
        int32_t n = llama_token_to_piece(e->vocab, tok, piece, sizeof(piece), 0, false);
        if (n > 0) pending.append(piece, static_cast<size_t>(n));
        e->gen_tokens++;

        size_t ready = completeUtf8Prefix(pending);
        if (ready > 0) {
            jbyteArray arr = stringToBytes(env, pending.substr(0, ready));
            keepGoing = env->CallBooleanMethod(callback, onToken, arr);
            env->DeleteLocalRef(arr);
            if (env->ExceptionCheck()) break;
            pending.erase(0, ready);
        }
        if (++nPast >= nCtx) break;
        llama_batch batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(e->ctx, batch) != 0) {
            llama_memory_clear(llama_get_memory(e->ctx), true);
            e->cached.clear();
            break;
        }
        e->cached.push_back(tok);
        if (cfg) {
            if (++nPastNeg >= nCtx) break;
            llama_batch nb = llama_batch_get_one(&tok, 1);
            if (llama_decode(e->ctxNeg, nb) != 0) {
                llama_memory_clear(llama_get_memory(e->ctxNeg), true);
                e->cachedNeg.clear();
                break;
            }
            e->cachedNeg.push_back(tok);
        }
    }
    if (!pending.empty() && !env->ExceptionCheck()) {
        jbyteArray arr = stringToBytes(env, pending);
        env->CallBooleanMethod(callback, onToken, arr);
        env->DeleteLocalRef(arr);
    }
    e->gen_ms = msSince(t1);
    llama_sampler_free(smpl);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_meshie4LoRa_llm_LlamaNative_cancel(JNIEnv *, jclass, jlong handle) {
    if (Engine *e = engine(handle)) e->cancel = true;
}

JNIEXPORT jdoubleArray JNICALL Java_com_meshie4LoRa_llm_LlamaNative_lastStats(JNIEnv *env, jclass, jlong handle) {
    Engine *e = engine(handle);
    jdouble vals[6] = {static_cast<jdouble>(e->prompt_tokens), e->prompt_ms, static_cast<jdouble>(e->gen_tokens), e->gen_ms,
                       static_cast<jdouble>(e->reused_tokens), static_cast<jdouble>(e->threads_used)};
    jdoubleArray arr = env->NewDoubleArray(6);
    env->SetDoubleArrayRegion(arr, 0, 6, vals);
    return arr;
}

}  // extern "C"
