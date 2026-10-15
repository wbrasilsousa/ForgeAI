package dev.forgeai.data

import org.json.JSONObject

/**
 * Tool calling para llama.cpp b10333 (injeção por prompt).
 *
 * A API crua do llama.h no b10333 não aceita definições de tools no
 * `llama_chat_apply_template` (isso vive na lib `common`, compilada OFF aqui),
 * então injetamos a seção de tools manualmente no início da mensagem de sistema,
 * no formato exato em que cada família de modelo foi treinada.
 *
 * Dois formatos suportados (detectados pelo template do modelo):
 *
 *  - [ToolFormat.QWEN25_JSON] (Qwen2.5): tools em `<tools>[array]</tools>` e
 *    chamada em JSON:
 *        <tool_call>
 *        {"name":"web_search","arguments":{"query":"..."}}
 *        </tool_call>
 *
 *  - [ToolFormat.QWEN35_XML] (Qwen3/Qwen3.5): tools em `<tools>{objeto}</tools>`
 *    e chamada em XML:
 *        <tool_call>
 *        <function=web_search>
 *        <parameter=query>
 *        previsão do tempo em São Paulo
 *        </parameter>
 *        </function>
 *        </tool_call>
 *    O resultado volta como mensagem `user` com
 *    `<tool_response>...</tool_response>` (é o que o template espera).
 *
 *  - [ToolFormat.LFM25] (LFM2.5 / LFM2.5-VL): tools definidas no system prompt
 *    como `List of tools: [JSON]` e chamada em estilo Python (a lista entre os
 *    tokens especiais `<|tool_call_start|>` e `<|tool_call_end|>`):
 *        <|tool_call_start|>[web_search(query="o que pesquisar")]<|tool_call_end|>
 *    O resultado volta como mensagem de role `tool` (o template ChatML do LFM
 *    renderiza `<|im_start|>tool\n...<|im_end|>`).
 *
 * Tools disponíveis:
 *  - [SEARCH_NAME] `web_search`: query → lista de resultados (título+trecho+URL)
 *  - [TOOL_NAME]   `web_access`:  URL → texto da página
 */
enum class ToolFormat { NONE, QWEN25_JSON, QWEN35_XML, LFM25 }

object ToolSupport {

    const val SEARCH_NAME = "web_search"
    const val TOOL_NAME = "web_access"

    val KNOWN_TOOLS = setOf(SEARCH_NAME, TOOL_NAME)

    private const val SEARCH_DESCRIPTION =
        "Pesquisa na internet e retorna uma lista de resultados com título, trecho e URL. " +
            "Use quando o usuário pedir informações atuais, notícias, preços, clima, ou qualquer " +
            "coisa que exija descobrir o que existe na web. Depois você pode usar web_access para " +
            "abrir a página de um resultado específico."

    private const val ACCESS_DESCRIPTION =
        "Acessa uma página da web e retorna o texto dela. Use quando o usuário pedir informações " +
            "de um site específico ou quando quiser ler o conteúdo completo de um resultado da " +
            "busca web_search. O resultado é limitado aos primeiros ~5000 caracteres."

    /** Definições OpenAI-compatible (o que o modelo vê dentro de <tools>). */
    val searchDefinitionJson: String =
        """{"type":"function","function":{"name":"$SEARCH_NAME","description":"$SEARCH_DESCRIPTION","parameters":{"type":"object","properties":{"query":{"type":"string","description":"O que pesquisar, em poucas palavras (ex.: 'previsão do tempo em São Paulo hoje')"}},"required":["query"]}}}"""

    val accessDefinitionJson: String =
        """{"type":"function","function":{"name":"$TOOL_NAME","description":"$ACCESS_DESCRIPTION","parameters":{"type":"object","properties":{"url":{"type":"string","description":"URL completa da página, começando com http:// ou https://"}},"required":["url"]}}}"""

    // LFM2.5 espera a lista de tools SEM o wrapper "function" (só o objeto).
    private val searchDefinitionLfm: String =
        """{"name":"$SEARCH_NAME","description":"$SEARCH_DESCRIPTION","parameters":{"type":"object","properties":{"query":{"type":"string","description":"O que pesquisar, em poucas palavras (ex.: 'previsão do tempo em São Paulo hoje')"}},"required":["query"]}}"""

    private val accessDefinitionLfm: String =
        """{"name":"$TOOL_NAME","description":"$ACCESS_DESCRIPTION","parameters":{"type":"object","properties":{"url":{"type":"string","description":"URL completa da página, começando com http:// ou https://"}},"required":["url"]}}"""

    /**
     * Detecta o formato de tools pelo chat template do modelo:
     *  - LFM2.5/LFM2.5-VL: tokens `<|tool_call_start|>` (VL) ou a renderização
     *    `List of tools: [` no template (1.2B-Thinking e variantes — o template
     *    define as tools no system prompt; a chamada é Pythonic entre os tokens)
     *  - `<function=` / `<parameter=` -> Qwen3/Qwen3.5 (XML)
     *  - `<tools>` -> Qwen2.5 (JSON)
     */
    fun detectFormat(template: String?): ToolFormat {
        if (template.isNullOrBlank()) return ToolFormat.NONE
        return when {
            template.contains("<|tool_call_start|>") ||
                template.contains("List of tools: [") ||
                template.contains("{%- if tools -%}") -> ToolFormat.LFM25
            template.contains("<function=") || template.contains("<parameter=") ->
                ToolFormat.QWEN35_XML
            template.contains("<tools>") -> ToolFormat.QWEN25_JSON
            else -> ToolFormat.NONE
        }
    }

    fun templateSupportsTools(template: String?): Boolean =
        detectFormat(template) != ToolFormat.NONE

    /** Seção completa de tools, injetada no início da mensagem de sistema. */
    fun buildSystemToolsSection(format: ToolFormat): String = when (format) {
        ToolFormat.QWEN25_JSON -> buildString {
            append("<tools>\n[$searchDefinitionJson,$accessDefinitionJson]\n</tools>\n\n")
            append(
                "Você tem acesso às ferramentas $SEARCH_NAME (pesquisar na internet) e " +
                    "$TOOL_NAME (abrir uma página). Use-as SOMENTE quando a pergunta do usuário exigir " +
                    "informações atuais ou específicas da internet (notícias, preços, clima, fatos recentes). " +
                    "Para saudações, conversa casual ou perguntas que você já sabe responder, NÃO use " +
                    "ferramentas: responda diretamente, em texto normal.\n\n" +
                    "Quando precisar de uma ferramenta, responda EXATAMENTE com uma única chamada no formato:\n" +
                    "<tool_call>\n{\"name\": \"$SEARCH_NAME\", \"arguments\": {\"query\": \"o que pesquisar\"}}\n</tool_call>\n" +
                    "sem nenhum texto adicional. Se preferir abrir uma página que você já conhece, use " +
                    "{\"name\": \"$TOOL_NAME\", \"arguments\": {\"url\": \"https://...\"}}. Depois que o " +
                    "resultado da ferramenta chegar, responda ao usuário normalmente usando essas informações."
            )
        }
        ToolFormat.QWEN35_XML -> buildString {
            append("# Tools\n\n")
            append("You have access to the following functions:\n\n")
            append("<tools>\n$searchDefinitionJson\n$accessDefinitionJson\n</tools>\n\n")
            append(
                "Use a function ONLY when the user's request needs current or specific internet " +
                    "information (news, prices, weather, recent facts). For greetings, casual chat " +
                    "or questions you can already answer, do NOT call a function: answer directly " +
                    "in normal text.\n\n"
            )
            append("If you choose to call a function ONLY reply in the following format with NO suffix:\n\n")
            append(
                "<tool_call>\n<function=$SEARCH_NAME>\n<parameter=query>\nprevisão do tempo em São Paulo hoje\n</parameter>\n</function>\n</tool_call>\n\n"
            )
            append(
                "<IMPORTANT>\nReminder:\n" +
                    "- Function calls MUST follow the specified format: an inner <function=...></function> block must be nested within <tool_call></tool_call> XML tags\n" +
                    "- Required parameters MUST be specified\n" +
                    "- You may provide optional reasoning for your function call in natural language BEFORE the function call, but NOT after\n" +
                    "- If there is no function call available, answer the question like normal with your current knowledge and do not tell the user about function calls\n" +
                    "</IMPORTANT>"
            )
        }
        ToolFormat.LFM25 -> buildString {
            // Formato oficial do LFM2.5: a lista de tools é um JSON no system
            // prompt e a chamada é uma lista Python entre os tokens especiais.
            append("List of tools: [\n")
            append(searchDefinitionLfm)
            append(",\n")
            append(accessDefinitionLfm)
            append("\n]\n\n")
            append(
                "Você tem acesso às ferramentas $SEARCH_NAME (pesquisar na internet) e " +
                    "$TOOL_NAME (abrir uma página). Use-as SOMENTE quando a pergunta do usuário exigir " +
                    "informações atuais ou específicas da internet (notícias, preços, clima, fatos recentes). " +
                    "Para saudações, conversa casual ou perguntas que você já sabe responder, NÃO use " +
                    "ferramentas: responda diretamente, em texto normal.\n\n" +
                    "Quando precisar de uma ferramenta, responda EXATAMENTE com uma única chamada no " +
                    "formato Python abaixo, entre os tokens especiais, sem nenhum texto adicional:\n" +
                    "<|tool_call_start|>[$SEARCH_NAME(query=\"o que pesquisar\")]<|tool_call_end|>\n" +
                    "Se preferir abrir uma página que você já conhece, use " +
                    "<|tool_call_start|>[$TOOL_NAME(url=\"https://...\")]<|tool_call_end|>. " +
                    "Depois que o resultado da ferramenta chegar, responda ao usuário normalmente usando essas informações."
            )
        }
        ToolFormat.NONE -> ""
    }

    /** Instrução de correção para a 2ª tentativa quando a 1ª veio quebrada. */
    fun buildCorrectionMessage(format: ToolFormat): String = when (format) {
        ToolFormat.QWEN25_JSON ->
            "Sua chamada de ferramenta anterior estava incompleta ou no formato errado. " +
                "Não responda ainda: repita APENAS a chamada no formato exato abaixo:\n" +
                "<tool_call>\n{\"name\": \"$SEARCH_NAME\", \"arguments\": {\"query\": \"o que pesquisar\"}}\n</tool_call>"
        ToolFormat.QWEN35_XML ->
            "Sua chamada de ferramenta anterior estava incompleta ou no formato errado. " +
                "Não responda ainda: repita APENAS a chamada no formato exato abaixo:\n" +
                "<tool_call>\n<function=$SEARCH_NAME>\n<parameter=query>\no que pesquisar\n</parameter>\n</function>\n</tool_call>"
        ToolFormat.LFM25 ->
            "Sua chamada de ferramenta anterior estava incompleta ou no formato errado. " +
                "Não responda ainda: repita APENAS a chamada no formato exato abaixo:\n" +
                "<|tool_call_start|>[$SEARCH_NAME(query=\"o que pesquisar\")]<|tool_call_end|>"
        ToolFormat.NONE -> ""
    }

    /** Uma chamada de tool extraída da resposta do modelo. */
    data class ToolCall(
        val name: String,
        val arguments: JSONObject,
        val rawText: String,
    ) {
        val url: String get() = arguments.optString("url").trim()
        val query: String get() = arguments.optString("query").trim()

        /** Valor principal para mostrar na UI (url ou query, conforme a tool). */
        val primaryValue: String get() = url.ifBlank { query }
    }

    // ------------------------------------------------------------------
    // Parsing — tenta LFM2.5 (Pythonic), depois XML (Qwen3.x), depois JSON (Qwen2.5).
    // ------------------------------------------------------------------

    private val XML_TC_RE = Regex("(?s)<tool_call>(.*?)</tool_call>")
    private val XML_FN_RE = Regex("(?s)<function=([^>\\n]+)>")
    private val XML_PARAM_RE = Regex("(?s)<parameter=([^>\\n]+)>([\\s\\S]*?)</parameter>")

    // --- LFM2.5: <|tool_call_start|>[name(key="value")]<|tool_call_end|> ---
    private val LFM_TC_RE = Regex("(?s)<\\|tool_call_start\\|>(.*?)<\\|tool_call_end\\|>")
    private val LFM_FN_RE = Regex("([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(")

    /**
     * Extrai o conteúdo entre os parênteses que fecham na primeira posição.
     * O texto recebido já está DENTRO do par de parênteses externo (o `(` que
     * abriu a chamada foi consumido pelo chamador), por isso a profundidade
     * começa em 1: o primeiro `)` em nível 0 é o fechamento da própria chamada.
     */
    private fun extractBalancedParens(s: String): String? {
        var depth = 1
        var inStr = false
        var quote = '\u0000'
        var esc = false
        for (i in s.indices) {
            val c = s[i]
            when {
                esc -> esc = false
                inStr && c == '\\' -> esc = true
                inStr && c == quote -> inStr = false
                !inStr && (c == '"' || c == '\'') -> { inStr = true; quote = c }
                !inStr && c == '(' -> depth++
                !inStr && c == ')' -> {
                    depth--
                    if (depth == 0) return s.substring(0, i)
                }
            }
        }
        return null
    }

    /** Converte `key="valor", n=42` (estilo Python) num JSONObject. */
    private fun parsePythonicArgs(s: String): JSONObject {
        val out = JSONObject()
        var depth = 0
        var inStr = false
        var quote = '\u0000'
        var esc = false
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        for (c in s) {
            when {
                esc -> { cur.append(c); esc = false }
                inStr && c == '\\' -> { cur.append(c); esc = true }
                inStr && c == quote -> { cur.append(c); inStr = false }
                !inStr && (c == '"' || c == '\'') -> { cur.append(c); inStr = true; quote = c }
                !inStr && (c == '(' || c == '[' || c == '{') -> { depth++; cur.append(c) }
                !inStr && (c == ')' || c == ']' || c == '}') -> { depth--; cur.append(c) }
                !inStr && c == ',' && depth == 0 -> { parts += cur.toString().trim(); cur.clear() }
                else -> cur.append(c)
            }
        }
        if (cur.isNotBlank()) parts += cur.toString().trim()
        for (p in parts) {
            val eq = p.indexOf('=')
            if (eq <= 0) continue
            val key = p.substring(0, eq).trim()
            if (key.isEmpty()) continue
            val value = p.substring(eq + 1).trim()
            val v: Any = when {
                value.length >= 2 && value.first() == value.last() &&
                    (value.first() == '"' || value.first() == '\'') ->
                    value.substring(1, value.length - 1)
                        .replace("\\\\", "\\")
                        .replace("\\\"", "\"")
                        .replace("\\'", "'")
                        .replace("\\n", "\n")
                        .replace("\\r", "\r")
                value.equals("true", true) -> true
                value.equals("false", true) -> false
                value.equals("null", true) || value == "None" -> JSONObject.NULL
                value.toIntOrNull() != null -> value.toInt()
                value.toDoubleOrNull() != null -> value.toDouble()
                else -> value
            }
            out.put(key, v)
        }
        return out
    }

    private fun parseLfmToolCall(raw: String): ToolCall? {
        // com o wrapper de tokens especiais
        LFM_TC_RE.find(raw)?.let { m ->
            var inner = m.groupValues[1].trim()
            if (inner.startsWith("[")) inner = inner.removePrefix("[").trim()
            val fn = LFM_FN_RE.find(inner)
            if (fn != null && fn.range.first == 0) {
                val name = fn.groupValues[1].trim()
                if (name in KNOWN_TOOLS) {
                    val args = extractBalancedParens(inner.substring(fn.range.last + 1))
                        ?.let { parsePythonicArgs(it) } ?: JSONObject()
                    return ToolCall(name, args, raw)
                }
            }
        }
        // sem o wrapper (resposta truncada): `web_search(query="...")` no início,
        // possivelmente envolto em `[...]` (a lista Python sem os tokens especiais,
        // comum em modelos quantizados que seguem só o "formato Python").
        val t = raw.trim()
        val bare = if (t.startsWith("[")) t.removePrefix("[").trim() else t
        val fn = LFM_FN_RE.find(bare)
        if (fn != null && fn.range.first == 0) {
            val name = fn.groupValues[1].trim()
            if (name in KNOWN_TOOLS) {
                val args = extractBalancedParens(bare.substring(fn.range.last + 1))
                    ?.let { parsePythonicArgs(it) } ?: JSONObject()
                return ToolCall(name, args, raw)
            }
        }
        return null
    }

    private fun parseXmlToolCall(raw: String): ToolCall? {
        // aceita a chamada com ou sem o wrapper <tool_call>
        val inner = XML_TC_RE.find(raw)?.groupValues?.get(1) ?: raw
        val fn = XML_FN_RE.find(inner) ?: return null
        val name = fn.groupValues[1].trim()
        if (name !in KNOWN_TOOLS) return null
        val args = JSONObject()
        for (p in XML_PARAM_RE.findAll(inner)) {
            val key = p.groupValues[1].trim()
            val value = p.groupValues[2].trim()
            if (key.isNotEmpty() && !args.has(key)) args.put(key, value)
        }
        return ToolCall(name, args, raw)
    }

    private val TOOL_CALL_RE = Regex("<tool_call>([\\s\\S]*?)</tool_call>")
    private val TOOL_CALL_OPEN_RE = Regex("<tool_call>")

    private fun parseJsonToolCall(raw: String): ToolCall? {
        val candidates = sequence {
            TOOL_CALL_RE.find(raw)?.groupValues?.get(1)?.trim()?.let { yield(it) }
            TOOL_CALL_OPEN_RE.find(raw)?.let { m ->
                yield(raw.substring(m.range.last + 1).trim())
            }
            raw.trim().takeIf { it.startsWith("{") }?.let { yield(it) }
        }
        for (c in candidates) {
            val json = extractJsonObject(c) ?: continue
            val name = json.optString("name")
            if (name !in KNOWN_TOOLS) continue
            return ToolCall(name, json.optJSONObject("arguments") ?: JSONObject(), raw)
        }
        return null
    }

    /**
     * Extrai uma chamada de tool do texto bruto do assistente, nos formatos
     * LFM2.5 (Pythonic), Qwen3 (XML) e Qwen2.5 (JSON). Tolerante a
     * quantizações pequenas que truncam ou esquecem fechamentos.
     */
    fun parseToolCall(raw: String): ToolCall? {
        parseLfmToolCall(raw)?.let { return it }
        parseXmlToolCall(raw)?.let { return it }
        return parseJsonToolCall(raw)
    }

    /** Verdadeiro quando o texto parece ser (ou tentar ser) uma chamada de tool. */
    fun looksLikeToolAttempt(text: String): Boolean {
        if (text.isBlank()) return false
        return text.contains("<|tool_call_start|>") ||
            text.contains("<tool_call>") ||
            text.contains("<function=") ||
            // chamada Python "solta" (ex.: `web_search(query="...")` truncada)
            KNOWN_TOOLS.any { tool -> text.contains("$tool(") } ||
            // chamada JSON/lista crua (ex.: `{"name": "web_search", ...}` ou
            // `[web_search(query="...")]` sem os tokens especiais)
            (text.trimStart().startsWith("{") && KNOWN_TOOLS.any { text.contains(it) }) ||
            (text.trimStart().startsWith("[") && KNOWN_TOOLS.any { text.contains(it) })
    }

    /** Extrai o primeiro objeto JSON `{...}` balanceado de um texto. */
    private fun extractJsonObject(s: String): JSONObject? {
        var depth = 0
        var start = -1
        var inStr = false
        var esc = false
        for (i in s.indices) {
            val c = s[i]
            when {
                esc -> esc = false
                inStr && c == '\\' -> esc = true
                inStr && c == '"' -> inStr = false
                !inStr && c == '"' -> inStr = true
                !inStr && c == '{' -> { if (depth == 0) start = i; depth++ }
                !inStr && c == '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        return runCatching { JSONObject(s.substring(start, i + 1)) }.getOrNull()
                    }
                }
            }
        }
        return null
    }

    /** Conteúdo (role "tool") que o modelo lê após a execução da ferramenta. */
    fun buildToolResultContent(success: Boolean, url: String, title: String?, body: String?, error: String?): String {
        if (!success) {
            return "Erro ao acessar $url: ${error ?: "falha desconhecida"}. " +
                "Informe isso ao usuário e sugira verificar a URL ou tentar outra fonte."
        }
        val sb = StringBuilder()
        if (!title.isNullOrBlank()) sb.append("Título: ").append(title).append('\n')
        sb.append("URL: ").append(url).append('\n')
        sb.append("Conteúdo:\n").append(body ?: "(página sem texto)")
        return sb.toString()
    }
}
