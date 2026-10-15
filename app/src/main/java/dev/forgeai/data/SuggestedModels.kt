package dev.forgeai.data

/**
 * Curated list of small instruct models that run well on phones.
 * File names verified against the Hub API (2025-11).
 */
data class SuggestedModel(
    val repo: String,
    val file: String,
    val paramsLabel: String,
    val sizeLabel: String,
    val note: String,
)

object SuggestedModels {
    val all = listOf(
        SuggestedModel(
            repo = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
            file = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            paramsLabel = "0.5B",
            sizeLabel = "~400 MB",
            note = "Rápido em qualquer celular",
        ),
        SuggestedModel(
            repo = "HuggingFaceTB/SmolLM2-360M-Instruct-GGUF",
            file = "smollm2-360m-instruct-q8_0.gguf",
            paramsLabel = "360M",
            sizeLabel = "~270 MB",
            note = "Leve e veloz",
        ),
        SuggestedModel(
            repo = "TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF",
            file = "tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
            paramsLabel = "1.1B",
            sizeLabel = "~670 MB",
            note = "Clássico para celular",
        ),
        SuggestedModel(
            repo = "HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF",
            file = "smollm2-1.7b-instruct-q4_k_m.gguf",
            paramsLabel = "1.7B",
            sizeLabel = "~1.1 GB",
            note = "Melhor custo-benefício",
        ),
        SuggestedModel(
            repo = "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            file = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            paramsLabel = "1.5B",
            sizeLabel = "~1 GB",
            note = "Excelente em português",
        ),
        SuggestedModel(
            repo = "bartowski/Phi-3.5-mini-instruct-GGUF",
            file = "Phi-3.5-mini-instruct-Q4_K_M.gguf",
            paramsLabel = "3.8B",
            sizeLabel = "~2.3 GB",
            note = "Para dispositivos potentes",
        ),
    )
}
