package dev.forgeai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A lista de sugestões é a família Bonsai 2 (ADR-001). Estes testes garantem
 * que os nomes de arquivo são os reais do Hub, que o packing é o suportado
 * pelo upstream (`Q2_0`) e que o encoder de visão casa com o modelo.
 */
class SuggestedModelsTest {

    @Test
    fun offersTheThreeBonsaiSizes() {
        val labels = SuggestedModels.all.map { it.paramsLabel }
        assertEquals(listOf("2B", "4B", "9B"), labels)
        assertTrue(SuggestedModels.all.all { it.repo.startsWith("prism-ml/Ternary-Bonsai-2-") })
    }

    @Test
    fun usesUpstreamSupportedPacking() {
        for (s in SuggestedModels.all) {
            assertEquals("Q2_0", HfResolver.quantFromName(s.file))
        }
    }

    @Test
    fun everySuggestionHasAVisionEncoderThatPairs() {
        for (s in SuggestedModels.all) {
            val mmproj = s.mmprojFile
            assertNotNull("$s sem mmproj", mmproj)
            assertTrue(ModelInfo.isMmprojName(mmproj!!))
            assertEquals(
                "encoder não casa com o modelo em $s",
                ModelInfo.visionBase(s.file),
                ModelInfo.visionBase(mmproj),
            )
            // o mesmo par é o que findMmproj acharia dentro do repositório
            assertEquals(
                mmproj,
                HfResolver.findMmproj(
                    listOf(HfResolver.HfFile(s.file, s.sizeBytes), HfResolver.HfFile(mmproj, 1)),
                    s.file,
                ),
            )
        }
    }

    @Test
    fun sizesGrowWithParameterCount() {
        val sizes = SuggestedModels.all.map { it.sizeBytes }
        assertEquals(sizes.sorted(), sizes)
        assertTrue(sizes.first() in 400_000_000..700_000_000)
        assertTrue(sizes.last() in 2_000_000_000..3_000_000_000)
    }

    @Test
    fun sizeLabelIsFormatted() {
        assertTrue(SuggestedModels.all.first().sizeLabel.contains("MB"))
    }
}
