// ============================================================================
// ForgeAI — JNI bridge to llama.cpp (pinned: llama.cpp b10333)
//
// Exposes:
//   nativeInit / nativeGetSystemInfo / nativeVulkanDevices
//   nativeInspectModel(path) -> JSON metadata (fast, no weights)
//   nativeLoadModel / nativeUnloadModel / nativeIsLoaded / nativeGetLoadedInfo
//   nativeGenerate(roles, contents, params...) -> streams tokens via callbacks
//   nativeStop / nativeResetContext
//
// The Kotlin object dev.forgeai.LlamaEngine receives callbacks:
//   onToken(String) / onComplete(String json) / onError(String)
//   onLoadProgress(float)
// ============================================================================

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "llama.h"
#include "gguf.h"
#include "ggml-backend.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define LOG_TAG "ForgeAI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Globals
// ---------------------------------------------------------------------------
static JavaVM * g_vm = nullptr;

static llama_model *        g_model   = nullptr;
static llama_context *      g_ctx     = nullptr;
static const llama_vocab *  g_vocab   = nullptr;
static mtmd_context *       g_mctx    = nullptr; // vision encoder (mmproj), opcional
static std::atomic<bool>    g_stop{false};
static std::atomic<bool>    g_generating{false};
static std::atomic<bool>    g_loaded{false};
static std::thread          g_gen_thread;
static std::mutex           g_mutex;

// Token sequence currently present in the KV cache (seq 0). Used to validate
// prefix reuse between turns so the cache is only reused when the re-tokenized
// history matches it token-for-token. Touched only by the generation thread
// and by reset/load paths that join the generation thread first.
static std::vector<llama_token> g_cached_toks;

static jobject g_callback = nullptr; // global ref to the Kotlin LlamaEngine singleton

// ---------------------------------------------------------------------------
// Small UTF-8 helpers (correct emoji/surrogate handling over JNI)
// ---------------------------------------------------------------------------
static jstring utf8_to_jstring(JNIEnv * env, const char * utf8, size_t len) {
    std::vector<jchar> out;
    out.reserve(len);
    size_t i = 0;
    while (i < len) {
        unsigned char c = (unsigned char)utf8[i];
        uint32_t cp = 0;
        size_t n = 0;
        if (c < 0x80) { cp = c; n = 1; }
        else if ((c & 0xE0) == 0xC0) { cp = c & 0x1Fu; n = 2; }
        else if ((c & 0xF0) == 0xE0) { cp = c & 0x0Fu; n = 3; }
        else if ((c & 0xF8) == 0xF0) { cp = c & 0x07u; n = 4; }
        else { out.push_back(0xFFFD); i += 1; continue; }

        if (i + n > len) { out.push_back(0xFFFD); i += 1; continue; }
        bool ok = true;
        for (size_t k = 1; k < n; k++) {
            unsigned char cc = (unsigned char)utf8[i + k];
            if ((cc & 0xC0) != 0x80) { ok = false; break; }
            cp = (cp << 6) | (cc & 0x3Fu);
        }
        if (!ok) { out.push_back(0xFFFD); i += 1; continue; }
        if ((n == 2 && cp < 0x80) || (n == 3 && cp < 0x800) ||
            (n == 4 && cp < 0x10000) || cp > 0x10FFFF) {
            out.push_back(0xFFFD); i += 1; continue;
        }
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out.push_back((jchar)(0xD800 + (cp >> 10)));
            out.push_back((jchar)(0xDC00 + (cp & 0x3FF)));
        } else {
            out.push_back((jchar)cp);
        }
        i += n;
    }
    return env->NewString(out.data(), (jsize)out.size());
}

// Returns the number of bytes that form a complete UTF-8 prefix.
static size_t utf8_keep_len(const char * s, size_t len) {
    if (len == 0) return 0;
    for (size_t k = 1; k <= 4 && k <= len; k++) {
        unsigned char c = (unsigned char)s[len - k];
        if ((c & 0xC0) == 0x80) continue; // continuation byte
        size_t expect = 0;
        if ((c & 0xE0) == 0xC0) expect = 2;
        else if ((c & 0xF0) == 0xE0) expect = 3;
        else if ((c & 0xF8) == 0xF0) expect = 4;
        else return len; // ASCII
        if (k == expect) return len;     // complete multi-byte sequence
        return len - k;                  // incomplete -> cut it off
    }
    return len;
}

// ---------------------------------------------------------------------------
// JNI thread / callback plumbing
// ---------------------------------------------------------------------------
static JNIEnv * attach_thread(bool * attached) {
    *attached = false;
    JNIEnv * env = nullptr;
    if (g_vm->GetEnv((void **)&env, JNI_VERSION_1_6) == JNI_OK) return env;
    if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
        *attached = true;
        return env;
    }
    return nullptr;
}

static void call_void(JNIEnv * env, const char * name, const char * sig, jvalue * args) {
    if (!g_callback || !env) return;
    jclass cls = env->GetObjectClass(g_callback);
    if (!cls) return;
    jmethodID mid = env->GetMethodID(cls, name, sig);
    if (mid) env->CallVoidMethodA(g_callback, mid, args);
    env->DeleteLocalRef(cls);
}

static void emit_token(JNIEnv * env, const char * bytes, size_t len) {
    jstring s = utf8_to_jstring(env, bytes, len);
    if (!s) return;
    jvalue v; v.l = s;
    call_void(env, "onToken", "(Ljava/lang/String;)V", &v);
    env->DeleteLocalRef(s);
}

static void emit_str(JNIEnv * env, const char * name, const char * text) {
    jstring s = env->NewStringUTF(text);
    jvalue v; v.l = s;
    call_void(env, name, "(Ljava/lang/String;)V", &v);
    env->DeleteLocalRef(s);
}

static void emit_complete(JNIEnv * env, const char * json) {
    emit_str(env, "onComplete", json);
}

static void emit_error(JNIEnv * env, const char * msg) {
    emit_str(env, "onError", msg);
}

static void emit_progress(JNIEnv * env, float frac) {
    jvalue v; v.f = frac;
    call_void(env, "onLoadProgress", "(F)V", &v);
}

// ---------------------------------------------------------------------------
// Quantization name for GGUF "general.file_type"
// ---------------------------------------------------------------------------
static const char * quant_name(int32_t ftype) {
    switch (ftype) {
        case 0: return "F32";
        case 1: return "F16";
        case 2: return "Q4_0";
        case 3: return "Q4_1";
        case 7: return "Q8_0";
        case 8: return "Q5_0";
        case 9: return "Q5_1";
        case 10: return "Q2_K";
        case 11: return "Q3_K_S";
        case 12: return "Q3_K_M";
        case 13: return "Q3_K_L";
        case 14: return "Q4_K_S";
        case 15: return "Q4_K_M";
        case 16: return "Q5_K_S";
        case 17: return "Q5_K_M";
        case 18: return "Q6_K";
        case 19: return "IQ2_XXS";
        case 20: return "IQ2_XS";
        case 21: return "Q2_K_S";
        case 22: return "IQ3_XS";
        case 23: return "IQ3_XXS";
        case 24: return "IQ1_S";
        case 25: return "IQ4_NL";
        case 26: return "IQ3_S";
        case 27: return "IQ3_M";
        case 28: return "IQ2_S";
        case 29: return "IQ2_M";
        case 30: return "IQ4_XS";
        case 31: return "IQ1_M";
        case 32: return "BF16";
        default:  return "";
    }
}

static std::string escape_json(const std::string & s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (char c : s) {
        if (c == '"') out += "\\\"";
        else if (c == '\\') out += "\\\\";
        else if (c == '\n') out += "\\n";
        else if (c == '\r') out += "\\r";
        else if (c == '\t') out += "\\t";
        else if ((unsigned char)c < 0x20 || c == 0x7f) {
            char buf[8];
            snprintf(buf, sizeof(buf), "\\u%04x", (unsigned char)c);
            out += buf;
        }
        else out += c;
    }
    return out;
}

// ---------------------------------------------------------------------------
// JNI_OnLoad
// ---------------------------------------------------------------------------
JNIEXPORT jint JNI_OnLoad(JavaVM * vm, void * reserved) {
    (void)reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

// ---------------------------------------------------------------------------
// nativeInit / nativeGetSystemInfo / nativeVulkanDevices
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_forgeai_LlamaEngine_nativeInit(JNIEnv * env, jobject thiz, jstring jbackend_dir) {
    (void)thiz;
    if (!g_callback) {
        g_callback = env->NewGlobalRef(thiz);
    }
    // Builds com GGML_BACKEND_DL (FORGE_ALL_VARIANTS) precisam que o backend
    // CPU seja carregado do diretório das libs nativas do app. Em builds
    // estáticos isto é um no-op (não há libggml-cpu-*.so). Chamado ANTES de
    // llama_backend_init(): como o registro já fica populado, o init interno
    // do llama.cpp não tenta carregar de paths que não existem no Android.
    if (jbackend_dir) {
        const char * dir = env->GetStringUTFChars(jbackend_dir, nullptr);
        if (dir) {
            ggml_backend_load_all_from_path(dir);
            env->ReleaseStringUTFChars(jbackend_dir, dir);
        }
    }
    llama_backend_init();
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_forgeai_LlamaEngine_nativeGetSystemInfo(JNIEnv * env, jobject) {
    const char * info = llama_print_system_info();
    return env->NewStringUTF(info ? info : "unknown");
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_forgeai_LlamaEngine_nativeVulkanDevices(JNIEnv * env, jobject) {
    // Enumerate GPU devices (only meaningful when built with Vulkan).
    std::string json = "[";
    const size_t n = ggml_backend_dev_count();
    bool first = true;
    for (size_t i = 0; i < n; i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (!dev) continue;
        enum ggml_backend_dev_type t = ggml_backend_dev_type(dev);
        if (t != GGML_BACKEND_DEVICE_TYPE_GPU && t != GGML_BACKEND_DEVICE_TYPE_IGPU) continue;
        if (!first) json += ",";
        first = false;
        json += "\"" + std::string(ggml_backend_dev_name(dev) ? ggml_backend_dev_name(dev) : "?") + "\"";
    }
    json += "]";
    return env->NewStringUTF(json.c_str());
}

// ---------------------------------------------------------------------------
// nativeInspectModel — read GGUF metadata only (no weights)
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jstring JNICALL
Java_dev_forgeai_LlamaEngine_nativeInspectModel(JNIEnv * env, jobject, jstring jpath) {
    const char * path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return nullptr;

    gguf_init_params ip = { /*no_alloc=*/ true, /*ctx=*/ nullptr };
    gguf_context * g = gguf_init_from_file(path, ip);
    env->ReleaseStringUTFChars(jpath, path);
    if (!g) {
        LOGE("inspect: gguf_init_from_file falhou em '%s'", path);
        return nullptr;
    }

    auto s = [&](const char * k) -> std::string {
        int64_t id = gguf_find_key(g, k);
        if (id < 0) return "";
        switch (gguf_get_kv_type(g, id)) {
            case GGUF_TYPE_STRING: {
                const char * v = gguf_get_val_str(g, id);
                return v ? v : "";
            }
            case GGUF_TYPE_INT8:    return std::to_string((int)gguf_get_val_i8(g, id));
            case GGUF_TYPE_INT16:   return std::to_string((int)gguf_get_val_i16(g, id));
            case GGUF_TYPE_INT32:   return std::to_string(gguf_get_val_i32(g, id));
            case GGUF_TYPE_INT64:   return std::to_string(gguf_get_val_i64(g, id));
            case GGUF_TYPE_UINT8:   return std::to_string(gguf_get_val_u8(g, id));
            case GGUF_TYPE_UINT16:  return std::to_string(gguf_get_val_u16(g, id));
            case GGUF_TYPE_UINT32:  return std::to_string(gguf_get_val_u32(g, id));
            case GGUF_TYPE_UINT64:  return std::to_string(gguf_get_val_u64(g, id));
            case GGUF_TYPE_FLOAT32: { char t[32]; snprintf(t, sizeof(t), "%.4f", gguf_get_val_f32(g, id)); return t; }
            case GGUF_TYPE_BOOL:    return gguf_get_val_bool(g, id) ? "true" : "false";
            default: return "";
        }
    };

    // As chaves numéricas usam o prefixo da arquitetura, que varia por modelo
    // (llama.block_count, qwen2.block_count, lfm2.block_count, ...). Usar o
    // prefixo errado (ou fixo) gera JSON inválido e a leitura falha.
    const std::string arch = s("general.architecture");
    const std::string pfx  = arch.empty() ? "llama" : arch;
    auto sarch = [&](const char * base) -> std::string {
        return s((pfx + "." + base).c_str());
    };

    int32_t ftype = 0;
    {
        std::string v = s("general.file_type");
        ftype = v.empty() ? -1 : atoi(v.c_str());
    }

    // Número de parâmetros = soma dos elementos de todos os tensores
    // (o GGUF guarda só os pesos, então a soma dos ne[] dá o total).
    int64_t n_params = 0;
    for (int64_t i = 0; i < gguf_get_n_tensors(g); i++) {
        const int64_t * ne = gguf_get_tensor_ne(g, i);
        if (!ne) continue;
        int64_t n = 1;
        for (int d = 0; d < GGML_MAX_DIMS; d++) {
            n *= ne[d];
        }
        n_params += n;
    }

    std::string json;
    json += "{";
    json += "\"meta_ver\":2,";
    json += "\"arch\":\""     + escape_json(arch) + "\",";
    json += "\"name\":\""     + escape_json(s("general.name")) + "\",";
    json += "\"tokenizer\":\"" + escape_json(s("tokenizer.ggml.model")) + "\",";
    json += "\"ftype\":"      + std::to_string(ftype) + ",";
    json += "\"quant\":\""    + std::string(quant_name(ftype)) + "\",";

    // Campos numéricos: só entram no JSON quando existem no arquivo.
    // Campo omitido vira null no Kotlin (nada de "chave":, que quebra o JSON).
    auto add_num = [&](const char * key, const std::string & val) {
        if (!val.empty()) {
            json += std::string("\"") + key + "\":" + val + ",";
        }
    };
    add_num("n_layer",     sarch("block_count"));
    add_num("n_ctx_train", sarch("context_length"));
    add_num("n_embd",      sarch("embedding_length"));
    add_num("n_head",      sarch("attention.head_count"));
    add_num("n_vocab",     sarch("vocab_size"));

    // KV heads: escalar em MHA/GQA, ou ARRAY por camada em atenção híbrida
    // (Qwen3.5/Bonsai 2: 3 camadas lineares : 1 full-attention). No array,
    // contamos só as camadas com KV > 0 e usamos o maior valor (full-attention)
    // — é exatamente o que dimensiona o KV cache (TD-25, fit badge).
    {
        const std::string key = pfx + ".attention.head_count_kv";
        const int64_t id = gguf_find_key(g, key.c_str());
        if (id >= 0) {
            const enum gguf_type t = gguf_get_kv_type(g, id);
            long long kv_heads = 0;
            long long kv_layers = 0;
            if (t == GGUF_TYPE_ARRAY) {
                const enum gguf_type et = gguf_get_arr_type(g, id);
                const size_t n = gguf_get_arr_n(g, id);
                const void * data = gguf_get_arr_data(g, id);
                for (size_t i = 0; i < n && data; i++) {
                    long long v = 0;
                    switch (et) {
                        case GGUF_TYPE_INT32:  v = ((const int32_t  *) data)[i]; break;
                        case GGUF_TYPE_INT64:  v = ((const int64_t  *) data)[i]; break;
                        case GGUF_TYPE_UINT32: v = (long long) ((const uint32_t *) data)[i]; break;
                        case GGUF_TYPE_UINT64: v = (long long) ((const uint64_t *) data)[i]; break;
                        default: break;
                    }
                    if (v > 0) { kv_layers++; if (v > kv_heads) kv_heads = v; }
                }
            } else {
                switch (t) {
                    case GGUF_TYPE_INT32:  kv_heads = gguf_get_val_i32(g, id); break;
                    case GGUF_TYPE_INT64:  kv_heads = (long long) gguf_get_val_i64(g, id); break;
                    case GGUF_TYPE_UINT32: kv_heads = (long long) gguf_get_val_u32(g, id); break;
                    case GGUF_TYPE_UINT64: kv_heads = (long long) gguf_get_val_u64(g, id); break;
                    default: break;
                }
            }
            if (kv_heads > 0) {
                json += "\"n_head_kv\":" + std::to_string(kv_heads) + ",";
                // escalar => todas as camadas guardam KV (o Kotlin cai em n_layer)
                if (kv_layers > 0) {
                    json += "\"n_kv_layers\":" + std::to_string(kv_layers) + ",";
                }
            }
        }
    }
    json += "\"n_tensors\":"  + std::to_string(gguf_get_n_tensors(g)) + ",";
    json += "\"n_params\":"  + std::to_string(n_params) + ",";
    // chat template (used to detect tool-call support without loading weights)
    json += "\"template\":\"" + escape_json(s("tokenizer.chat_template")) + "\"";
    json += "}";

    gguf_free(g);
    return utf8_to_jstring(env, json.c_str(), json.size());
}

// ---------------------------------------------------------------------------
// Load / unload
// ---------------------------------------------------------------------------
static bool load_progress_cb(float progress, void * ud) {
    (void)ud;
    bool attached = false;
    JNIEnv * env = attach_thread(&attached);
    if (env) emit_progress(env, progress);
    if (attached) g_vm->DetachCurrentThread();
    return true; // continue loading
}

static void free_model_locked() {
    if (g_mctx)  { mtmd_free(g_mctx);  g_mctx = nullptr; }
    if (g_ctx)   { llama_free(g_ctx);   g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_vocab = nullptr;
    g_loaded = false;
    g_cached_toks.clear();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_forgeai_LlamaEngine_nativeLoadModel(
        JNIEnv * env, jobject,
        jstring jpath, jstring jmmproj, jint n_ctx, jint n_threads, jint n_threads_batch, jint n_gpu_layers) {

    if (g_generating.load()) {
        emit_error(env, "Modelo em geração. Aguarde ou pare a geração antes de trocar de modelo.");
        return JNI_FALSE;
    }

    const char * path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(g_mutex);

    free_model_locked();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = n_gpu_layers;
    mp.progress_callback = load_progress_cb;
    mp.progress_callback_user_data = nullptr;

    LOGI("loading model: %s (ctx=%d, threads=%d/%d, gpu_layers=%d)",
         path, (int)n_ctx, (int)n_threads, (int)n_threads_batch, (int)n_gpu_layers);

    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);

    if (!g_model) {
        LOGE("failed to load model");
        emit_error(env, "Falha ao carregar o modelo. O arquivo pode estar corrompido ou incompleto — exclua-o na aba Modelos e baixe novamente.");
        return JNI_FALSE;
    }

    g_vocab = llama_model_get_vocab(g_model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx         = (uint32_t)n_ctx;
    cp.n_batch       = 512;
    cp.n_ubatch      = 512;
    cp.n_threads     = n_threads;
    cp.n_threads_batch = n_threads_batch;
    cp.no_perf       = false;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;

    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) {
        LOGE("failed to init context");
        llama_model_free(g_model);
        g_model = nullptr;
        g_vocab = nullptr;
        emit_error(env, "Falha ao criar o contexto de inferência. Tente reduzir o contexto ou a memória.");
        return JNI_FALSE;
    }

    llama_set_n_threads(g_ctx, n_threads, n_threads_batch);
    g_loaded.store(true);

    // Vision encoder (mmproj) — opcional. Sem ele o modelo roda só em texto.
    // LFM2.5-VL usa SigLIP2 NaFlex via o arquivo mmproj-*.gguf do mesmo repo.
    if (jmmproj) {
        const char * mmproj = env->GetStringUTFChars(jmmproj, nullptr);
        if (mmproj && mmproj[0] != '\0') {
            mtmd_context_params mmp = mtmd_context_params_default();
            mmp.use_gpu      = false; // build CPU
            mmp.n_threads    = (int)n_threads;
            mmp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
            mmp.warmup       = false;
            g_mctx = mtmd_init_from_file(mmproj, g_model, mmp);
            if (!g_mctx) {
                LOGE("mmproj init falhou: %s", mmproj);
                emit_error(env, "Não foi possível carregar o encoder de imagens (mmproj). O modelo segue funcionando em texto.");
            } else {
                LOGI("mmproj carregado: %s", mmproj);
            }
        }
        if (mmproj) env->ReleaseStringUTFChars(jmmproj, mmproj);
    }
    LOGI("model loaded OK");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_forgeai_LlamaEngine_nativeUnloadModel(JNIEnv * env, jobject) {
    g_stop.store(true);
    if (g_gen_thread.joinable()) g_gen_thread.join();
    std::lock_guard<std::mutex> lock(g_mutex);
    free_model_locked();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_forgeai_LlamaEngine_nativeIsLoaded(JNIEnv *, jobject) {
    return g_loaded.load() && g_model && g_ctx ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_forgeai_LlamaEngine_nativeGetLoadedInfo(JNIEnv * env, jobject) {
    if (!g_model) return nullptr;

    char desc[512] = {0};
    llama_model_desc(g_model, desc, sizeof(desc));

    const char * t = llama_model_chat_template(g_model, nullptr);
    std::string tmpl = t ? std::string(t) : "";
    std::string tmpl_short = "";
    if (!tmpl.empty()) {
        // short form: first line, max 64 chars (kept for display/compat)
        size_t nl = tmpl.find('\n');
        tmpl_short = tmpl.substr(0, nl == std::string::npos ? 64 : nl);
    }

    char tmp[128];
    snprintf(tmp, sizeof(tmp), "%llu", (unsigned long long)llama_model_n_params(g_model));
    std::string n_params = tmp;
    snprintf(tmp, sizeof(tmp), "%llu", (unsigned long long)llama_model_size(g_model));
    std::string size = tmp;

    int32_t ftype = (int32_t)llama_model_ftype(g_model);

    // build the JSON with std::string: the full chat template can be long
    // (the old fixed 1024-byte buffer would have truncated/overflowed it)
    std::string json;
    json.reserve(256 + tmpl.size() * 2);
    json += "{\"has_vision\":" + std::string(g_mctx ? "true" : "false") + ",";
    json += "\"name\":\"" + escape_json(desc) + "\",";
    json += "\"n_params\":" + n_params + ",";
    json += "\"size_bytes\":" + size + ",";
    json += "\"n_ctx_train\":" + std::to_string(llama_model_n_ctx_train(g_model)) + ",";
    json += "\"n_layer\":" + std::to_string(llama_model_n_layer(g_model)) + ",";
    json += "\"n_embd\":" + std::to_string(llama_model_n_embd(g_model)) + ",";
    json += "\"quant\":\"" + std::string(quant_name(ftype)) + "\",";
    json += "\"ftype\":" + std::to_string(ftype) + ",";
    json += "\"has_template\":" + std::string(t ? "true" : "false") + ",";
    json += "\"template\":\"" + escape_json(tmpl_short) + "\",";
    json += "\"template_full\":\"" + escape_json(tmpl) + "\"}";
    return env->NewStringUTF(json.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_forgeai_LlamaEngine_nativeResetContext(JNIEnv *, jobject) {
    // Stop is asynchronous (the generation loop polls g_stop between decodes),
    // so wait for any in-flight generation before touching the memory.
    g_stop.store(true);
    if (g_gen_thread.joinable()) g_gen_thread.join();
    if (!g_ctx) return;
    llama_memory_t mem = llama_get_memory(g_ctx);
    llama_memory_seq_rm(mem, 0, 0, -1);
    g_cached_toks.clear();
}

// ---------------------------------------------------------------------------
// Generation
// ---------------------------------------------------------------------------
struct GenParams {
    // Message data is copied to C++ strings on the CALLING thread (JNI local
    // references are only valid there — using them on the worker thread would
    // be undefined behaviour and crash the app).
    std::vector<std::string> roles;
    std::vector<std::string> contents;
    // Path of the attached image per message (empty string = no image).
    std::vector<std::string> images;
    int n_messages = 0;
    float temp;
    int   top_k;
    float top_p;
    float min_p;
    float repeat_penalty;
    uint32_t seed;
    int max_tokens;
    int n_threads;
    int n_threads_batch;
    bool reset_context;
};

static void generation_worker(GenParams p) {
    bool attached = false;
    JNIEnv * env = attach_thread(&attached);
    if (!env) { g_generating.store(false); return; }

    std::string status = "ok";
    int n_cur = 0;
    int n_prompt = 0;

    do {
        // 1) guard the model/context state
        if (!g_model || !g_ctx || !g_vocab) {
            emit_error(env, "Nenhum modelo carregado.");
            status = "error";
            break;
        }

        llama_set_n_threads(g_ctx, p.n_threads, p.n_threads_batch);

        // 3) build the prompt. Two paths:
        //   - texto puro: llama_chat_apply_template (template do modelo, com
        //     fallback ChatML para templates não reconhecidos — LFM2.5 usa ChatML).
        //   - com imagem: prompt ChatML manual com o marcador <__media__> no fim
        //     da mensagem com imagem; o mtmd tokeniza + embute o encoder de visão
        //     (SigLIP2/LFM2 via mmproj) e decodifica texto+imagem no contexto.
        const bool has_images = [&]() {
            for (const auto & img : p.images) if (!img.empty()) return true;
            return false;
        }();
        const bool vision_path = has_images && g_mctx != nullptr;
        if (has_images && !g_mctx) {
            emit_error(env, "Este modelo não tem encoder de imagens carregado (mmproj).");
            status = "error";
            break;
        }

        const char * tmpl = llama_model_chat_template(g_model, nullptr);
        const int n_ctx_cur = llama_n_ctx(g_ctx);
        std::vector<llama_token> toks;
        int n_past = 0;

        if (vision_path) {
            // prompt no formato ChatML (o mesmo que llama_chat_apply_template
            // produz para LFM2.5), com <__media__> após o texto da mensagem.
            std::string prompt;
            prompt.reserve(512);
            for (int i = 0; i < p.n_messages; i++) {
                prompt += "<|im_start|>";
                prompt += p.roles[i];
                prompt += "\n";
                prompt += p.contents[i];
                if (!p.images[i].empty()) prompt += "<__media__>";
                prompt += "<|im_end|>\n";
            }
            prompt += "<|im_start|>assistant\n";

            // carrega as imagens (jpg/png/... via stb_image no mtmd)
            std::vector<mtmd_helper_bitmap_wrapper> wrappers;
            std::vector<const mtmd_bitmap *> bitmaps;
            for (const auto & img : p.images) {
                if (img.empty()) continue;
                mtmd_helper_bitmap_wrapper w = mtmd_helper_bitmap_init_from_file(g_mctx, img.c_str(), false);
                if (!w.bitmap) {
                    for (auto & bw : wrappers) if (bw.bitmap) mtmd_bitmap_free(bw.bitmap);
                    emit_error(env, ("Não foi possível carregar a imagem: " + img).c_str());
                    status = "error";
                    break;
                }
                wrappers.push_back(w);
                bitmaps.push_back(w.bitmap);
            }
            if (status == "error") break;

            mtmd_input_text inp_txt = {
                prompt.c_str(), prompt.size(),
                /* add_special   */ true,
                /* parse_special */ true,
            };
            mtmd_input_chunks * chunks = mtmd_input_chunks_init();
            int rc = mtmd_tokenize(g_mctx, chunks, &inp_txt, bitmaps.data(), bitmaps.size());
            for (auto & bw : wrappers) if (bw.bitmap) mtmd_bitmap_free(bw.bitmap);
            if (rc != 0) {
                mtmd_input_chunks_free(chunks);
                emit_error(env, "Falha ao processar a imagem (formato não suportado?).");
                status = "error";
                break;
            }

            const size_t n_tok_est = mtmd_helper_get_n_tokens(chunks);
            if ((int)n_tok_est > n_ctx_cur - 32) {
                char msg[256];
                snprintf(msg, sizeof(msg),
                    "O histórico (com a imagem) excede o contexto disponível (%d tokens de %d). Limpe a conversa ou aumente o contexto nas configurações.",
                    (int)n_tok_est, n_ctx_cur);
                mtmd_input_chunks_free(chunks);
                emit_error(env, msg);
                status = "error";
                break;
            }

            // imagem sempre força reprocessamento completo (sem KV reuse)
            llama_memory_t mem = llama_get_memory(g_ctx);
            llama_memory_seq_rm(mem, 0, 0, -1);
            g_cached_toks.clear();

            llama_pos new_n_past = 0;
            rc = mtmd_helper_eval_chunks(g_mctx, g_ctx, chunks, 0, 0, (int)llama_n_batch(g_ctx), true, &new_n_past);
            mtmd_input_chunks_free(chunks);
            if (rc != 0) {
                emit_error(env, "Falha ao decodificar a imagem no contexto.");
                status = "error";
                break;
            }
            n_past = (int)new_n_past;
            n_prompt = n_past;
        } else {
            // ---- path de texto (existente) ----
            std::vector<llama_chat_message> msgs;
            msgs.reserve(p.n_messages);
            for (int i = 0; i < p.n_messages; i++) {
                msgs.push_back({ p.roles[i].c_str(), p.contents[i].c_str() });
            }

            std::string prompt;
            int len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, nullptr, 0);
            if (len < 0) {
                // template not recognized by llama.cpp -> fall back to chatml
                len = llama_chat_apply_template(nullptr, msgs.data(), msgs.size(), true, nullptr, 0);
            }
            if (len < 0) {
                emit_error(env, "Não foi possível aplicar o template de chat deste modelo.");
                status = "error";
                break;
            }
            prompt.resize(len + 1);
            int len2 = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, &prompt[0], (int)prompt.size());
            if (len2 < 0) {
                len2 = llama_chat_apply_template(nullptr, msgs.data(), msgs.size(), true, &prompt[0], (int)prompt.size());
            }
            if (len2 < 0) {
                emit_error(env, "Não foi possível aplicar o template de chat deste modelo.");
                status = "error";
                break;
            }
            prompt.resize(len2);

            // 4) tokenize
            int n_max = (int)(prompt.size() + 8);
            toks.resize(n_max);
            int n_tok = llama_tokenize(g_vocab, prompt.c_str(), (int)prompt.size(),
                                       toks.data(), (int)toks.size(), true, true);
            if (n_tok < 0) {
                n_max = -n_tok;
                toks.resize(n_max);
                n_tok = llama_tokenize(g_vocab, prompt.c_str(), (int)prompt.size(),
                                       toks.data(), (int)toks.size(), true, true);
            }
            if (n_tok < 0 || (size_t)n_tok > toks.size()) {
                emit_error(env, "Erro ao tokenizar o prompt.");
                status = "error";
                break;
            }
            toks.resize(n_tok);
            n_prompt = n_tok;

            if (n_tok > n_ctx_cur - 32) {
                char msg[256];
                snprintf(msg, sizeof(msg),
                    "O histórico excede o contexto disponível (%d tokens de %d). Limpe a conversa ou aumente o contexto nas configurações.",
                    n_tok, n_ctx_cur);
                emit_error(env, msg);
                status = "error";
                break;
            }

            if (n_tok == 0) {
                emit_error(env, "Prompt vazio.");
                status = "error";
                break;
            }

            // 5) context alignment (multi-turn KV cache reuse)
            //
            // The cache is reused ONLY when the new tokenized history has the
            // cached token sequence as an exact prefix (token-for-token). Otherwise
            // the KV states would be paired with different tokens — the model
            // produces garbage and llama.cpp may abort. When the generated response
            // re-tokenizes differently from the generated token stream (BPE merges
            // across boundaries), the prefix won't match and we simply re-decode.
            llama_memory_t mem = llama_get_memory(g_ctx);
            bool cache_valid = false;
            if (!p.reset_context && !g_cached_toks.empty()) {
                const size_t clen = g_cached_toks.size();
                if ((size_t)n_tok >= clen) {
                    bool match = true;
                    for (size_t i = 0; i < clen; i++) {
                        if (g_cached_toks[i] != toks[i]) { match = false; break; }
                    }
                    const llama_pos pmax = llama_memory_seq_pos_max(mem, 0);
                    if (match && pmax + 1 >= (llama_pos)clen) cache_valid = true;
                }
            }
            if (!cache_valid) {
                llama_memory_seq_rm(mem, 0, 0, -1);
                g_cached_toks.clear();
            }
            n_past = cache_valid ? (int)g_cached_toks.size() : 0;

            // 6) decode the prompt in chunks of n_batch. llama.cpp aborts
            //    (GGML_ASSERT(n_tokens_all <= n_batch)) if a single batch exceeds
            //    n_batch — with a growing chat history the full prompt easily does,
            //    so decode it incrementally.
            if (n_tok > n_past) {
                const uint32_t chunk = std::max(1u, llama_n_batch(g_ctx));
                bool decode_ok = true;
                for (int pos = n_past; pos < n_tok && decode_ok; pos += (int)chunk) {
                    const int n_new = std::min(n_tok - pos, (int)chunk);
                    llama_batch batch = llama_batch_init(n_new, 0, 1);
                    batch.n_tokens = n_new;
                    for (int i = 0; i < n_new; i++) {
                        batch.token[i]    = toks[pos + i];
                        batch.pos[i]      = pos + i;
                        batch.n_seq_id[i] = 1;
                        batch.seq_id[i][0]= 0;
                        batch.logits[i]   = (pos + i == n_tok - 1);
                    }
                    const int r = llama_decode(g_ctx, batch);
                    llama_batch_free(batch);
                    if (r != 0) {
                        char msg[128];
                        snprintf(msg, sizeof(msg), "Falha ao processar o prompt (erro %d). Tente limpar a conversa.", r);
                        emit_error(env, msg);
                        status = "error";
                        decode_ok = false;
                    }
                }
                if (!decode_ok) break;
                n_past = n_tok;
            }
        }

        // 6) sampler chain
        llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
        const int n_vocab = llama_vocab_n_tokens(g_vocab);
        if (p.repeat_penalty > 0.0f && p.repeat_penalty != 1.0f) {
            llama_sampler_chain_add(smpl, llama_sampler_init_penalties(n_vocab, 64, p.repeat_penalty, 0.0f, 0.0f));
        }
        if (p.top_k > 0) {
            llama_sampler_chain_add(smpl, llama_sampler_init_top_k(p.top_k));
        }
        if (p.top_p > 0.0f && p.top_p < 1.0f) {
            llama_sampler_chain_add(smpl, llama_sampler_init_top_p(p.top_p, 1));
        }
        if (p.min_p > 0.0f) {
            llama_sampler_chain_add(smpl, llama_sampler_init_min_p(p.min_p, 1));
        }
        if (p.temp > 0.0f) {
            llama_sampler_chain_add(smpl, llama_sampler_init_temp(p.temp));
            llama_sampler_chain_add(smpl, llama_sampler_init_dist(p.seed));
        } else {
            llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
        }

        // 7) generation loop
        g_stop.store(false);
        const auto t0 = std::chrono::steady_clock::now();
        auto last_flush = t0;
        std::string pending; // holds incomplete UTF-8 sequences across tokens
        std::vector<llama_token> gen_toks; // tokens actually decoded into the cache
        gen_toks.reserve((size_t)p.max_tokens);

        while (true) {
            if (g_stop.load()) { status = "stopped"; break; }
            if (n_cur >= p.max_tokens) { status = "max_tokens"; break; }
            if (n_past + 1 >= n_ctx_cur) { status = "ctx_full"; break; }

            const llama_token tok = llama_sampler_sample(smpl, g_ctx, -1);
            if (tok == LLAMA_TOKEN_NULL) { status = "sampling_error"; break; }
            llama_sampler_accept(smpl, tok);

            if (llama_vocab_is_eog(g_vocab, tok)) break; // natural end

            llama_batch batch = llama_batch_get_one((llama_token *)&tok, 1);
            const int r = llama_decode(g_ctx, batch);
            if (r != 0) { LOGE("decode error %d", r); status = "decode_error"; break; }
            gen_toks.push_back(tok);
            n_past++;

            // detokenize (streaming with UTF-8 safety)
            char piece[256];
            int n = llama_token_to_piece(g_vocab, tok, piece, (int32_t)sizeof(piece), 0, false);
            if (n < 0) {
                std::vector<char> big(-n + 1);
                n = llama_token_to_piece(g_vocab, tok, big.data(), (int32_t)big.size(), 0, false);
                if (n > 0) pending.append(big.data(), (size_t)n);
            } else if (n > 0) {
                pending.append(piece, (size_t)n);
            }

            // Streaming em lote: acumula texto e só faz a chamada JNI quando
            // houver >= 96 bytes prontos ou ~32ms desde a última liberação.
            // Reduz drasticamente o overhead por token (strings/alloc no Kotlin)
            // mantendo o efeito "token a token".
            const size_t keep = utf8_keep_len(pending.data(), pending.size());
            if (keep > 0) {
                const auto now = std::chrono::steady_clock::now();
                const double since_ms = std::chrono::duration<double, std::milli>(now - last_flush).count();
                if (keep >= 96 || since_ms >= 32.0) {
                    emit_token(env, pending.data(), keep);
                    pending.erase(0, keep);
                    last_flush = now;
                }
            }
            n_cur++;
        }

        if (!pending.empty()) {
            emit_token(env, pending.data(), pending.size());
            pending.clear();
        }

        // remember what is now cached for the next turn's prefix check.
        // (No path com imagem o cache é sempre limpo: o histórico com imagem é
        // reprocessado por completo a cada turno.)
        if (status != "decode_error" && status != "sampling_error" && !vision_path) {
            g_cached_toks = toks;
            g_cached_toks.insert(g_cached_toks.end(), gen_toks.begin(), gen_toks.end());
        } else {
            g_cached_toks.clear();
        }

        const auto t1 = std::chrono::steady_clock::now();
        const double elapsed_s = std::chrono::duration<double>(t1 - t0).count();
        const double tps = n_cur > 0 && elapsed_s > 0.0 ? (double)n_cur / elapsed_s : 0.0;

        llama_sampler_free(smpl);

        char json[256];
        snprintf(json, sizeof(json),
            "{\"status\":\"%s\",\"tokens\":%d,\"prompt_tokens\":%d,\"elapsed_ms\":%.1f,\"tps\":%.2f}",
            status.c_str(), n_cur, n_prompt, elapsed_s * 1000.0, tps);
        emit_complete(env, json);

    } while (false);

    g_generating.store(false);
    if (attached) g_vm->DetachCurrentThread();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_forgeai_LlamaEngine_nativeGenerate(
        JNIEnv * env, jobject,
        jobjectArray jroles, jobjectArray jcontents, jobjectArray jimages,
        jfloat jtemp, jint jtop_k, jfloat jtop_p, jfloat jmin_p, jfloat jrepeat,
        jlong jseed, jint jmax_tokens, jint jthreads, jint jthreads_batch,
        jboolean jreset_context) {

    if (!g_loaded.load() || !g_model || !g_ctx) return JNI_FALSE;
    if (g_generating.exchange(true)) return JNI_FALSE;

    GenParams p;
    p.n_messages = jroles ? env->GetArrayLength(jroles) : 0;
    const int n_images = jimages ? env->GetArrayLength(jimages) : 0;
    // Copy message strings to C++ HERE, on the calling thread — JNI local
    // references are not valid on the worker thread and would crash the app.
    p.roles.reserve(p.n_messages);
    p.contents.reserve(p.n_messages);
    p.images.reserve(p.n_messages);
    for (int i = 0; i < p.n_messages; i++) {
        jstring r = (jstring)env->GetObjectArrayElement(jroles, i);
        jstring c = (jstring)env->GetObjectArrayElement(jcontents, i);
        jstring im = (jimages && i < n_images) ? (jstring)env->GetObjectArrayElement(jimages, i) : nullptr;
        const char * rc = r ? env->GetStringUTFChars(r, nullptr) : nullptr;
        const char * cc = c ? env->GetStringUTFChars(c, nullptr) : nullptr;
        const char * ic = im ? env->GetStringUTFChars(im, nullptr) : nullptr;
        p.roles.emplace_back(rc ? rc : "");
        p.contents.emplace_back(cc ? cc : "");
        p.images.emplace_back(ic ? ic : "");
        if (rc) env->ReleaseStringUTFChars(r, rc);
        if (cc) env->ReleaseStringUTFChars(c, cc);
        if (ic) env->ReleaseStringUTFChars(im, ic);
        if (r) env->DeleteLocalRef(r);
        if (c) env->DeleteLocalRef(c);
        if (im) env->DeleteLocalRef(im);
    }
    p.temp       = (float)jtemp;
    p.top_k      = (int)jtop_k;
    p.top_p      = (float)jtop_p;
    p.min_p      = (float)jmin_p;
    p.repeat_penalty = (float)jrepeat;
    p.seed       = (uint32_t)jseed;
    p.max_tokens = (int)jmax_tokens;
    p.n_threads  = (int)jthreads;
    p.n_threads_batch = (int)jthreads_batch;
    p.reset_context = (bool)jreset_context;

    // The previous worker has already finished (g_generating was false above),
    // but the std::thread object is still joinable — assigning a new thread to
    // a joinable std::thread calls std::terminate(), which aborts the app on
    // the second generation. Join it first.
    if (g_gen_thread.joinable()) g_gen_thread.join();
    g_gen_thread = std::thread(generation_worker, p);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_forgeai_LlamaEngine_nativeStop(JNIEnv *, jobject) {
    g_stop.store(true);
}
