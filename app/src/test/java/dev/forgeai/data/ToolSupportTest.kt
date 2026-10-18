package dev.forgeai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tool calling é a parte mais frágil do app (parsers manuais de 4 formatos).
 * Estes testes fixam o contrato dos formatos que já funcionam com LFM2.5,
 * Qwen2.5 e Qwen3.x — qualquer refatoração futura (TD-16) tem que mantê-los.
 */
class ToolSupportTest {

    // ------------------------------------------------------------------
    // detecção de formato pelo chat template
    // ------------------------------------------------------------------
    @Test
    fun detectFormat_lfm25() {
        assertEquals(
            ToolFormat.LFM25,
            ToolSupport.detectFormat("<|im_start|>...<|tool_call_start|>[web_search(query=\"x\")]<|tool_call_end|>"),
        )
        assertEquals(ToolFormat.LFM25, ToolSupport.detectFormat("List of tools: [{\"name\": \"web_search\"}]"))
    }

    @Test
    fun detectFormat_qwen35Xml() {
        assertEquals(
            ToolFormat.QWEN35_XML,
            ToolSupport.detectFormat("<tool_call>\n<function=web_search>\n<parameter=query>x</parameter>\n</function>\n</tool_call>"),
        )
    }

    @Test
    fun detectFormat_qwen25Json() {
        assertEquals(ToolFormat.QWEN25_JSON, ToolSupport.detectFormat("<tools>[{\"type\":\"function\"}]</tools>"))
    }

    @Test
    fun detectFormat_noneForPlainTemplates() {
        assertEquals(ToolFormat.NONE, ToolSupport.detectFormat(null))
        assertEquals(ToolFormat.NONE, ToolSupport.detectFormat(""))
        assertEquals(ToolFormat.NONE, ToolSupport.detectFormat("<|im_start|>user\n{{message}}<|im_end|>"))
    }

    @Test
    fun templateSupportsTools_followsDetection() {
        assertTrue(ToolSupport.templateSupportsTools("<tools></tools>"))
        assertFalse(ToolSupport.templateSupportsTools("<|im_start|>user"))
    }

    // ------------------------------------------------------------------
    // parsing das chamadas
    // ------------------------------------------------------------------
    @Test
    fun parseToolCall_lfm25WithSpecialTokens() {
        val call = ToolSupport.parseToolCall(
            "<|tool_call_start|>[web_search(query=\"clima em são paulo\")]<|tool_call_end|>"
        )
        assertEquals(ToolSupport.SEARCH_NAME, call?.name)
        assertEquals("clima em são paulo", call?.query)
    }

    @Test
    fun parseToolCall_lfm25BareCall() {
        val call = ToolSupport.parseToolCall("web_access(url=\"https://example.com/a\")")
        assertEquals(ToolSupport.TOOL_NAME, call?.name)
        assertEquals("https://example.com/a", call?.url)
    }

    @Test
    fun parseToolCall_qwen35Xml() {
        val call = ToolSupport.parseToolCall(
            "<tool_call>\n<function=web_access>\n<parameter=url>\nhttps://example.com\n</parameter>\n</function>\n</tool_call>"
        )
        assertEquals(ToolSupport.TOOL_NAME, call?.name)
        assertEquals("https://example.com", call?.url)
    }

    @Test
    fun parseToolCall_qwen25Json() {
        val call = ToolSupport.parseToolCall(
            "<tool_call>\n{\"name\": \"web_search\", \"arguments\": {\"query\": \"notícias de hoje\"}}\n</tool_call>"
        )
        assertEquals(ToolSupport.SEARCH_NAME, call?.name)
        assertEquals("notícias de hoje", call?.query)
    }

    @Test
    fun parseToolCall_ignoresUnknownToolsAndPlainText() {
        assertNull(ToolSupport.parseToolCall("Bom dia! Como posso ajudar?"))
        assertNull(ToolSupport.parseToolCall("<tool_call>{\"name\": \"shell\", \"arguments\": {}}</tool_call>"))
    }

    @Test
    fun looksLikeToolAttempt_detectsBrokenCalls() {
        assertTrue(ToolSupport.looksLikeToolAttempt("<|tool_call_start|>[web_search(query=\"x\")"))
        assertTrue(ToolSupport.looksLikeToolAttempt("{\"name\": \"web_search\", \"arguments\": {"))
        assertTrue(ToolSupport.looksLikeToolAttempt("web_access(url=\"https://a.b\""))
        assertTrue(ToolSupport.looksLikeToolAttempt("[web_search(query=\"x\")]"))
        assertFalse(ToolSupport.looksLikeToolAttempt(""))
        assertFalse(ToolSupport.looksLikeToolAttempt("Resposta normal, sem ferramentas."))
    }

    @Test
    fun toolCall_primaryValuePrefersUrl() {
        val call = ToolSupport.parseToolCall("web_access(url=\"https://a.b/c\")")
        assertEquals("https://a.b/c", call?.primaryValue)
    }

    // ------------------------------------------------------------------
    // mensagens sintetizadas
    // ------------------------------------------------------------------
    @Test
    fun buildSystemToolsSection_containsToolNamesPerFormat() {
        assertEquals("", ToolSupport.buildSystemToolsSection(ToolFormat.NONE))
        for (format in listOf(ToolFormat.LFM25, ToolFormat.QWEN25_JSON, ToolFormat.QWEN35_XML)) {
            val section = ToolSupport.buildSystemToolsSection(format)
            assertTrue("$format sem web_search", section.contains(ToolSupport.SEARCH_NAME))
            assertTrue("$format sem web_access", section.contains(ToolSupport.TOOL_NAME))
        }
        assertTrue(
            ToolSupport.buildSystemToolsSection(ToolFormat.QWEN25_JSON).contains("<tools>")
        )
    }

    @Test
    fun buildCorrectionMessage_isNonEmptyForKnownFormats() {
        for (format in listOf(ToolFormat.LFM25, ToolFormat.QWEN25_JSON, ToolFormat.QWEN35_XML)) {
            assertTrue(ToolSupport.buildCorrectionMessage(format).isNotBlank())
        }
    }

    @Test
    fun buildToolResultContent_errorAndSuccess() {
        val err = ToolSupport.buildToolResultContent(false, "https://a.b", null, null, "timeout")
        assertTrue(err.contains("timeout"))
        assertTrue(err.contains("https://a.b"))

        val ok = ToolSupport.buildToolResultContent(true, "https://a.b", "Título", "corpo da página", null)
        assertTrue(ok.contains("Título: Título"))
        assertTrue(ok.contains("corpo da página"))
    }
}
