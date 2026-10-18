package dev.forgeai.ui

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.forgeai.LlamaEngine
import dev.forgeai.core.AppPrefs
import dev.forgeai.core.AppSettings
import dev.forgeai.core.ModelSettings
import dev.forgeai.data.HfResolver
import dev.forgeai.data.ModelDownloader
import dev.forgeai.data.ModelInfo
import dev.forgeai.data.ModelRepository
import dev.forgeai.data.ToolFormat
import dev.forgeai.data.ToolSupport
import dev.forgeai.data.WebAccess
import dev.forgeai.data.WebSearch
import dev.forgeai.ui.components.isOpenThinkAnswer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

enum class ChatRole { USER, ASSISTANT, SYSTEM, TOOL }

data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val content: String,
    val error: Boolean = false,
    val streaming: Boolean = false,
    val info: String? = null,
    /** Caminho de uma imagem anexada (mensagens do usuário com visão). */
    val imagePath: String? = null,
)

/** Summary of a saved conversation (shown in the history drawer). */
data class ChatSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
)

sealed interface EngineStatus {
    data object Idle : EngineStatus
    data object Loading : EngineStatus
    data object Ready : EngineStatus
    data object Generating : EngineStatus
}

sealed interface UrlState {
    data object Idle : UrlState
    data object Loading : UrlState
    data class Ready(val repo: String, val files: List<HfResolver.HfFile>) : UrlState
    data class Error(val message: String) : UrlState
}

/** RAM do aparelho, para o badge de compatibilidade (TD-25). */
data class RamInfo(
    val availableBytes: Long,
    val totalBytes: Long,
    val lowMemory: Boolean = false,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPrefs(app)
    private val repo = ModelRepository(app)
    private val downloader = ModelDownloader(app)

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------
    var settings by mutableStateOf(prefs.load())
        private set

    /** Configurações de inferência do modelo atualmente carregado (por modelo). */
    var modelSettings by mutableStateOf(ModelSettings())
        private set

    var models by mutableStateOf<List<ModelInfo>>(emptyList())
        private set

    var messages by mutableStateOf<List<ChatMessage>>(emptyList())
        private set

    /** All saved conversations (most recent first) — shown in the drawer. */
    var chats by mutableStateOf<List<ChatSummary>>(emptyList())
        private set

    /** The conversation currently open in the chat tab. */
    var currentChatId by mutableStateOf<String?>(null)
        private set

    var engineStatus by mutableStateOf<EngineStatus>(EngineStatus.Idle)
        private set

    var loadedModel by mutableStateOf<ModelInfo?>(null)
        private set

    /** True quando o modelo carregado tem suporte nativo a tools. */
    var modelSupportsTools by mutableStateOf(false)
        private set

    /** True quando o modelo carregado tem encoder de visão (mmproj). */
    var modelSupportsVision by mutableStateOf(false)
        private set

    /** Formato de tools do modelo carregado (Qwen2.5 JSON / Qwen3.x XML). */
    var modelToolFormat by mutableStateOf(ToolFormat.NONE)
        private set

    var lastStats by mutableStateOf<LlamaEngine.GenerationStats?>(null)
        private set

    var streamingText by mutableStateOf("")
        private set

    /**
     * True when the loaded model writes its answer inside <think> and never
     * closes the tag (Bonsai Q1_0, etc.). Such models must stream the open
     * block live, otherwise the user stares at the typing indicator for the
     * whole generation and the answer only appears at the end.
     */
    var streamOpenThink by mutableStateOf(false)
        private set

    var urlState by mutableStateOf<UrlState>(UrlState.Idle)
        private set

    /** Cached storage info: (free, used, total) — refreshed on demand. */
    var storage by mutableStateOf(Triple(0L, 0L, 0L))
        private set

    /** RAM livre/total do aparelho (badge de compatibilidade dos modelos). */
    var ram by mutableStateOf(RamInfo(0L, 0L))
        private set

    val downloadTasks: StateFlow<Map<String, ModelDownloader.Task>> = downloader.tasks
    val loadProgress = LlamaEngine.loadProgress
    val engineReady = LlamaEngine.engineReady
    val systemInfo = LlamaEngine.systemInfo
    val gpuDevices = LlamaEngine.gpuDevices

    private val _snack = MutableStateFlow<Pair<Long, String>?>(null)
    val snack: StateFlow<Pair<Long, String>?> = _snack

    private var pendingReset = false
    private var generationJob: Job? = null
    private var lastDoneCount = 0

    /** In-memory messages of every saved conversation, keyed by chat id. */
    private var chatStore = mutableMapOf<String, MutableList<ChatMessage>>()

    val isGenerating: Boolean get() = engineStatus is EngineStatus.Generating
    val vulkanAvailable: Boolean get() = LlamaEngine.vulkanAvailable()

    /** versionName real do APK (não hardcodar na tela de Ajustes). */
    val appVersion: String by lazy {
        runCatching {
            val app = getApplication<Application>()
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
        }.getOrDefault("")
    }

    /** Web access efetivamente ativo: ligado em Ajustes E modelo com suporte. */
    val webAccessEnabled: Boolean get() = settings.webAccess && modelSupportsTools

    // ------------------------------------------------------------------
    // Init
    // ------------------------------------------------------------------
    init {
        LlamaEngine.init(getApplication())
        refreshModels()
        refreshStorage()
        loadChats()
        // resume downloads that were interrupted (e.g. app killed mid-download)
        viewModelScope.launch(Dispatchers.IO) {
            downloader.resumeInterrupted(viewModelScope)
        }
        prefs.getString(AppPrefs.KEY_LAST_MODEL)?.let { path ->
            if (java.io.File(path).exists()) {
                loadModel(path)
            }
        }
        viewModelScope.launch {
            downloader.tasks.collectLatest { tasks ->
                val done = tasks.values.count { it.status == ModelDownloader.Status.DONE }
                if (done > lastDoneCount) {
                    lastDoneCount = done
                    refreshModels()
                    refreshStorage()
                    showSnack("Download concluído")
                }
            }
        }
    }

    fun refreshStorage() {
        storage = runCatching {
            Triple(repo.freeSpaceBytes(), repo.usedSpaceBytes(), repo.totalSpaceBytes())
        }.getOrDefault(Triple(0L, 0L, 0L))
        refreshRam()
    }

    /** Lê a RAM livre/total do sistema (usado no fit badge). */
    fun refreshRam() {
        ram = runCatching {
            val am = getApplication<Application>()
                .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            RamInfo(mi.availMem, mi.totalMem, mi.lowMemory)
        }.getOrDefault(RamInfo(0L, 0L))
    }

    fun showSnack(msg: String) {
        _snack.value = System.nanoTime() to msg
    }

    fun consumeSnack() {
        _snack.value = null
    }

    // ------------------------------------------------------------------
    // Models
    // ------------------------------------------------------------------
    fun refreshModels(forceInspect: Boolean = false) {
        viewModelScope.launch {
            val list = withContext(Dispatchers.Default) { repo.listModels() }
            val needInspect = list.any { !it.metaLoaded || forceInspect }
            val result = if (needInspect) {
                withContext(Dispatchers.Default) {
                    list.map { m ->
                        if (!m.metaLoaded || forceInspect) repo.inspect(m) else m
                    }
                }
            } else list
            models = result.map { it.copy(isLoaded = it.path == loadedModel?.path) }
        }
    }

    fun loadModel(path: String) {
        if (isGenerating) {
            showSnack("Aguarde o fim da geração para trocar de modelo")
            return
        }
        val ms = prefs.loadModelSettings(path)
        modelSettings = ms
        val gpu = if (vulkanAvailable) ms.gpuLayers else 0
        viewModelScope.launch {
            engineStatus = EngineStatus.Loading
            var mmproj: String? = null
            val ok = withContext(Dispatchers.IO) {
                // Resolve o encoder de visão direto do disco: na inicialização a
                // lista `models` ainda pode estar vazia (race), o que desligava a
                // visão silenciosamente ao recarregar o último modelo.
                mmproj = repo.mmprojFor(path)
                LlamaEngine.loadModel(
                    path, mmproj, ms.nCtx, ms.threads, gpu, ms.imageTokens,
                )
            }
            if (ok) {
                engineStatus = EngineStatus.Ready
                pendingReset = true
                streamOpenThink = prefs.isOpenThinkModel(path)
                modelSupportsVision = runCatching {
                    LlamaEngine.loadedInfo()?.optBoolean("has_vision", false) == true
                }.getOrDefault(false)
                if (!modelSupportsVision && mmproj != null) {
                    showSnack("Encoder de imagens (mmproj) não carregou — a visão ficará desligada.")
                }
                modelSupportsTools = runCatching {
                    val info = LlamaEngine.loadedInfo()
                    ToolSupport.templateSupportsTools(
                        info?.optString("template_full")?.takeIf { it.isNotBlank() }
                            ?: info?.optString("template")
                    )
                }.getOrDefault(false)
                modelToolFormat = runCatching {
                    val info = LlamaEngine.loadedInfo()
                    ToolSupport.detectFormat(
                        info?.optString("template_full")?.takeIf { it.isNotBlank() }
                            ?: info?.optString("template")
                    )
                }.getOrDefault(ToolFormat.NONE)
                if (settings.webAccess) {
                    if (modelSupportsTools) {
                        showSnack("🌐 Web access ativo — o modelo pode navegar na internet")
                    } else {
                        showSnack("Web access está ligado, mas este modelo não suporta ferramentas")
                    }
                }
                // Seed the per-model behavior from responses already stored in
                // this chat (e.g. a previous Bonsai turn that never closed
                // <think>) so the very next generation already streams live.
                if (!streamOpenThink) {
                    val seeded = messages.any { it.role == ChatRole.ASSISTANT && isOpenThinkAnswer(it.content) }
                    if (seeded) {
                        prefs.setOpenThinkModel(path, true)
                        streamOpenThink = true
                    }
                }
                loadedModel = models.firstOrNull { it.path == path }?.copy(isLoaded = true)
                prefs.putString(AppPrefs.KEY_LAST_MODEL, path)
                // refresh the "loaded" badge
                models = models.map { it.copy(isLoaded = it.path == path) }
            } else {
                engineStatus = EngineStatus.Idle
                val stale = LlamaEngine.events.tryReceive().getOrNull()
                val msg = (stale as? LlamaEngine.Event.Error)?.message ?: "Falha ao carregar o modelo"
                showSnack(msg)
            }
        }
    }

    fun reloadModel() {
        val m = loadedModel ?: return
        loadModel(m.path)
    }

    fun unloadModel() {
        if (isGenerating) LlamaEngine.stop()
        viewModelScope.launch(Dispatchers.IO) {
            LlamaEngine.unloadModel()
        }
        loadedModel = null
        engineStatus = EngineStatus.Idle
        pendingReset = true
        streamOpenThink = false
        modelSupportsTools = false
        modelSupportsVision = false
        modelToolFormat = ToolFormat.NONE
        modelSettings = ModelSettings()
        prefs.putString(AppPrefs.KEY_LAST_MODEL, "")
        models = models.map { it.copy(isLoaded = false) }
    }

    fun deleteModel(model: ModelInfo) {
        if (model.isLoaded) unloadModel()
        viewModelScope.launch(Dispatchers.IO) {
            repo.delete(model)
            withContext(Dispatchers.Main) {
                refreshModels()
                refreshStorage()
            }
        }
    }

    fun downloadModel(repoName: String, fileName: String, size: Long?, partCount: Int = 1, mmprojName: String? = null) {
        downloader.start(viewModelScope, repoName, fileName, size, partCount)
        // modelos VL precisam do encoder de visão (mmproj-*.gguf) do mesmo repo;
        // o downloader ignora arquivos que já existem em disco.
        if (!mmprojName.isNullOrBlank()) {
            downloader.start(viewModelScope, repoName, mmprojName, null, 1)
        }
        urlState = UrlState.Idle
    }

    fun cancelDownload(id: String) = downloader.cancel(id)

    fun removeDownload(id: String) = downloader.remove(id)

    fun storageInfo(): Triple<Long, Long, Long> = storage

    // ------------------------------------------------------------------
    // HuggingFace URL resolution
    // ------------------------------------------------------------------
    fun resolveUrl(raw: String, silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) urlState = UrlState.Loading
            try {
                val source = HfResolver.parseUrl(raw)
                val files = HfResolver.mergeShards(HfResolver.listFiles(source.repo, source.file))
                urlState = UrlState.Ready(source.repo, files)
            } catch (e: HfResolver.HfException) {
                if (!silent) urlState = UrlState.Error(e.message ?: "Erro")
            } catch (e: Exception) {
                if (!silent) urlState = UrlState.Error(e.message ?: "Erro inesperado")
            }
        }
    }

    fun retryDownload(id: String) {
        downloader.retry(viewModelScope, id)
    }

    // ------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------
    /**
     * Copia a imagem escolhida (content URI) para o diretório do app e devolve
     * o caminho do arquivo — persistente para o histórico e legível pelo JNI.
     */
    fun importImage(uri: android.net.Uri): String? {
        val dir = File(getApplication<Application>().filesDir, "images").apply { mkdirs() }
        val out = File(dir, "img_${System.currentTimeMillis()}.jpg")
        return try {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            // Imagens acima do alvo disparam o tiling do encoder e a codificação
            // em CPU fica lenta o bastante para parecer travamento. O alvo
            // acompanha a qualidade escolhida (imageTokens): 512 tok -> 768 px,
            // 1024 -> 1024 px, 1536+ -> 1280 px.
            downscaleIfNeeded(out, maxDim = maxDimForImageTokens(modelSettings.imageTokens))
            if (out.exists() && out.length() > 0) out.absolutePath else null
        } catch (e: Exception) {
            runCatching { out.delete() }
            null
        }
    }

    /** Mapeia a qualidade da imagem (tokens do encoder) para o lado máximo em px. */
    private fun maxDimForImageTokens(tokens: Int): Int = when {
        tokens <= 512 -> 768
        tokens <= 1024 -> 1024
        else -> 1280
    }

    /** Reamostra a imagem em disco quando o lado maior excede [maxDim]. */
    private fun downscaleIfNeeded(file: File, maxDim: Int) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0 || maxOf(w, h) <= maxDim) return
        // Decodifica por amostragem (potência de 2) até ficar próximo do alvo e
        // depois reescala com precisão — evita alocar a imagem inteira em RAM.
        var sample = 1
        while (maxOf(w / sample, h / sample) > maxDim * 2) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return
        val scale = maxDim.toFloat() / maxOf(decoded.width, decoded.height)
        val outW = maxOf(1, (decoded.width * scale).toInt())
        val outH = maxOf(1, (decoded.height * scale).toInt())
        val scaled = if (outW != decoded.width || outH != decoded.height) {
            Bitmap.createScaledBitmap(decoded, outW, outH, true)
        } else decoded
        if (scaled !== decoded) decoded.recycle()
        try {
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
        } finally {
            scaled.recycle()
        }
    }

    fun send(text: String, imagePath: String? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && imagePath == null) return
        if (engineStatus != EngineStatus.Ready) {
            showSnack("Carregue um modelo antes de conversar")
            return
        }
        if (imagePath != null && !modelSupportsVision) {
            showSnack("O modelo carregado não suporta imagens. Baixe e carregue um modelo VL com mmproj.")
            return
        }
        appendMessage(ChatMessage(UUID.randomUUID().toString(), ChatRole.USER, trimmed, imagePath = imagePath))
        startGeneration()
    }

    fun regenerate() {
        if (isGenerating) return
        if (engineStatus != EngineStatus.Ready) return
        val last = messages.lastOrNull() ?: return
        if (last.role != ChatRole.ASSISTANT) return
        messages = messages.dropLast(1)
        persist()
        pendingReset = true
        startGeneration()
    }

    fun stop() {
        LlamaEngine.stop()
    }

    fun clearChat() {
        generationJob?.cancel()
        LlamaEngine.stop()
        messages = emptyList()
        streamingText = ""
        lastStats = null
        pendingReset = true
        persist()
        viewModelScope.launch(Dispatchers.IO) { LlamaEngine.resetContext() }
    }

    // ------------------------------------------------------------------
    // Conversation management (new chat / history)
    // ------------------------------------------------------------------
    fun newChat() {
        if (isGenerating) LlamaEngine.stop()
        generationJob?.cancel()
        persist() // save the conversation we're leaving
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        chatStore[id] = mutableListOf()
        chats = listOf(ChatSummary(id, "Nova conversa", now)) + chats.filter { it.id != id }
        currentChatId = id
        messages = emptyList()
        streamingText = ""
        lastStats = null
        pendingReset = true
        writeChats()
    }

    fun openChat(id: String) {
        if (id == currentChatId) return
        if (isGenerating) LlamaEngine.stop()
        generationJob?.cancel()
        persist() // save the conversation we're leaving
        currentChatId = id
        messages = chatStore[id] ?: emptyList()
        streamingText = ""
        lastStats = null
        pendingReset = true
        writeChats()
    }

    fun deleteChat(id: String) {
        chatStore.remove(id)
        val remaining = chats.filter { it.id != id }
        chats = remaining
        if (id == currentChatId) {
            val next = remaining.firstOrNull()
            if (next != null) {
                currentChatId = next.id
                messages = chatStore[next.id] ?: emptyList()
            } else {
                val nid = UUID.randomUUID().toString()
                chatStore[nid] = mutableListOf()
                chats = listOf(ChatSummary(nid, "Nova conversa", System.currentTimeMillis()))
                currentChatId = nid
                messages = emptyList()
            }
            streamingText = ""
            lastStats = null
            pendingReset = true
        }
        writeChats()
        showSnack("Conversa excluída")
    }

    private fun startGeneration() {
        if (generationJob?.isActive == true) return
        if (engineStatus != EngineStatus.Ready) return
        val model = loadedModel ?: return
        val ms = modelSettings
        val s = settings

        trimHistoryIfNeeded(ms.nCtx, s)

        engineStatus = EngineStatus.Generating
        lastStats = null
        streamingText = ""
        val reset = pendingReset
        pendingReset = false

        generationJob = viewModelScope.launch {
            var needReset = reset
            var rounds = 0
            var retriedTool = false
            var lastToolKey: String? = null
            val maxRounds = 6 // 1 resposta final + até 5 rodadas de tool (busca → acesso → …)

            while (true) {
                // streaming placeholder for this model round
                appendMessage(ChatMessage(UUID.randomUUID().toString(), ChatRole.ASSISTANT, "", streaming = true))

                // Drain stale events left by a previous/aborted generation so
                // this turn's event loop cannot confuse old tokens/Complete with new ones.
                while (LlamaEngine.events.tryReceive().getOrNull() != null) {}

                val outcome = runGenerationRound(needReset, ms)
                needReset = false

                when (outcome) {
                    is RoundOutcome.Final -> {
                        if (ToolSupport.looksLikeToolAttempt(outcome.text)) {
                            if (webAccessEnabled && !retriedTool) {
                                // 1ª tentativa quebrada: mostra resumo e dá uma
                                // chance extra com a correção do formato.
                                retriedTool = true
                                finalizeAssistant(outcome.text, "⚠️ chamada de ferramenta incompleta — tentando de novo…")
                                appendMessage(
                                    ChatMessage(
                                        UUID.randomUUID().toString(),
                                        ChatRole.TOOL,
                                        ToolSupport.buildCorrectionMessage(modelToolFormat),
                                        info = "🔁 corrigindo formato da chamada…",
                                    )
                                )
                                continue
                            }
                            // Nunca vazar o JSON/XML cru para o usuário.
                            val msg = if (webAccessEnabled) {
                                "⚠️ O modelo tentou buscar na web, mas a chamada ficou incompleta. Reformule a pergunta."
                            } else {
                                "⚠️ O modelo tentou usar uma ferramenta de web, mas o Web access está desligado. Ative em Ajustes → Ferramentas."
                            }
                            finalizeAssistant(msg, null, error = true)
                        } else {
                            finalizeAssistant(outcome.text, outcome.info)
                            rememberOpenThinkModel(outcome.text)
                        }
                        break
                    }
                    is RoundOutcome.Error -> {
                        if (outcome.dropPlaceholder) {
                            messages = messages.dropLast(1)
                            persist()
                            showSnack(outcome.message)
                        } else {
                            finalizeAssistant(outcome.partialText.ifEmpty { null }, outcome.message, error = true)
                        }
                        break
                    }
                    is RoundOutcome.ToolCall -> {
                        if (!webAccessEnabled) {
                            // O modelo emitiu uma chamada mesmo com web access desligado:
                            // não executa a tool (privacidade) e explica ao usuário.
                            finalizeAssistant(
                                "⚠️ O modelo tentou usar a ferramenta ${outcome.call.name}, mas o Web access está desligado. Ative em Ajustes → Ferramentas.",
                                null,
                                error = true,
                            )
                            break
                        }
                        // Modelos pequenos (ex.: LFM2.5-VL-3B) às vezes respondem a um
                        // "olá" com uma chamada e, depois do resultado, chamam de novo
                        // a MESMA ferramenta — vira um loop que parece um travamento.
                        // Detecta a repetição e encerra com uma mensagem clara.
                        val toolKey = "${outcome.call.name}\u0000${outcome.call.primaryValue}"
                        if (toolKey == lastToolKey) {
                            finalizeAssistant(
                                "⚠️ O modelo repetiu a mesma chamada de ferramenta. Interrompi para evitar um loop — reformule a pergunta ou desligue o Web access em Ajustes.",
                                null,
                                error = true,
                            )
                            break
                        }
                        lastToolKey = toolKey
                        // O texto cru da chamada fica no histórico (o modelo precisa
                        // ver a própria chamada antes do resultado da tool) e a UI
                        // mostra um balão compacto em vez do JSON bruto.
                        finalizeAssistant(outcome.call.rawText, "🔧 chamando ${outcome.call.name}…")
                        rounds++
                        if (rounds >= maxRounds) {
                            appendMessage(
                                ChatMessage(
                                    UUID.randomUUID().toString(),
                                    ChatRole.ASSISTANT,
                                    "Não consegui concluir a navegação na web após várias tentativas. Tente reformular a pergunta ou verificar a URL.",
                                    error = true,
                                )
                            )
                            break
                        }
                        val (modelText, displayInfo) = executeTool(outcome.call)
                        appendMessage(
                            ChatMessage(
                                UUID.randomUUID().toString(),
                                ChatRole.TOOL,
                                modelText,
                                info = displayInfo,
                            )
                        )
                    }
                }
            }
            engineStatus = EngineStatus.Ready
            streamingText = ""
        }
    }

    /** Resultado de uma rodada de geração (resposta final, chamada de tool ou erro). */
    private sealed interface RoundOutcome {
        data class Final(val text: String, val info: String?) : RoundOutcome
        data class ToolCall(val call: ToolSupport.ToolCall) : RoundOutcome
        data class Error(
            val message: String,
            val partialText: String = "",
            val dropPlaceholder: Boolean = false,
        ) : RoundOutcome
    }

    /**
     * Uma rodada do agente: monta o histórico, gera e classifica o resultado.
     * Se o modelo pediu uma tool, retorna [RoundOutcome.ToolCall] para o loop
     * executar a ferramenta e rodar uma nova rodada.
     */
    private suspend fun runGenerationRound(resetContext: Boolean, ms: ModelSettings): RoundOutcome {
        val history = buildHistory()
        val seed = if (ms.randomSeed) {
            ((System.nanoTime() shl 1) xor System.currentTimeMillis()) and Long.MAX_VALUE
        } else ms.fixedSeed

        val started = withContext(Dispatchers.IO) {
            // histórico com imagem força reprocessamento completo (sem KV reuse)
            val hasImage = history.any { it.third != null }
            LlamaEngine.generate(
                history = history,
                temp = ms.temperature,
                topK = ms.topK,
                topP = ms.topP,
                minP = ms.minP,
                repeatPenalty = ms.repeatPenalty,
                seed = seed,
                maxTokens = ms.maxTokens,
                threads = ms.threads,
                resetContext = resetContext || hasImage,
            )
        }
        if (!started) {
            return RoundOutcome.Error("Falha ao iniciar a geração", dropPlaceholder = true)
        }

        val sb = StringBuilder()
        var done = false
        var outcome: RoundOutcome = RoundOutcome.Error("Geração interrompida")

        withTimeoutOrNull(300_000L) {
            while (!done) {
                val event = LlamaEngine.events.receiveCatching().getOrNull() ?: break
                when (event) {
                    is LlamaEngine.Event.Token -> {
                        sb.append(event.text)
                        streamingText = sb.toString()
                        updateStreamingMessage(sb.toString())
                    }
                    is LlamaEngine.Event.Complete -> {
                        lastStats = event.stats
                        val text = sb.toString()
                        val toolCall = ToolSupport.parseToolCall(text)
                        outcome = if (toolCall != null) {
                            RoundOutcome.ToolCall(toolCall)
                        } else {
                            val info = when {
                                event.stats.finishedByContext ->
                                    "Contexto cheio — resposta interrompida. Limpe a conversa ou aumente o contexto."
                                event.stats.finishedByMaxTokens -> "Limite de tokens atingido."
                                event.stats.stopped -> "Geração interrompida."
                                else -> null
                            }
                            RoundOutcome.Final(text, info)
                        }
                        done = true
                    }
                    is LlamaEngine.Event.Error -> {
                        outcome = RoundOutcome.Error(event.message, sb.toString())
                        done = true
                    }
                }
            }
        }
        if (!done) {
            // O timeout cancela só a espera por eventos; a thread nativa continua
            // rodando (a codificação de imagem não observa o g_stop). Sinaliza a
            // parada para que, se a codificação terminar, a geração não continue
            // em segundo plano e atrapalhe o próximo turno.
            LlamaEngine.stop()
            outcome = RoundOutcome.Error("Tempo de geração esgotado — a imagem pode ser grande demais para este aparelho. Tente uma imagem menor.", sb.toString())
        }
        return outcome
    }

    /** Executa a tool chamada pelo modelo e devolve (texto para o modelo, resumo para a UI). */
    private suspend fun executeTool(call: ToolSupport.ToolCall): Pair<String, String> = when (call.name) {
        ToolSupport.SEARCH_NAME -> executeSearch(call)
        else -> executeWebAccess(call)
    }

    private suspend fun executeSearch(call: ToolSupport.ToolCall): Pair<String, String> {
        val q = call.query
        if (q.isBlank()) {
            return ToolSupport.buildToolResultContent(false, "", null, null, "query vazia na chamada da ferramenta") to "❌ query vazia"
        }
        val result = WebSearch.search(q)
        return result.fold(
            onSuccess = { results ->
                val content = WebSearch.formatResults(q, results)
                val summary = "🔍 ${results.size} resultado(s) · \"${q.take(40)}\""
                content to summary
            },
            onFailure = { e ->
                val msg = e.message ?: "erro de rede"
                ToolSupport.buildToolResultContent(false, "", null, null, msg) to "❌ $msg"
            },
        )
    }

    private suspend fun executeWebAccess(call: ToolSupport.ToolCall): Pair<String, String> {
        val url = call.url
        if (url.isBlank()) {
            val content = ToolSupport.buildToolResultContent(false, "", null, null, "URL vazia na chamada da ferramenta")
            return content to "❌ URL vazia na chamada da ferramenta"
        }
        val result = WebAccess.fetch(url)
        return result.fold(
            onSuccess = { page ->
                val summary = buildString {
                    append("🌐 ")
                    append(page.title?.take(60) ?: page.url.take(40))
                    append(" · ")
                    append(page.text.length)
                    append(" caracteres")
                }
                ToolSupport.buildToolResultContent(true, page.url, page.title, page.text, null) to summary
            },
            onFailure = { e ->
                val msg = e.message ?: "erro de rede"
                ToolSupport.buildToolResultContent(false, url, null, null, msg) to "❌ $msg"
            },
        )
    }

    /**
     * If the finished response is an unclosed <think> block that looks like a
     * complete answer, remember that this model behaves that way so the next
     * generations stream live instead of waiting for the whole turn.
     */
    private fun rememberOpenThinkModel(raw: String) {
        val model = loadedModel ?: return
        if (!isOpenThinkAnswer(raw)) return
        if (!prefs.isOpenThinkModel(model.path)) {
            prefs.setOpenThinkModel(model.path, true)
        }
        streamOpenThink = true
    }

    private fun buildHistory(): List<Triple<String, String, String?>> {
        val list = mutableListOf<Triple<String, String, String?>>()
        val sys = settings.systemPrompt.trim()
        val toolsOn = settings.webAccess && modelSupportsTools
        if (sys.isNotEmpty() || toolsOn) {
            val sb = StringBuilder()
            if (toolsOn) {
                // Seção de tools no formato do modelo (Qwen2.5 JSON / Qwen3.x XML /
                // LFM2.5 Pythonic), antes do system prompt (mesma ordem do template).
                sb.append(ToolSupport.buildSystemToolsSection(modelToolFormat)).append("\n\n")
            }
            if (sys.isNotEmpty()) sb.append(sys)
            list.add(Triple("system", sb.toString(), null))
        }
        for (m in messages) {
            when (m.role) {
                ChatRole.USER -> list.add(Triple("user", m.content, m.imagePath))
                ChatRole.ASSISTANT ->
                    if (m.content.isNotEmpty() && !m.error) list.add(Triple("assistant", m.content, null))
                ChatRole.TOOL -> if (m.content.isNotEmpty()) {
                    if (modelToolFormat == ToolFormat.QWEN35_XML) {
                        // Qwen3.x espera o resultado da tool como mensagem user
                        // com <tool_response>...</tool_response>.
                        list.add(Triple("user", "<tool_response>\n${m.content}\n</tool_response>", null))
                    } else {
                        // ChatML (LFM2.5) e Qwen2.5 usam o role "tool" nativo.
                        list.add(Triple("tool", m.content, null))
                    }
                }
                ChatRole.SYSTEM -> {}
            }
        }
        return list
    }

    private fun trimHistoryIfNeeded(nCtx: Int, s: AppSettings) {
        val budget = nCtx * 4
        var total = messages.sumOf { it.content.length } + s.systemPrompt.length
        if (total <= budget) return
        val kept = messages.toMutableList()
        var i = 0
        while (i < kept.size && total > budget) {
            val m = kept[i]
            if (m.role != ChatRole.SYSTEM) {
                total -= m.content.length
                kept.removeAt(i)
            } else {
                i++
            }
        }
        if (kept.size != messages.size) {
            pendingReset = true
            messages = kept
            persist()
            showSnack("Histórico antigo removido para caber no contexto")
        }
    }

    private fun appendMessage(msg: ChatMessage) {
        messages = messages + msg
        persist()
    }

    private fun updateStreamingMessage(text: String) {
        messages = messages.map {
            if (it.streaming) it.copy(content = text) else it
        }
    }

    private fun finalizeAssistant(content: String?, info: String?, error: Boolean = false) {
        messages = messages.map { m ->
            if (m.streaming) {
                m.copy(
                    streaming = false,
                    content = content ?: "",
                    error = error,
                    info = info,
                )
            } else m
        }
        persist()
    }

    private fun persist() {
        val id = currentChatId ?: return
        // streaming placeholders (typing cursor, partial text) are never stored
        chatStore[id] = messages.filterNot { it.streaming }.toMutableList()
        val title = deriveTitle(messages)
        chats = listOf(ChatSummary(id, title, System.currentTimeMillis())) +
            chats.filter { it.id != id }
        writeChats()
    }

    private fun writeChats() {
        val arr = JSONArray()
        for (c in chats) {
            val o = JSONObject()
            o.put("id", c.id)
            o.put("title", c.title)
            o.put("updatedAt", c.updatedAt)
            val mArr = JSONArray()
            for (m in chatStore[c.id] ?: emptyList()) {
                val mo = JSONObject()
                mo.put("role", m.role.name)
                mo.put("content", m.content)
                mo.put("error", m.error)
                if (m.imagePath != null) mo.put("image", m.imagePath)
                mArr.put(mo)
            }
            o.put("messages", mArr)
            arr.put(o)
        }
        prefs.putString(AppPrefs.KEY_CHATS, arr.toString())
        prefs.putString(AppPrefs.KEY_ACTIVE_CHAT, currentChatId ?: "")
    }

    private fun loadChats() {
        val list = mutableListOf<ChatSummary>()
        val store = mutableMapOf<String, MutableList<ChatMessage>>()
        val raw = prefs.getString(AppPrefs.KEY_CHATS)
        if (!raw.isNullOrEmpty()) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("id").ifEmpty { UUID.randomUUID().toString() }
                    val title = o.optString("title").ifEmpty { "Nova conversa" }
                    val updated = o.optLong("updatedAt", System.currentTimeMillis())
                    val msgs = parseMessages(o.optJSONArray("messages"))
                    store[id] = msgs.toMutableList()
                    list += ChatSummary(id, title, updated)
                }
            }
        }
        // migrate the old single-chat format, if present
        if (list.isEmpty()) {
            prefs.getString(AppPrefs.KEY_CHAT)?.let { old ->
                runCatching {
                    val msgs = parseMessages(JSONArray(old))
                    if (msgs.isNotEmpty()) {
                        val id = UUID.randomUUID().toString()
                        store[id] = msgs.toMutableList()
                        list += ChatSummary(id, deriveTitle(msgs), System.currentTimeMillis())
                    }
                }
            }
        }
        chatStore = store
        chats = list.sortedByDescending { it.updatedAt }
        val active = prefs.getString(AppPrefs.KEY_ACTIVE_CHAT)
        currentChatId = chats.firstOrNull { it.id == active }?.id ?: chats.firstOrNull()?.id
        messages = currentChatId?.let { store[it] } ?: emptyList()
        if (currentChatId == null) {
            val id = UUID.randomUUID().toString()
            chatStore[id] = mutableListOf()
            chats = listOf(ChatSummary(id, "Nova conversa", System.currentTimeMillis()))
            currentChatId = id
        }
    }

    private fun parseMessages(arr: JSONArray?): List<ChatMessage> {
        if (arr == null) return emptyList()
        val out = mutableListOf<ChatMessage>()
        for (i in 0 until arr.length()) {
            runCatching {
                val o = arr.getJSONObject(i)
                val role = runCatching { ChatRole.valueOf(o.optString("role")) }.getOrNull()
                    ?: return@runCatching
                if (role == ChatRole.SYSTEM) return@runCatching
                val content = o.optString("content")
                // drop empty streaming leftovers from a previous crash/interruption
                if (role == ChatRole.ASSISTANT && content.isBlank() && !o.optBoolean("error")) {
                    return@runCatching
                }
                out += ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = role,
                    content = content,
                    error = o.optBoolean("error"),
                    imagePath = o.optString("image").takeIf {
                        it.isNotBlank() && java.io.File(it).exists()
                    },
                )
            }
        }
        return out
    }

    private fun deriveTitle(msgs: List<ChatMessage>): String {
        val first = msgs.firstOrNull { it.role == ChatRole.USER }?.content?.trim()
            ?: return "Nova conversa"
        val oneLine = first.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= 48) oneLine else oneLine.take(48) + "…"
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------
    fun updateSettings(new: AppSettings) {
        settings = new
        prefs.save(new)
    }

    /** Configurações de inferência salvas de um modelo (para edição pré-carregamento). */
    fun settingsFor(path: String): ModelSettings = prefs.loadModelSettings(path)

    /** Salva a configuração de inferência de um modelo. */
    fun updateModelSettings(path: String, new: ModelSettings) {
        val prev = prefs.loadModelSettings(path)
        prefs.saveModelSettings(path, new)
        if (loadedModel?.path == path) {
            modelSettings = new
            if (prev.nCtx != new.nCtx || prev.threads != new.threads ||
                prev.gpuLayers != new.gpuLayers || prev.imageTokens != new.imageTokens
            ) {
                showSnack("Parâmetros de memória/visão mudaram — recarregue o modelo para aplicar")
            }
        }
    }

    fun applyLoadParams() {
        if (loadedModel != null) reloadModel()
    }
}
