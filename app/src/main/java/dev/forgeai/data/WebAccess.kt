package dev.forgeai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Tool `web_access`: baixa uma página e devolve o texto legível.
 *
 * Atenção: esta ferramenta envia requisições para a internet (os dados saem
 * do aparelho). Só é usada quando o usuário liga "Web access" em Ajustes.
 */
object WebAccess {

    /** Máximo de caracteres de texto devolvido ao modelo (cabe no contexto). */
    const val MAX_CHARS = 5000

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    data class Page(
        val url: String,
        val title: String?,
        val text: String,
    )

    suspend fun fetch(rawUrl: String): Result<Page> = withContext(Dispatchers.IO) {
        runCatching {
            val url = normalizeUrl(rawUrl)
                ?: throw IllegalArgumentException("URL inválida: $rawUrl")
            val request = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) ForgeAI/1.0",
                )
                .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.5")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException(
                        "HTTP ${resp.code}${resp.message.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""}".trim(),
                    )
                }
                val html = resp.body?.string().orEmpty()
                Page(url, extractTitle(html), htmlToText(html))
            }
        }
    }

    private fun normalizeUrl(raw: String): String? {
        var u = raw.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://$u"
        }
        val uri = runCatching { java.net.URI(u) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return uri.toString()
    }

    private fun extractTitle(html: String): String? {
        val m = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(html)
            ?: return null
        return HtmlText.toText(m.groupValues[1]).trim().ifBlank { null }
    }

    private fun htmlToText(html: String): String {
        var s = html
        // descarta blocos não-texto primeiro
        s = Regex("(?is)<(script|style|noscript|template|head|svg|iframe|form|nav|footer|header)[^>]*>.*?</\\1>")
            .replace(s, " ")
        s = Regex("(?is)<!--.*?-->").replace(s, " ")
        s = HtmlText.stripTags(s)
        s = HtmlText.decodeEntities(s)
        s = Regex("[\\s\\r\\n\\t]+").replace(s, " ").trim()
        return s.take(MAX_CHARS)
    }
}
