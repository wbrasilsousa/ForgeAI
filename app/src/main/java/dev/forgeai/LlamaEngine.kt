package dev.forgeai

import android.content.Context
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * JNI bridge to libllamaforge.so (llama.cpp b10333).
 *
 * The native side calls back into [onToken], [onComplete], [onError] and
 * [onLoadProgress] from its own worker thread. Events are delivered through
 * a FIFO channel so the consumer is guaranteed to see, per generation:
 *   zero or more Token events, then exactly one Complete or Error event.
 */
object LlamaEngine {

    init {
        System.loadLibrary("llamaforge")
    }

    // ------------------------------------------------------------------
    // Native methods (implemented in llama_jni.cpp)
    // ------------------------------------------------------------------
    private external fun nativeInit(backendDir: String?): Boolean
    private external fun nativeGetSystemInfo(): String
    private external fun nativeVulkanDevices(): String
    private external fun nativeInspectModel(path: String): String?
    private external fun nativeLoadModel(
        path: String,
        mmprojPath: String?,
        nCtx: Int,
        nThreads: Int,
        nThreadsBatch: Int,
        nGpuLayers: Int
    ): Boolean

    /**
     * Threads para o processamento do prompt (batch): é computação paralela,
     * então vale usar todos os núcleos disponíveis (inclusive os A55). A
     * geração em si (decode, limitado por memória) continua usando as threads
     * configuradas pelo usuário.
     */
    private val autoBatchThreads: Int by lazy {
        minOf(8, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
    }

    private external fun nativeUnloadModel()
    private external fun nativeIsLoaded(): Boolean
    private external fun nativeGetLoadedInfo(): String?

    private external fun nativeGenerate(
        roles: Array<String>,
        contents: Array<String>,
        images: Array<String?>,
        temp: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        repeatPenalty: Float,
        seed: Long,
        maxTokens: Int,
        threads: Int,
        threadsBatch: Int,
        resetContext: Boolean
    ): Boolean

    private external fun nativeStop()
    private external fun nativeResetContext()

    // ------------------------------------------------------------------
    // Event plumbing (called from native threads)
    // ------------------------------------------------------------------
    sealed class Event {
        data class Token(val text: String) : Event()
        data class Complete(val stats: GenerationStats) : Event()
        data class Error(val message: String) : Event()
    }

    data class GenerationStats(
        val status: String,
        val tokens: Int,
        val promptTokens: Int,
        val elapsedMs: Double,
        val tps: Double
    ) {
        val finishedByMaxTokens: Boolean get() = status == "max_tokens"
        val finishedByContext: Boolean get() = status == "ctx_full"
        val stopped: Boolean get() = status == "stopped"
    }

    val events = Channel<Event>(Channel.UNLIMITED)
    val loadProgress = MutableStateFlow(0f)
    val engineReady = MutableStateFlow(false)
    val systemInfo = MutableStateFlow("")
    val gpuDevices = MutableStateFlow<List<String>>(emptyList())

    fun onToken(text: String) {
        events.trySend(Event.Token(text))
    }

    fun onComplete(json: String) {
        try {
            val o = JSONObject(json)
            events.trySend(
                Event.Complete(
                    GenerationStats(
                        status = o.optString("status", "ok"),
                        tokens = o.optInt("tokens", 0),
                        promptTokens = o.optInt("prompt_tokens", 0),
                        elapsedMs = o.optDouble("elapsed_ms", 0.0),
                        tps = o.optDouble("tps", 0.0)
                    )
                )
            )
        } catch (_: Exception) {
        }
    }

    fun onError(msg: String) {
        events.trySend(Event.Error(msg))
    }

    fun onLoadProgress(frac: Float) {
        loadProgress.value = frac.coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------------
    // Public API (called from the ViewModel)
    // ------------------------------------------------------------------
    @Synchronized
    fun init(context: Context) {
        if (engineReady.value) return
        // Diretório das libs nativas: necessário para builds com backend
        // dinâmico (FORGE_ALL_VARIANTS / GGML_BACKEND_DL) acharem o
        // libggml-cpu-*.so certo para o aparelho. No-op em builds estáticos.
        val backendDir = context.applicationInfo.nativeLibraryDir
        engineReady.value = runCatching { nativeInit(backendDir) }.getOrDefault(false)
        systemInfo.value = runCatching { nativeGetSystemInfo() }.getOrDefault("")
        gpuDevices.value = runCatching {
            val arr = nativeVulkanDevices()
            if (arr.isNullOrBlank()) emptyList() else {
                val j = JSONObject("{\"d\":$arr}")
                val a = j.getJSONArray("d")
                (0 until a.length()).map { a.getString(it) }
            }
        }.getOrDefault(emptyList())
    }

    fun vulkanAvailable(): Boolean = gpuDevices.value.isNotEmpty()

    fun inspectModel(path: String): JSONObject? =
        runCatching { nativeInspectModel(path)?.let { JSONObject(it) } }.getOrNull()

    fun loadModel(path: String, mmprojPath: String?, nCtx: Int, threads: Int, gpuLayers: Int): Boolean {
        loadProgress.value = 0f
        val ok = nativeLoadModel(path, mmprojPath, nCtx, threads, autoBatchThreads, gpuLayers)
        loadProgress.value = 1f
        return ok
    }

    fun unloadModel() = nativeUnloadModel()

    fun isLoaded(): Boolean = nativeIsLoaded()

    fun loadedInfo(): JSONObject? =
        runCatching { nativeGetLoadedInfo()?.let { JSONObject(it) } }.getOrNull()

    /**
     * Starts generation on a native worker thread.
     * Returns true if the generation was actually started.
     *
     * [history] is a list of (role, content); [images] holds the path of an
     * attached image per message (null = text-only message). Messages with an
     * image are formatted by the native side with the vision marker so the
     * mtmd encoder can embed the picture.
     */
    fun generate(
        history: List<Triple<String, String, String?>>, // (role, content, imagePath?)
        temp: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        repeatPenalty: Float,
        seed: Long,
        maxTokens: Int,
        threads: Int,
        resetContext: Boolean
    ): Boolean {
        val roles = history.map { it.first }.toTypedArray()
        val contents = history.map { it.second }.toTypedArray()
        val images = history.map { it.third }.toTypedArray()
        return nativeGenerate(
            roles, contents, images,
            temp, topK, topP, minP, repeatPenalty,
            seed, maxTokens, threads, autoBatchThreads, resetContext
        )
    }

    fun stop() = nativeStop()
    fun resetContext() = nativeResetContext()
}
