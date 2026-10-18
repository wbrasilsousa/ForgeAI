package dev.forgeai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Estimador de memória (TD-25). O erro aqui vira recomendação errada na tela,
 * então os números são fixados com contas feitas à mão.
 */
class ModelFitTest {

    /** Qwen2.5-2B-ish: GQA com 2 KV heads, 24 camadas, 2048 de embedding. */
    private fun gqa() = ModelInfo(
        fileName = "m.gguf",
        path = "/m/m.gguf",
        sizeBytes = 1_500_000_000,
        nLayer = 24,
        nEmbd = 2048,
        nHead = 16,
        nHeadKv = 2,
        nKvLayers = 24,
        nVocab = 151_936,
    )

    @Test
    fun kvCache_usesGqaHeads() {
        // 2(K,V) * 24 layers * 2 kv_heads * 128 head_dim * 2048 ctx * 2 bytes
        val expected = 2L * 24 * 2 * 128 * 2048 * 2
        assertEquals(expected, ModelFit.kvCacheBytes(gqa(), 2048))
    }

    @Test
    fun kvCache_respectsHybridAttentionLayerCount() {
        val hybrid = gqa().copy(nKvLayers = 6) // 3 lineares : 1 full (Qwen3.5)
        assertEquals(
            ModelFit.kvCacheBytes(gqa(), 2048) / 4,
            ModelFit.kvCacheBytes(hybrid, 2048),
        )
    }

    @Test
    fun kvCache_growsLinearlyWithContext() {
        val a = ModelFit.kvCacheBytes(gqa(), 2048)
        val b = ModelFit.kvCacheBytes(gqa(), 4096)
        assertEquals(a * 2, b)
    }

    @Test
    fun kvCache_fallsBackToMhaHeadsWithoutMetadata() {
        val mha = gqa().copy(nHeadKv = null)
        val expected = 2L * 24 * 16 * 128 * 2048 * 2
        assertEquals(expected, ModelFit.kvCacheBytes(mha, 2048))
    }

    @Test
    fun kvCache_zeroWithoutMetadata() {
        val bare = ModelInfo("m.gguf", "/m/m.gguf", 1_000_000)
        assertEquals(0L, ModelFit.kvCacheBytes(bare, 2048))
    }

    @Test
    fun kvCache_q8IsAboutHalfOfF16() {
        val f16 = ModelFit.kvCacheBytes(gqa(), 2048, ModelFit.KV_F16)
        val q8 = ModelFit.kvCacheBytes(gqa(), 2048, ModelFit.KV_Q8_0)
        assertTrue("q8=$q8 f16=$f16", q8 < f16)
        assertTrue("q8=$q8 deve ser > f16/2", q8 > f16 / 2)
    }

    @Test
    fun computeBuffer_hasFloorAndGrowsWithEmbedding() {
        val small = gqa().copy(nEmbd = 512)
        assertEquals(64L * 1024 * 1024, ModelFit.computeBufferBytes(small))
        val big = gqa().copy(nEmbd = 4096)
        assertTrue(ModelFit.computeBufferBytes(big) > 64L * 1024 * 1024)
    }

    @Test
    fun estimate_countsAllComponents() {
        val est = ModelFit.estimate(
            model = gqa(),
            nCtx = 2048,
            availableBytes = 5_000_000_000,
            visionBytes = 300_000_000,
        )
        assertEquals(1_500_000_000L, est.weightsBytes)
        assertEquals(300_000_000L, est.visionBytes)
        assertTrue(est.kvCacheBytes > 0)
        assertTrue(est.computeBytes > 0)
        assertEquals(
            est.weightsBytes + est.kvCacheBytes + est.computeBytes + est.visionBytes,
            est.totalBytes,
        )
    }

    @Test
    fun fit_levels_followAvailableRam() {
        // ~2,5 GB de pesos + KV + compute (Bonsai 2 9B)
        val m = ModelInfo(
            "b.gguf", "/m/b.gguf", 2_557_000_000,
            nLayer = 36, nEmbd = 4096, nHead = 32, nHeadKv = 8, nKvLayers = 12,
            nVocab = 151_936,
        )
        val good = ModelFit.estimate(m, 2048, availableBytes = 5_000_000_000)
        assertEquals(MemoryEstimate.Fit.GOOD, good.fit)

        val tight = ModelFit.estimate(m, 2048, availableBytes = 3_500_000_000)
        assertEquals(MemoryEstimate.Fit.TIGHT, tight.fit)

        val noFit = ModelFit.estimate(m, 2048, availableBytes = 2_500_000_000)
        assertEquals(MemoryEstimate.Fit.NO_FIT, noFit.fit)
        assertTrue(noFit.headroomBytes < 0)
    }

    @Test
    fun fit_isOptimisticWhenRamIsUnknown() {
        val est = ModelFit.estimate(gqa(), 2048, availableBytes = 0L)
        assertEquals(MemoryEstimate.Fit.GOOD, est.fit)
    }

    @Test
    fun labels_areHumanReadable() {
        val est = ModelFit.estimate(gqa(), 2048, availableBytes = 5_000_000_000)
        assertEquals("cabe", est.shortLabel)
        assertTrue(est.totalLabel.endsWith("GB") || est.totalLabel.endsWith("MB"))
    }
}
