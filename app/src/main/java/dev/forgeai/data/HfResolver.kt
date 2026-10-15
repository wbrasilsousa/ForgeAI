package dev.forgeai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Resolves HuggingFace URLs (model pages, tree pages, resolve URLs) and
 * lists the downloadable GGUF files of a repository via the Hub API.
 */
object HfResolver {

    data class HfFile(val fileName: String, val sizeBytes: Long?, val partCount: Int = 1)

    data class HfSource(val repo: String, val file: String?)

    class HfException(message: String) : Exception(message)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Parses any supported HuggingFace URL into a (repo, file?) pair.
     * Examples:
     *   https://huggingface.co/org/repo
     *   https://huggingface.co/org/repo/tree/main
     *   https://huggingface.co/org/repo/resolve/main/file.gguf
     *   https://huggingface.co/org/repo/blob/main/file.gguf
     *   hf://org/repo/file.gguf
     *   org/repo
     */
    fun parseUrl(raw: String): HfSource {
        var s = raw.trim().ifEmpty { throw HfException("URL vazia") }
        if (s.startsWith("hf://")) {
            s = s.removePrefix("hf://")
        } else if (!s.contains("://")) {
            // bare "org/repo" shortcut
            if (!s.contains("/")) throw HfException("URL inválida")
            return HfSource(repo = s, file = null)
        } else {
            val u = java.net.URI(s)
            if (u.host == null || !(u.host == "huggingface.co" || u.host.endsWith(".huggingface.co"))) {
                throw HfException("Só são suportadas URLs do HuggingFace (huggingface.co)")
            }
            s = u.path ?: throw HfException("URL inválida")
        }
        s = s.trim('/')
        if (s.isEmpty()) throw HfException("URL inválida")
        val parts = s.split("/")
        if (parts.size < 2) throw HfException("URL inválida — informe org/repo")

        val org = parts[0]
        val repo = parts[1]
        var file: String? = null
        if (parts.size >= 4) {
            when (parts[2]) {
                "resolve", "blob" -> {
                    // org/repo/resolve/rev/file...
                    val rest = parts.drop(4)
                    if (rest.isNotEmpty()) file = rest.joinToString("/")
                }
                "raw" -> {
                    val rest = parts.drop(3)
                    if (rest.isNotEmpty()) file = rest.joinToString("/")
                }
            }
        }
        return HfSource(repo = "$org/$repo", file = file)
    }

    /**
     * Lists GGUF files of a repository. Sizes come from the Hub API when
     * available, otherwise resolved via HEAD requests.
     */
    suspend fun listFiles(repo: String, directFile: String? = null): List<HfFile> =
        withContext(Dispatchers.IO) {
            if (directFile != null) {
                val size = headSize(repo, directFile)
                return@withContext listOf(HfFile(directFile, size))
            }
            val req = Request.Builder()
                .url("https://huggingface.co/api/models/${encodeRepo(repo)}")
                .header("User-Agent", "ForgeAI/1.0")
                .build()
            val body = try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        when (resp.code) {
                            401, 403 -> throw HfException(
                                "Este repositório é privado ou gated — ele exige autenticação."
                            )
                            404 -> throw HfException("Repositório não encontrado: $repo")
                            else -> throw HfException("Erro HTTP ${resp.code} ao consultar o HuggingFace")
                        }
                    }
                    resp.body?.string()
                }
            } catch (e: HfException) {
                throw e
            } catch (e: IOException) {
                throw HfException("Falha de rede: ${e.message}")
            }
            if (body == null) throw HfException("Resposta vazia do HuggingFace")

            val json = JSONObject(body)
            val siblings = json.optJSONArray("siblings")
            if (siblings == null || siblings.length() == 0) {
                throw HfException("Nenhum arquivo encontrado neste repositório.")
            }
            val files = mutableListOf<HfFile>()
            for (i in 0 until siblings.length()) {
                val sib = siblings.getJSONObject(i)
                val name = sib.optString("rfilename")
                if (name.isBlank()) continue
                if (!name.endsWith(".gguf", ignoreCase = true)) continue
                val size: Long? = if (sib.has("size") && !sib.isNull("size")) {
                    val raw = sib.optLong("size", -1L)
                    if (raw >= 0) raw else null
                } else null
                files.add(HfFile(name, size))
            }
            if (files.isEmpty()) {
                throw HfException("Nenhum arquivo .gguf encontrado neste repositório.")
            }
            files.sortedByDescending { it.sizeBytes ?: 0L }
        }

    fun headSize(repo: String, file: String): Long? {
        return runCatching {
            val req = Request.Builder()
                .url("https://huggingface.co/${encodeRepo(repo)}/resolve/main/${encodePathSegment(file)}")
                .header("User-Agent", "ForgeAI/1.0")
                .head()
                .build()
            client.newCall(req).execute().use { resp ->
                resp.header("Content-Length")?.toLongOrNull()
            }
        }.getOrNull()
    }

    /**
     * URL-encodes a single path segment (file names may contain spaces, etc.).
     * NOTE: never encode the whole repo id — the "/" must be preserved,
     * otherwise the Hub responds HTTP 400.
     */
    fun encodePathSegment(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** URL-encodes a repo id ("org/name") preserving the slash. */
    fun encodeRepo(repo: String): String =
        repo.split('/').joinToString("/") { encodePathSegment(it) }

    private val SHARD_RE = Regex("-\\d{5}-of-\\d{5}\$", RegexOption.IGNORE_CASE)
    private val SHARD_FULL_RE = Regex("^(.+)-\\d{5}-of-(\\d{5})\\.gguf$", RegexOption.IGNORE_CASE)

    /** Base name without the shard suffix, e.g. "m-00001-of-00002.gguf" -> "m.gguf". */
    fun baseName(fileName: String): String {
        val info = shardInfo(fileName)
        return info?.first?.let { "$it.gguf" } ?: fileName
    }

    /** For a shard file returns (base, totalParts), else null. */
    fun shardInfo(fileName: String): Pair<String, Int>? {
        val m = SHARD_FULL_RE.matchEntire(fileName) ?: return null
        val total = m.groupValues[2].toInt()
        if (total <= 1) return null
        return m.groupValues[1] to total
    }

    /** All part file names of a sharded model, or null if not sharded. */
    fun shardParts(fileName: String): List<String>? {
        val info = shardInfo(fileName) ?: return null
        val (base, total) = info
        return (1..total).map { String.format("%s-%05d-of-%05d.gguf", base, it, total) }
    }

    /**
     * Groups the file list: sharded models are merged into a single entry
     * (with partCount and summed size) so the user picks the whole model.
     */
    fun mergeShards(files: List<HfFile>): List<HfFile> {
        val map = LinkedHashMap<String, MutableList<HfFile>>()
        for (f in files) {
            map.getOrPut(baseName(f.fileName)) { mutableListOf() }.add(f)
        }
        return map.values.map { group ->
            if (group.size == 1) {
                group.first()
            } else {
                val totalSize = group.sumOf { it.sizeBytes ?: 0L }.takeIf { s -> s > 0 && group.any { it.sizeBytes != null } }
                HfFile(
                    fileName = group.first().fileName,
                    sizeBytes = totalSize,
                    partCount = group.size,
                )
            }
        }.sortedByDescending { it.sizeBytes ?: 0L }
    }

    /**
     * Procura o encoder de visão (mmproj-*.gguf) que casa com um arquivo de
     * modelo na mesma lista do repositório (mesmo nome base sem quantização).
     *
     * Prefere o encoder quantizado (Q8_0/Q4_* etc.): em CPU ele é bem mais
     * leve e rápido que F16/BF16 — e a qualidade visual praticamente não muda.
     * O BF16 (que costuma vir primeiro na listagem do Hub) é o pior caso para
     * celular: arquivo grande e codificação ~2× mais lenta.
     */
    fun findMmproj(files: List<HfFile>, modelFile: String): String? {
        val base = ModelInfo.visionBase(modelFile)
        val candidates = files.filter {
            it.fileName.startsWith("mmproj-", ignoreCase = true) &&
                ModelInfo.visionBase(it.fileName) == base
        }
        if (candidates.isEmpty()) return null
        val rank = { f: HfFile ->
            when (quantFromName(f.fileName)?.uppercase()) {
                "BF16", "F32" -> 3
                "F16", "FP16" -> 2
                else -> 1 // Q8_0, Q4_*, IQ* — quantizado e mais rápido
            }
        }
        return candidates.minWithOrNull(
            compareBy<HfFile>({ rank(it) }, { it.fileName })
        )?.fileName
    }

    /**
     * Extracts the quantization tag from a GGUF file name, e.g.
     * "qwen2.5-0.5b-instruct-q4_k_m.gguf" -> "Q4_K_M".
     */
    private val QUANT_PATTERNS = listOf(
        "Q4_K_M", "Q5_K_M", "Q4_K_S", "Q5_K_S", "Q3_K_M", "Q3_K_L", "Q3_K_S",
        "Q2_K_S", "Q2_K", "Q6_K", "Q8_0", "Q5_0", "Q5_1", "Q4_0", "Q4_1", "Q3_0",
        "IQ1_M", "IQ1_S", "IQ2_M", "IQ2_S", "IQ2_XXS", "IQ2_XS", "IQ3_M", "IQ3_S",
        "IQ3_XXS", "IQ3_XS", "IQ4_NL", "IQ4_XS", "TQ1_0", "TQ2_0", "MXFP4",
        "BF16", "FP16", "F16", "F32",
    )

    fun quantFromName(fileName: String): String? {
        val upper = fileName.uppercase()
        for (p in QUANT_PATTERNS) {
            if (upper.contains(p)) return p
        }
        return null
    }

    fun isRecommendedQuant(quant: String?): Boolean =
        quant != null && (quant == "Q4_K_M" || quant == "Q4_0" || quant == "Q5_K_M")
}
