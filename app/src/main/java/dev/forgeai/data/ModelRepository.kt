package dev.forgeai.data

import android.content.Context
import android.os.StatFs
import dev.forgeai.LlamaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Scans the app's models directory and keeps per-model metadata sidecars.
 */
class ModelRepository(private val context: Context) {

    /** Versão do arquivo .meta.json — incrementa quando novos campos são adicionados. */
    private companion object {
        const val META_VERSION = 2
    }

    val modelsDir: File
        get() = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }

    private fun metaFile(model: ModelInfo): File =
        File(modelsDir, "${model.fileName}.meta.json")

    fun listModels(): List<ModelInfo> {
        val dir = modelsDir
        if (!dir.exists()) return emptyList()
        val loaded = LlamaEngine.isLoaded()
        val loadedPath = loadedInfoPath()
        val allFiles = dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
            ?: return emptyList()

        // mmproj-*.gguf (encoder de visão) não é um modelo standalone: fica
        // associado ao modelo principal com o mesmo nome base (sem quantização).
        val mmprojs = allFiles
            .filter { it.name.startsWith("mmproj-", ignoreCase = true) }
            .associateBy { ModelInfo.visionBase(it.name) }
        val modelFiles = allFiles.filterNot { it.name.startsWith("mmproj-", ignoreCase = true) }

        // merge sharded models (m-00001-of-00002.gguf + m-00002-of-00002.gguf -> 1 entry)
        val byBase = modelFiles.groupBy { HfResolver.baseName(it.name) }
        return byBase.map { (_, group) ->
            val sorted = group.sortedBy { it.name }
            val first = sorted.first()
            val mmprojPath = mmprojs[ModelInfo.visionBase(first.name)]?.absolutePath
            val base = ModelInfo(
                fileName = first.name,
                path = first.absolutePath,
                sizeBytes = sorted.sumOf { it.length() },
                partCount = sorted.size,
                mmprojPath = mmprojPath,
                isLoaded = loaded && first.absolutePath == loadedPath,
            )
            (readMeta(base) ?: base).copy(
                partCount = sorted.size,
                mmprojPath = mmprojPath,
            )
        }.sortedBy { it.fileName.lowercase(Locale.ROOT) }
    }

    private fun loadedInfoPath(): String? =
        runCatching { LlamaEngine.loadedInfo()?.optString("path", null)?.takeIf { it.isNotBlank() } }
            .getOrNull()

    /**
     * Resolve o encoder de visão (mmproj-*.gguf) que casa com um modelo em
     * disco, independentemente da lista [models] já ter sido carregada. Usado
     * no carregamento do último modelo na inicialização, quando a lista ainda
     * pode estar vazia (race) — sem isso a visão ficava desligada.
     */
    fun mmprojFor(modelPath: String): String? {
        val f = File(modelPath)
        if (!f.exists()) return null
        val dir = f.parentFile ?: return null
        val base = ModelInfo.visionBase(f.name)
        val mmprojs = dir.listFiles { x ->
            x.isFile && x.name.startsWith("mmproj-", ignoreCase = true)
        } ?: return null
        return mmprojs
            .firstOrNull { ModelInfo.visionBase(it.name) == base }
            ?.absolutePath
    }

    fun readMeta(model: ModelInfo): ModelInfo? {
        val f = metaFile(model)
        if (!f.exists()) return null
        return runCatching {
            val meta = JSONObject(f.readText())
            // Sidecars antigos (sem meta_ver / n_params) são descartados para
            // forçar uma releitura com os campos novos.
            if (meta.optInt("meta_ver", 0) < META_VERSION) return null
            ModelInfo.fromMeta(model.fileName, model.path, model.sizeBytes, meta)
                .copy(isLoaded = model.isLoaded)
        }.getOrNull()
    }

    /**
     * Inspects the GGUF metadata natively (fast) and caches the result.
     */
    suspend fun inspect(model: ModelInfo): ModelInfo = withContext(Dispatchers.Default) {
        val meta = LlamaEngine.inspectModel(model.path)
        if (meta != null) {
            meta.put("part_count", model.partCount)
            // persist sidecar
            runCatching { metaFile(model).writeText(meta.toString()) }
            runCatching {
                ModelInfo.fromMeta(model.fileName, model.path, model.sizeBytes, meta)
                    .copy(partCount = model.partCount, isLoaded = model.isLoaded)
            }.getOrElse { model }
        } else {
            model
        }
    }

    fun delete(model: ModelInfo) {
        if (model.partCount > 1) {
            HfResolver.shardParts(model.fileName)?.forEach { p ->
                runCatching { File(modelsDir, p).delete() }
            }
        } else {
            runCatching { File(model.path).delete() }
        }
        runCatching { metaFile(model).delete() }
    }

    fun freeSpaceBytes(): Long {
        return runCatching {
            val stat = StatFs(modelsDir.absolutePath)
            stat.availableBytes
        }.getOrDefault(Long.MAX_VALUE)
    }

    fun totalSpaceBytes(): Long {
        return runCatching {
            val stat = StatFs(modelsDir.absolutePath)
            stat.totalBytes
        }.getOrDefault(0L)
    }

    fun usedSpaceBytes(): Long = listModels().sumOf { it.sizeBytes }

    fun fileFor(name: String): File = File(modelsDir, name)
}
