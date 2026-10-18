package dev.forgeai.data

import org.json.JSONObject

/**
 * A GGUF model present in the models directory.
 */
data class ModelInfo(
    val fileName: String,
    val path: String,
    val sizeBytes: Long,
    val partCount: Int = 1,
    // metadata (filled after inspection)
    val name: String? = null,
    val arch: String? = null,
    val quant: String? = null,
    val params: Long? = null,
    val nCtxTrain: Long? = null,
    val nLayer: Int? = null,
    val nEmbd: Int? = null,
    val nHead: Int? = null,
    /** Cabeças KV (GQA/MQA). Igual a [nHead] em modelos MHA. */
    val nHeadKv: Int? = null,
    /** Camadas que realmente guardam KV (atenção híbrida: < [nLayer]). */
    val nKvLayers: Int? = null,
    val nVocab: Int? = null,
    val nTensors: Int? = null,
    val tokenizer: String? = null,
    val chatTemplate: String? = null,
    /** Caminho do encoder de imagens (mmproj-*.gguf) encontrado ao lado do modelo. */
    val mmprojPath: String? = null,
    val metaLoaded: Boolean = false,
    val isLoaded: Boolean = false,
) {
    /** True quando o template do modelo tem suporte nativo a tools (formato Qwen2.5). */
    val supportsTools: Boolean get() = ToolSupport.templateSupportsTools(chatTemplate)

    /** True quando o modelo tem um encoder de visão (mmproj) disponível em disco. */
    val supportsVision: Boolean get() = mmprojPath != null

    val displayName: String
        get() = name?.takeUnless { it.isHashLike() }
            ?: HfResolver.baseName(fileName).removeSuffix(".gguf").replace('_', ' ')

    val displayParams: String
        get() = params?.let { formatParams(it) } ?: "—"

    val displayQuant: String
        get() = quant?.takeIf { it.isNotBlank() } ?: "GGUF"

    val sizeLabel: String
        get() = formatBytes(sizeBytes)

    companion object {
        /** Nomes tipo hash (ex.: o general.name dos modelos LFM é um SHA-1 hex). */
        private val HASH_LIKE = Regex("^[0-9a-fA-F]{32,}$")

        /**
         * Nome base sem prefixo mmproj- e sem sufixo de quantização — usado
         * para casar o encoder de visão com o modelo principal.
         *   "mmproj-LFM2.5-VL-3B-Q8_0.gguf" -> "LFM2.5-VL-3B"
         *   "LFM2.5-VL-3B-Q4_K_M.gguf"      -> "LFM2.5-VL-3B"
         */
        /** True quando o arquivo é um encoder de visão (mmproj), em qualquer posição do nome. */
        fun isMmprojName(fileName: String): Boolean =
            fileName.contains("mmproj", ignoreCase = true)

        /**
         * Nome base sem o marcador mmproj e sem sufixos de quantização/variante —
         * usado para casar o encoder de visão com o modelo principal.
         *   "mmproj-LFM2.5-VL-3B-Q8_0.gguf"            -> "LFM2.5-VL-3B"
         *   "Ternary-Bonsai-2-2B-mmproj-Q8_0.gguf"     -> "Ternary-Bonsai-2-2B"
         *   "Ternary-Bonsai-2-4B-PQ2_0-MTP-Q8_0.gguf"  -> "Ternary-Bonsai-2-4B"
         */
        fun visionBase(fileName: String): String {
            var name = HfResolver.baseName(fileName).removeSuffix(".gguf")
            // o marcador pode estar no início OU no meio ("…-2B-mmproj-Q8_0")
            name = MMPROJ_RE.replace(name, "").trim('-', '_', ' ', '.')
            // Remove sufixos do fim, repetidamente: quantização (Q8_0, PQ2_0, …)
            // e marcadores de variante (MTP, DFlash2). Modelos Bonsai 2 usam
            // nomes compostos como "-PQ2_0-MTP-Q8_0", onde só um strip não basta.
            while (true) {
                val quant = HfResolver.quantFromName(name)
                val stripped = when {
                    quant != null && name.endsWith("-$quant", ignoreCase = true) ->
                        name.dropLast(quant.length + 1)
                    else -> VARIANT_SUFFIXES
                        .firstOrNull { name.endsWith("-$it", ignoreCase = true) }
                        ?.let { name.dropLast(it.length + 1) }
                }
                if (stripped == null || stripped == name || stripped.isEmpty()) break
                name = stripped
            }
            return name.trim('-', '_', ' ')
        }

        private val MMPROJ_RE = Regex("mmproj[-_ ]?", RegexOption.IGNORE_CASE)

        /** Marcadores de variante que não são quantização. */
        private val VARIANT_SUFFIXES = listOf("MTP", "DFlash2", "DFlash")

        fun String.isHashLike(): Boolean = HASH_LIKE.matches(this)

        fun formatParams(n: Long): String {
            val b = n.toDouble()
            return when {
                b >= 1e9 -> String.format("%.1fB", b / 1e9)
                b >= 1e6 -> String.format("%.1fM", b / 1e6)
                else -> n.toString()
            }
        }

        fun formatBytes(bytes: Long): String {
            val b = bytes.toDouble()
            return when {
                b >= 1e9 -> String.format("%.2f GB", b / 1e9)
                b >= 1e6 -> String.format("%.1f MB", b / 1e6)
                else -> String.format("%.0f KB", b / 1e3)
            }
        }

        fun fromMeta(fileName: String, path: String, sizeBytes: Long, meta: JSONObject): ModelInfo {
            fun optLong(key: String): Long? {
                val v = meta.opt(key) ?: return null
                return if (v is Number) v.toLong() else v.toString().toLongOrNull()
            }
            fun optInt(key: String): Int? {
                val v = meta.opt(key) ?: return null
                return if (v is Number) v.toInt() else v.toString().toIntOrNull()
            }
            return ModelInfo(
                fileName = fileName,
                path = path,
                sizeBytes = sizeBytes,
                partCount = meta.optInt("part_count", 1).coerceAtLeast(1),
                name = meta.optString("name").ifBlank { null },
                arch = meta.optString("arch").ifBlank { null },
                quant = meta.optString("quant").ifBlank { null },
                params = optLong("n_params") ?: optLong("params"),
                nCtxTrain = optLong("n_ctx_train"),
                nLayer = optInt("n_layer"),
                nEmbd = optInt("n_embd"),
                nHead = optInt("n_head"),
                nHeadKv = optInt("n_head_kv"),
                nKvLayers = optInt("n_kv_layers"),
                nVocab = optInt("n_vocab"),
                nTensors = optInt("n_tensors"),
                tokenizer = meta.optString("tokenizer").ifBlank { null },
                chatTemplate = meta.optString("template").ifBlank { null },
                metaLoaded = true,
            )
        }
    }
}
