package dev.forgeai.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regras de <think> do renderizador: modelos de baixo bit (Bonsai) escrevem a
 * resposta dentro do bloco aberto e nunca fecham a tag. Se isso quebrar, o
 * usuário vê “(sem resposta)”.
 */
class ThinkTest {

    @Test
    fun stripThink_removesClosedBlock() {
        assertEquals("Resposta.", stripThink("<think>raciocínio interno</think>Resposta."))
        assertEquals("Olá", stripThink("Olá"))
    }

    @Test
    fun stripThink_hidesOpenBlockWhileStreaming() {
        assertEquals("", stripThink("<think>raciocinando", final = false))
    }

    @Test
    fun stripThink_recoversOpenBlockWhenFinal() {
        assertEquals("raciocinando", stripThink("<think>raciocinando", final = true))
    }

    @Test
    fun stripThink_recoversInnerTextWhenNothingVisibleAfterClose() {
        assertEquals("resposta dentro do bloco", stripThink("<think>resposta dentro do bloco</think>", final = true))
        assertEquals("", stripThink("<think>resposta dentro do bloco</think>", final = false))
    }

    @Test
    fun stripThink_keepsTextBeforeAndAfterBlock() {
        assertEquals("antes depois", stripThink("antes<think>x</think> depois"))
    }

    @Test
    fun isOpenThinkAnswer_requiresPunctuation() {
        assertTrue(isOpenThinkAnswer("<think>Pronto, aqui está a resposta."))
        assertFalse(isOpenThinkAnswer("<think>Pronto, aqui está a respo"))
        assertFalse(isOpenThinkAnswer("<think>a</think>fora"))
        assertFalse(isOpenThinkAnswer("texto normal."))
    }

    @Test
    fun parseMarkdown_splitsCodeFromText() {
        val segments = parseMarkdown("Veja:\n```kt\nval x = 1\n```\nfim")
        val code = segments.filterIsInstance<MdSegment.Code>().single()
        assertEquals("kt", code.language)
        assertEquals("val x = 1", code.code)
        val text = segments.filterIsInstance<MdSegment.Text>().joinToString("") { it.text }
        assertTrue(text.contains("Veja:"))
        assertTrue(text.contains("fim"))
    }

    @Test
    fun parseMarkdown_stripsThinkBeforeSplitting() {
        val segments = parseMarkdown("<think>segredo</think>Resposta final")
        assertFalse(segments.filterIsInstance<MdSegment.Text>().any { it.text.contains("segredo") })
    }
}
