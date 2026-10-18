package dev.forgeai.data

/**
 * Estimador de memória para rodar um modelo (TD-25 — "fit badge").
 *
 * Responde a pergunta que todo usuário faz antes de baixar/carregar:
 * **"esse modelo roda no meu celular?"** — sem precisar carregar nada.
 *
 * A conta é conservadora e usa só metadados do GGUF (lidos nativamente em
 * `nativeInspectModel`) + o tamanho real do arquivo:
 *
 *   total = pesos (arquivo) + KV cache + buffer de computação + encoder de visão
 *
 * O KV cache é o termo que mais erra se calculado no chute. Ele é
 * `2 · camadas_com_kv · n_kv_heads · head_dim · n_ctx · bytes_por_elemento`:
 *  - `n_kv_heads` < `n_head` em modelos GQA (Qwen, Llama 3, etc.);
 *  - modelos de atenção híbrida (Qwen3.5/Bonsai 2: 3 camadas lineares : 1 full)
 *    só guardam KV nas camadas full-attention → `n_kv_layers`.
 * Os dois valores vêm do GGUF (`attention.head_count_kv`), inclusive quando o
 * campo é um array por camada (caso dos híbridos).
 *
 * A tela mostra o resultado como "cabe / aperta / não cabe" com os GB
 * estimados e a RAM livre real do aparelho.
 */
data class MemoryEstimate(
    val weightsBytes: Long,
    val kvCacheBytes: Long,
    val computeBytes: Long,
    val visionBytes: Long,
    val availableBytes: Long,
) {
    enum class Fit { GOOD, TIGHT, NO_FIT }

    val totalBytes: Long get() = weightsBytes + kvCacheBytes + computeBytes + visionBytes

    /** Folga (pode ser negativa). */
    val headroomBytes: Long get() = availableBytes - totalBytes

    /**
     * GOOD  = cabe com folga (≤60% da RAM livre)
     * TIGHT = cabe, mas o sistema pode matar apps em segundo plano (≤90%)
     * NO_FIT= não cabe
     * Sem informação de RAM (availableBytes ≤ 0) não alarmamos o usuário.
     */
    val fit: Fit
        get() = when {
            availableBytes <= 0L -> Fit.GOOD
            totalBytes <= (availableBytes * 0.60).toLong() -> Fit.GOOD
            totalBytes <= (availableBytes * 0.90).toLong() -> Fit.TIGHT
            else -> Fit.NO_FIT
        }

    val totalLabel: String get() = ModelInfo.formatBytes(totalBytes)

    /** Texto curto para o badge. */
    val shortLabel: String
        get() = when (fit) {
            Fit.GOOD -> "cabe"
            Fit.TIGHT -> "aperta"
            Fit.NO_FIT -> "não cabe"
        }
}

object ModelFit {

    /** Bytes por elemento do KV cache (llama.cpp). */
    const val KV_F16 = 2.0f
    const val KV_Q8_0 = 1.0625f // 8,5 bits/elemento
    const val KV_Q4_0 = 0.5625f // 4,5 bits/elemento

    /** Sem metadados suficientes, assume-se um buffer de computação modesto. */
    private const val DEFAULT_COMPUTE_BYTES = 128L * 1024 * 1024

    /** Tokens de prompt por passo de decodificação (n_batch do app). */
    private const val N_BATCH = 512L

    fun estimate(
        model: ModelInfo,
        nCtx: Int,
        availableBytes: Long,
        visionBytes: Long = 0L,
        kvBytesPerElem: Float = KV_F16,
    ): MemoryEstimate = MemoryEstimate(
        weightsBytes = model.sizeBytes,
        kvCacheBytes = kvCacheBytes(model, nCtx, kvBytesPerElem),
        computeBytes = computeBufferBytes(model),
        visionBytes = visionBytes.coerceAtLeast(0L),
        availableBytes = availableBytes,
    )

    /** `2 · camadas_com_kv · n_kv_heads · head_dim · n_ctx · bytes` (K + V). */
    fun kvCacheBytes(model: ModelInfo, nCtx: Int, kvBytesPerElem: Float = KV_F16): Long {
        val nLayer = model.nLayer ?: return 0L
        val nEmbd = model.nEmbd ?: return 0L
        val nHead = model.nHead ?: return 0L
        if (nLayer <= 0 || nEmbd <= 0 || nHead <= 0 || nCtx <= 0) return 0L

        val headDim = (nEmbd / nHead).coerceAtLeast(1)
        val kvHeads = (model.nHeadKv ?: nHead).coerceAtLeast(1)
        val kvLayers = (model.nKvLayers ?: nLayer).coerceIn(1, nLayer)

        val elements = 2L * kvLayers * kvHeads * headDim * nCtx
        return (elements * kvBytesPerElem).toLong()
    }

    /**
     * Buffer de computação do grafo: ~`n_batch · n_embd · 4 bytes · fator`
     * mais o tensor de logits (`n_vocab · 4`). É a parcela menor, mas sem ela
     * a estimativa mente em ~100–300 MB.
     */
    fun computeBufferBytes(model: ModelInfo): Long {
        val nEmbd = model.nEmbd ?: return DEFAULT_COMPUTE_BYTES
        val logits = (model.nVocab ?: 0).toLong() * 4L
        val activations = N_BATCH * nEmbd.toLong() * 4L * 8L
        return (activations + logits).coerceAtLeast(DEFAULT_COMPUTE_BYTES / 2)
    }
}
