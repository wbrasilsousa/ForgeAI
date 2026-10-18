package dev.forgeai.data

/**
 * Modelos sugeridos na tela "Adicionar".
 *
 * Desde a Onda 0 a lista é a família **PrismML Bonsai 2** (ternary, base
 * Qwen3.5, multimodal): 2B, 4B e 9B. Detalhes da escolha do packing em
 * `docs/DECISIONS.md` (ADR-001):
 *
 *  - usamos o packing `Q2_0` porque é o único que o llama.cpp **upstream**
 *    (`b10333`) carrega — `PQ2_0`/`PTQ1_0` exigem o fork da PrismML;
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
            file = "Ternary-Bonsai-2-2B-Q2_0.gguf",
            mmprojFile = "Ternary-Bonsai-2-2B-mmproj-Q8_0.gguf",
            paramsLabel = "2B",
            sizeBytes = 547_000_000,
            note = "Rápido · vê imagens e usa ferramentas",
        ),
        SuggestedModel(
            repo = "prism-ml/Ternary-Bonsai-2-4B-gguf",
            file = "Ternary-Bonsai-2-4B-Q2_0.gguf",
            mmprojFile = "Ternary-Bonsai-2-4B-mmproj-Q8_0.gguf",
            paramsLabel = "4B",
            sizeBytes = 1_212_000_000,
            note = "Melhor equilíbrio entre qualidade e velocidade",
        ),
        SuggestedModel(
            repo = "prism-ml/Ternary-Bonsai-2-9B-gguf",
            file = "Ternary-Bonsai-2-9B-Q2_0.gguf",
            mmprojFile = "Ternary-Bonsai-2-9B-mmproj-Q8_0.gguf",
            paramsLabel = "9B",
            sizeBytes = 2_557_000_000,
            note = "Qualidade máxima · ~3,2 GB com a visão",
        ),
    )
}
