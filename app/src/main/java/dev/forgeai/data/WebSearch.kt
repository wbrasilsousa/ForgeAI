package dev.forgeai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Tool `web_search`: envia uma query para o DuckDuckGo (sem API key) e
 * devolve uma lista de resultados (título + trecho + URL) para o modelo
 * escolher. Se o DuckDuckGo falhar ou não retornar nada, cai na API de busca
 * da Wikipédia.
 *
 * Atenção: a query vai para a internet (os dados saem do aparelho). Só é
 * usada quando o usuário liga "Web access" em Ajustes.
 */
object WebSearch {

    const val MAX_RESULTS = 5
    const val MAX_CHARS = 4000

    data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String,
    )

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

    private fun request(url: String, accept: String = "text/html,application/xhtml+xml,*/*;q=0.8"): Response =
        client.newCall(
            Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) ForgeAI/1.0",
                )
                .header("Accept", accept)
                .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.5")
                .get()
                .build()
        ).execute()

    suspend fun search(query: String): Result<List<SearchResult>> = withContext(Dispatchers.IO) {
        runCatching { ddgHtml(query) }
            .recoverCatching { wikipedia(query) }
    }

    // ------------------------------------------------------------------
    // DuckDuckGo HTML (sem API key)
    // ------------------------------------------------------------------
    private fun ddgHtml(query: String): List<SearchResult> {
        val url = "https://html.duckduckgo.com/html/?q=${enc(query)}"
        request(url).use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("DuckDuckGo HTTP ${resp.code}")
            val html = resp.body?.string().orEmpty()

            val titles = Regex("(?s)<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>")
                .findAll(html).toList()
            val snippets = Regex("(?s)<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>")
                .findAll(html).toList()

            val results = titles.mapIndexedNotNull { i, m ->
                val title = HtmlText.toText(m.groupValues[2]).trim()
                if (title.isBlank()) return@mapIndexedNotNull null
                val href = m.groupValues[1]
                val snip = snippets.getOrNull(i)?.groupValues?.get(1)?.let { HtmlText.toText(it).trim() } ?: ""
                 SearchResult(title, decodeDdgUrl(href), snip)
            }.take(MAX_RESULTS)

            if (results.isEmpty()) throw IllegalStateException("DuckDuckGo não retornou resultados")
            return results
        }
    }

    /** A URL do DDG vem como redirect `//duckduckgo.com/l/?uddg=<url>`. */
    private fun decodeDdgUrl(href: String): String {
        val m = Regex("uddg=([^&]+)").find(href) ?: return href.trim()
        return runCatching { URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrDefault(href.trim())
    }

    // ------------------------------------------------------------------
    // Fallback: API de busca da Wikipédia (JSON, sem key)
    // ------------------------------------------------------------------
    private fun wikipedia(query: String): List<SearchResult> {
        val url = "https://pt.wikipedia.org/w/api.php" +
            "?action=query&list=search&format=json&origin=*" +
            "&srlimit=${MAX_RESULTS}&srsearch=${enc(query)}"
        request(url, "application/json").use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Wikipedia HTTP ${resp.code}")
            val o = JSONObject(resp.body?.string().orEmpty())
            val search = o.optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
            val results = (0 until search.length()).mapNotNull { i ->
                val item = search.optJSONObject(i) ?: return@mapNotNull null
                val title = item.optString("title").trim()
                if (title.isBlank()) return@mapNotNull null
                val snip = item.optString("snippet")
                    .let { HtmlText.decodeEntities(Regex("<[^>]*>").replace(it, " ")) }
                    .let { Regex("[\\s\\r\\n\\t]+").replace(it, " ").trim() }
                val pageUrl = "https://pt.wikipedia.org/wiki/" +
                    URLEncoder.encode(title.replace(' ', '_'), "UTF-8")
                 SearchResult(title, pageUrl, snip)
            }.take(MAX_RESULTS)
            return results
        }
    }

    /** Texto que o modelo lê como resultado da busca. */
    fun formatResults(query: String, results: List<SearchResult>): String {
        val sb = StringBuilder()
        sb.append("Resultados de busca para \"").append(query).append("\":")
        if (results.isEmpty()) {
            sb.append("\n\nNenhum resultado encontrado.")
            return sb.toString()
        }
        results.forEachIndexed { i, r ->
            sb.append("\n\n").append(i + 1).append(". ").append(r.title.ifBlank { "(sem título)" })
            if (r.url.isNotBlank()) sb.append("\n   URL: ").append(r.url)
            if (r.snippet.isNotBlank()) sb.append("\n   Trecho: ").append(r.snippet)
        }
        return sb.toString().take(MAX_CHARS)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
