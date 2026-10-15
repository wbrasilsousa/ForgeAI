package dev.forgeai.data

import android.content.Context
import android.util.Log
import dev.forgeai.data.ModelInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads GGUF files from HuggingFace.
 *
 * - progress, cancel and resume (HTTP Range)
 * - automatic retry with backoff when the CDN drops the connection
 * - stall watchdog (no data for 60s -> reconnect)
 * - sharded models (x-00001-of-00002.gguf) are downloaded as a single task
 * - interrupted downloads are persisted so they resume after an app restart
 */
class ModelDownloader(private val context: Context) {

    enum class Status { QUEUED, DOWNLOADING, DONE, ERROR, CANCELLED }

    data class Task(
        val id: String,
        val repo: String,
        val fileName: String,
        val url: String,
        val partCount: Int = 1,
        val partIndex: Int = 0,
        val totalBytes: Long = 0L,
        val downloadedBytes: Long = 0L,
        val speedBps: Long = 0L,
        val status: Status = Status.QUEUED,
        val error: String? = null,
        val attempt: Int = 1,
    ) {
        val progress: Float
            get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    private val _tasks = MutableStateFlow<Map<String, Task>>(emptyMap())
    val tasks: StateFlow<Map<String, Task>> = _tasks

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // unlimited: big files; stall handled manually
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val jobs = mutableMapOf<String, Job>()
    private val partsMap = mutableMapOf<String, List<String>>()

    private val modelsDir: File
        get() = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------
    fun start(scope: CoroutineScope, repo: String, fileName: String, knownSize: Long? = null, partCount: Int = 1) {
        val parts = if (partCount > 1) HfResolver.shardParts(fileName) ?: listOf(fileName) else listOf(fileName)
        val display = parts.first()
        val id = "$repo/$display"
        if (_tasks.value.containsKey(id) && _tasks.value[id]!!.status == Status.DOWNLOADING) return
        writeSidecar(id, repo, display)
        partsMap[id] = parts
        val url = "https://huggingface.co/${HfResolver.encodeRepo(repo)}/resolve/main/${HfResolver.encodePathSegment(display)}"
        _tasks.update { it + (id to Task(
            id = id, repo = repo, fileName = display, url = url,
            partCount = parts.size, totalBytes = knownSize ?: 0L,
        )) }
        jobs[id] = scope.launch(Dispatchers.IO) { run(id) }
    }

    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        _tasks.update { map ->
            val t = map[id] ?: return@update map
            map + (id to t.copy(status = Status.CANCELLED))
        }
    }

    fun retry(scope: CoroutineScope, id: String) {
        jobs.remove(id)
        val t = _tasks.value[id] ?: return
        _tasks.update { map ->
            map + (id to t.copy(status = Status.QUEUED, error = null, partIndex = 0))
        }
        jobs[id] = scope.launch(Dispatchers.IO) { run(id) }
    }

    fun remove(id: String) {
        cancel(id)
        _tasks.value[id]?.let { deleteSidecarFor(it.fileName) }
        partsMap.remove(id)
        _tasks.update { it - id }
    }

    /**
     * Scans the models dir for interrupted downloads (.part.meta.json sidecars)
     * and resumes them. Called on app startup.
     */
    fun resumeInterrupted(scope: CoroutineScope) {
        val dir = modelsDir
        val metas = dir.listFiles { f -> f.isFile && f.name.endsWith(".part.meta.json") } ?: return
        for (meta in metas) {
            try {
                val o = JSONObject(meta.readText())
                val repo = o.optString("repo").ifBlank { continue }
                val file = o.optString("file").ifBlank { continue }
                val id = "$repo/$file"
                if (_tasks.value.containsKey(id)) continue
                // already completed?
                val finalFile = File(dir, file)
                if (finalFile.exists() && finalFile.length() > 0) {
                    deleteSidecarFor(file)
                    continue
                }
                val partCount = HfResolver.shardParts(file)?.size ?: 1
                start(scope, repo, file, null, partCount)
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------
    // Core loop (one task = one model, possibly sharded)
    // ------------------------------------------------------------------
    private suspend fun run(id: String) {
        val task = _tasks.value[id] ?: return
        val repo = task.repo
        val parts = partsMap[id] ?: listOf(task.fileName)

        for ((idx, partName) in parts.withIndex()) {
            val finalFile = File(modelsDir, partName)
            if (finalFile.exists() && finalFile.length() > 0) continue // already done

            val ok = runPart(id, repo, partName, idx + 1, parts.size)
            if (!ok) return // ERROR / CANCELLED already recorded
        }

        // all parts downloaded
        val totalSize = parts.sumOf { File(modelsDir, it).length() }
        deleteSidecarFor(task.fileName)
        partsMap.remove(id)
        _tasks.update { it + (id to it[id]!!.copy(
            status = Status.DONE, partIndex = parts.size, totalBytes = totalSize, downloadedBytes = totalSize,
        )) }
    }

    private suspend fun runPart(id: String, repo: String, partName: String, partIndex: Int, partCount: Int): Boolean {
        val partFile = File(modelsDir, "$partName.part")
        val finalFile = File(modelsDir, partName)
        val maxAttempts = 5
        var attempt = 0
        while (true) {
            attempt++
            val existing = if (partFile.exists()) partFile.length() else 0L
            _tasks.update { map ->
                val t = map[id] ?: return@update map
                map + (id to t.copy(status = Status.DOWNLOADING, attempt = attempt, partIndex = partIndex, partCount = partCount))
            }
            try {
                val completed = downloadAttempt(id, repo, partName, existing)
                if (completed) {
                    if (!partFile.renameTo(finalFile)) {
                        partFile.copyTo(finalFile, overwrite = true)
                        partFile.delete()
                    }
                    return true
                }
                return true // 416 -> already complete
            } catch (e: CancellationException) {
                _tasks.update { it + (id to it[id]!!.copy(status = Status.CANCELLED)) }
                throw e
            } catch (e: Exception) {
                Log.e("ForgeAI-DL", "download falhou (parte $partIndex/$partCount, tentativa $attempt): ${e.message}", e)
                if (attempt >= maxAttempts) {
                    _tasks.update { it + (id to it[id]!!.copy(
                        status = Status.ERROR,
                        error = if (partCount > 1) "parte $partIndex/$partCount: ${e.message ?: "erro de rede"}"
                        else e.message ?: "erro de rede",
                    )) }
                    return false
                }
                delay(1500L * attempt) // backoff, then retry (resume from .part)
            }
        }
    }

    /**
     * One download attempt of a single part. Returns true when the part is
     * completely written to disk. Throws IOException so [runPart] can retry.
     */
    private fun downloadAttempt(id: String, repo: String, partName: String, existing0: Long): Boolean {
        val partFile = File(modelsDir, "$partName.part")
        var existing = existing0

        val builder = Request.Builder()
            .url("https://huggingface.co/${HfResolver.encodeRepo(repo)}/resolve/main/${HfResolver.encodePathSegment(partName)}")
            .header("User-Agent", "ForgeAI/1.0")
        if (existing > 0) builder.header("Range", "bytes=$existing-")
        val request = builder.build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) {
                if (resp.code == 416 && existing > 0) return true // range complete
                throw IOException("HTTP ${resp.code}")
            }
            val body = resp.body ?: throw IOException("resposta vazia")

            if (resp.code == 200 && existing > 0) {
                existing = 0 // server ignored Range -> restart
            }

            val remaining = body.contentLength().coerceAtLeast(0)
            val total = existing + remaining

            val stat = android.os.StatFs(modelsDir.absolutePath)
            if (total > stat.availableBytes) {
                throw IOException("Espaço insuficiente: precisa de ${ModelInfo.formatBytes(total)}")
            }

            var written = existing
            val append = existing > 0

            FileOutputStream(partFile, append).use { out ->
                val source = body.source()
                val buf = Buffer()
                val chunk = 64L * 1024

                var lastEmitBytes = written
                var lastEmitTime = System.currentTimeMillis()
                var lastDataTime = System.currentTimeMillis()

                while (true) {
                    val n = source.read(buf, chunk)
                    if (n == -1L) break
                    buf.writeTo(out, n) // consumes from buf (bounded memory)
                    written += n

                    val now = System.currentTimeMillis()
                    if (n > 0) lastDataTime = now

                    // stall watchdog
                    if (now - lastDataTime > 60_000) {
                        throw IOException("Conexão parada — reconectando")
                    }

                    if (now - lastEmitTime >= 250 || written >= total) {
                        val dt = (now - lastEmitTime).coerceAtLeast(1) / 1000.0
                        val speed = ((written - lastEmitBytes) / dt).toLong()
                        _tasks.update { map ->
                            val t = map[id] ?: return@update map
                            map + (id to t.copy(
                                downloadedBytes = written,
                                totalBytes = total,
                                speedBps = speed,
                            ))
                        }
                        lastEmitBytes = written
                        lastEmitTime = now
                    }
                }
            }

            val finalBytes = partFile.length()
            _tasks.update { map ->
                val t = map[id] ?: return@update map
                map + (id to t.copy(downloadedBytes = finalBytes, totalBytes = total, speedBps = 0L))
            }
        }
        return true
    }

    // ------------------------------------------------------------------
    // Sidecar persistence (so interrupted downloads can be resumed)
    // ------------------------------------------------------------------
    private fun writeSidecar(id: String, repo: String, file: String) {
        try {
            val o = JSONObject()
            o.put("repo", repo)
            o.put("file", file)
            File(modelsDir, "$file.part.meta.json").writeText(o.toString())
        } catch (_: Exception) {
        }
    }

    private fun deleteSidecarFor(fileName: String) {
        runCatching { File(modelsDir, "$fileName.part.meta.json").delete() }
    }
}
