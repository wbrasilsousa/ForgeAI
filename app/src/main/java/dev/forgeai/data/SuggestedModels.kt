package dev.forgeai.data

/**
 * Modelos sugeridos na tela "Adicionar".
 *
 * Desde a Onda 0 a lista é a família **PrismML Bonsai 2** (ternary, base
 * Qwen3.5, multimodal): 2B, 4B e 9B. Detalhes da escolha do packing em
 * `docs/DECISIONS.md` (ADR-001):
 *
 *  - usamos o packing `PQ2_0`: no fork da PrismML ele é **menor e mais rápido**
 *    que o `Q2_0` (0,517 GB / 19,6 t/s vs 0,547 GB / 1,8 t/s no bench x86) e é
 *    o caminho com kernels otimizados. Todos os packings ternários produzem
 *    texto coerente no fork; no upstream o `Q2_0` decodifica errado (ADR-006);
 *  - o encoder de visão vem do mesmo repositório, em `mmproj-Q8_0` (≈2× mais
 *    rápido no celular que o BF16, sem perda visual perceptível);
 *  - os tamanhos abaixo são os reais do Hub (out/2026) e servem para o badge de
 *    compatibilidade antes do download.
 */
data class SuggestedModel(
    val repo: String,
    val file: String,
    /** Encoder de visão (mmproj) do mesmo repositório; null = modelo só texto. */
    val mmprojFile: String? = null,
    val paramsLabel: String,
    val sizeBytes: Long,
    val note: String,
) {
    val sizeLabel: String get() = ModelInfo.formatBytes(sizeBytes)

    /** Tamanho total baixado (modelo + encoder de visão). */
    val totalBytes: Long get() = sizeBytes

    val supportsVision: Boolean get() = mmprojFile != null
}

object SuggestedModels {
    val all = listOf(
        SuggestedModel(
            repo = "prism-ml/Ternary-Bonsai-2-2B-gguf",
            file = "Ternary-Bonsai-2-2B-PQ2_0.gguf",
            mmprojFile = "Ternary-Bonsai-2-2B-mmproj-Q8_0.gguf",
            paramsLabel = "2B",
            sizeBytes = 517_312_992,
            note = "Rápido · vê imagens e usa ferramentas",
        ),
        SuggestedModel(
            repo = "prism-ml/Ternary-Bonsai-2-4B-gguf",
            file = "Ternary-Bonsai-2-4B-PQ2_0.gguf",
            mmprojFile = "Ternary-Bonsai-2-4B-mmproj-Q8_0.gguf",
            paramsLabel = "4B",
            sizeBytes = 1_146_465_120,
            note = "Melhor equilíbrio entre qualidade e velocidade",
        ),
        SuggestedModel(
            repo = "prism-ml/Ternary-Bonsai-2-9B-gguf",
            file = "Ternary-Bonsai-2-9B-PQ2_0.gguf",
            mmprojFile = "Ternary-Bonsai-2-9B-mmproj-Q8_0.gguf",
            paramsLabel = "9B",
            sizeBytes = 2_416_851_840,
            note = "Qualidade máxima · ~3,0 GB com a visão",
        ),
    )
}
