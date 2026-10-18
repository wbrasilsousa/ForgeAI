package dev.forgeai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class ModelInfoTest {

    private fun model(fileName: String) = ModelInfo(fileName = fileName, path = "/tmp/$fileName", sizeBytes = 0)

    @Test
    fun displayName_prefersGgufNameAndIgnoresHashes() {
        assertEquals("qwen2.5-0.5b-instruct", model("qwen2.5-0.5b-instruct.gguf").displayName)
        val withHash = model("x.gguf").copy(name = "a".repeat(40))
        assertEquals("x", withHash.displayName)
        val withName = model("x.gguf").copy(name = "Bonsai 2 2B")
        assertEquals("Bonsai 2 2B", withName.displayName)
    }

    @Test
    fun formatters_areStable() {
        assertEquals("2.0B", ModelInfo.formatParams(2_000_000_000))
        assertEquals("360.0M", ModelInfo.formatParams(360_000_000))
        assertEquals("1.50 GB", ModelInfo.formatBytes(1_500_000_000))
        assertEquals("400.0 MB", ModelInfo.formatBytes(400_000_000))
    }

    @Test
    fun fromMeta_readsNumericAndStringFields() {
        val meta = JSONObject(
            """
            {"meta_ver":2,"arch":"qwen35","name":"Ternary-Bonsai-2-2B","quant":"Q2_0",
             "n_params":1880000000,"n_ctx_train":262144,"n_layer":24,"n_embd":2048,
             "n_vocab":151936,"n_tensors":300,"tokenizer":"gpt2","template":"<tools>"}
            """.trimIndent()
        )
        val info = ModelInfo.fromMeta("Ternary-Bonsai-2-2B-Q2_0.gguf", "/m/x.gguf", 547_000_000, meta)
        assertEquals("qwen35", info.arch)
        assertEquals("Q2_0", info.quant)
        assertEquals(1_880_000_000L, info.params)
        assertEquals(262_144L, info.nCtxTrain)
        assertEquals(24, info.nLayer)
        assertTrue(info.metaLoaded)
        assertTrue(info.supportsTools)
        assertFalse(info.supportsVision)
    }

    @Test
    fun supportsVision_onlyWhenMmprojPresent() {
        assertFalse(model("a.gguf").supportsVision)
        assertTrue(model("a.gguf").copy(mmprojPath = "/m/mmproj.gguf").supportsVision)
    }
}
