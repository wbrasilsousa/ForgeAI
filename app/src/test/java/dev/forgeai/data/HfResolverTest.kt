package dev.forgeai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contratos do resolvedor de URLs do HuggingFace e do empacotamento de shards.
 * Tudo aqui é puro (sem rede), então roda em qualquer JVM.
 */
class HfResolverTest {

    // ------------------------------------------------------------------
    // parseUrl
    // ------------------------------------------------------------------
    @Test
    fun parseUrl_repoPage() {
        val s = HfResolver.parseUrl("https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF")
        assertEquals("Qwen/Qwen2.5-0.5B-Instruct-GGUF", s.repo)
        assertNull(s.file)
    }

    @Test
    fun parseUrl_treePage() {
        val s = HfResolver.parseUrl("https://huggingface.co/org/repo/tree/main")
        assertEquals("org/repo", s.repo)
        assertNull(s.file)
    }

    @Test
    fun parseUrl_resolveUrl() {
        val s = HfResolver.parseUrl("https://huggingface.co/org/repo/resolve/main/model.q4_k_m.gguf")
        assertEquals("org/repo", s.repo)
        assertEquals("model.q4_k_m.gguf", s.file)
    }

    @Test
    fun parseUrl_blobUrlWithSubdirectory() {
        val s = HfResolver.parseUrl("https://huggingface.co/org/repo/blob/main/sub/dir/model.gguf")
        assertEquals("org/repo", s.repo)
        assertEquals("sub/dir/model.gguf", s.file)
    }

    @Test
    fun parseUrl_hfScheme() {
        val s = HfResolver.parseUrl("hf://org/repo/resolve/main/model.gguf")
        assertEquals("org/repo", s.repo)
        assertEquals("model.gguf", s.file)
    }

    @Test
    fun parseUrl_bareRepoShortcut() {
        val s = HfResolver.parseUrl("prism-ml/Ternary-Bonsai-2-2B-gguf")
        assertEquals("prism-ml/Ternary-Bonsai-2-2B-gguf", s.repo)
        assertNull(s.file)
    }

    @Test
    fun parseUrl_rejectsForeignHost() {
        assertThrows(HfResolver.HfException::class.java) {
            HfResolver.parseUrl("https://example.com/org/repo")
        }
    }

    @Test
    fun parseUrl_rejectsGarbage() {
        assertThrows(HfResolver.HfException::class.java) { HfResolver.parseUrl("") }
        assertThrows(HfResolver.HfException::class.java) { HfResolver.parseUrl("sem-barra") }
    }

    // ------------------------------------------------------------------
    // shards
    // ------------------------------------------------------------------
    @Test
    fun shardInfo_parsesParts() {
        assertEquals("m" to 2, HfResolver.shardInfo("m-00001-of-00002.gguf"))
        assertNull(HfResolver.shardInfo("m-Q4_K_M.gguf"))
        assertNull(HfResolver.shardInfo("m-00001-of-00001.gguf"))
    }

    @Test
    fun baseName_removesShardSuffix() {
        assertEquals("m.gguf", HfResolver.baseName("m-00002-of-00003.gguf"))
        assertEquals("m.gguf", HfResolver.baseName("m.gguf"))
    }

    @Test
    fun shardParts_expandsAllParts() {
        assertEquals(
            listOf("m-00001-of-00002.gguf", "m-00002-of-00002.gguf"),
            HfResolver.shardParts("m-00001-of-00002.gguf"),
        )
        assertNull(HfResolver.shardParts("m.gguf"))
    }

    @Test
    fun mergeShards_groupsIntoOneEntryWithSummedSize() {
        val merged = HfResolver.mergeShards(
            listOf(
                HfResolver.HfFile("m-00001-of-00002.gguf", 100),
                HfResolver.HfFile("m-00002-of-00002.gguf", 50),
                HfResolver.HfFile("solo.gguf", 10),
            )
        )
        assertEquals(2, merged.size)
        val shard = merged.first { it.fileName.startsWith("m-") }
        assertEquals(2, shard.partCount)
        assertEquals(150L, shard.sizeBytes)
    }

    // ------------------------------------------------------------------
    // quantização e casamento do mmproj
    // ------------------------------------------------------------------
    @Test
    fun quantFromName_commonQuants() {
        assertEquals("Q4_K_M", HfResolver.quantFromName("qwen2.5-0.5b-instruct-q4_k_m.gguf"))
        assertEquals("Q8_0", HfResolver.quantFromName("mmproj-LFM2.5-VL-3B-Q8_0.gguf"))
        assertEquals("F16", HfResolver.quantFromName("model-f16.gguf"))
        assertNull(HfResolver.quantFromName("model.gguf"))
    }

    /** Bonsai 2: os packings ternários precisam ser reconhecidos no nome. */
    @Test
    fun quantFromName_ternaryBonsaiPackings() {
        assertEquals("Q2_0", HfResolver.quantFromName("Ternary-Bonsai-2-2B-Q2_0.gguf"))
        assertEquals("PQ2_0", HfResolver.quantFromName("Ternary-Bonsai-2-4B-PQ2_0.gguf"))
        assertEquals("PTQ1_0", HfResolver.quantFromName("Ternary-Bonsai-2-9B-PTQ1_0.gguf"))
        // num nome composto o primeiro quant do fim é o do head (MTP em Q8_0)
        assertEquals(
            "Q8_0",
            HfResolver.quantFromName("Ternary-Bonsai-2-4B-PQ2_0-MTP-Q8_0.gguf"),
        )
    }

    /**
     * O nome base tem que ignorar quantização E marcadores de variante, senão
     * o mmproj de um modelo "-PQ2_0-MTP-Q8_0" nunca casa.
     */
    @Test
    fun visionBase_stripsQuantAndVariantSuffixes() {
        assertEquals("Ternary-Bonsai-2-4B", ModelInfo.visionBase("Ternary-Bonsai-2-4B-PQ2_0-MTP-Q8_0.gguf"))
        assertEquals("Ternary-Bonsai-2-2B", ModelInfo.visionBase("Ternary-Bonsai-2-2B-Q2_0.gguf"))
        assertEquals("Ternary-Bonsai-2-2B", ModelInfo.visionBase("Ternary-Bonsai-2-2B-mmproj-Q8_0.gguf"))
        assertEquals("LFM2.5-VL-3B", ModelInfo.visionBase("mmproj-LFM2.5-VL-3B-BF16.gguf"))
    }

    @Test
    fun findMmproj_pairsBonsai2MtpVariant() {
        val files = listOf(
            HfResolver.HfFile("Ternary-Bonsai-2-4B-PQ2_0-MTP-Q8_0.gguf", 1_575),
            HfResolver.HfFile("Ternary-Bonsai-2-4B-mmproj-Q8_0.gguf", 1_212),
        )
        assertEquals(
            "Ternary-Bonsai-2-4B-mmproj-Q8_0.gguf",
            HfResolver.findMmproj(files, "Ternary-Bonsai-2-4B-PQ2_0-MTP-Q8_0.gguf"),
        )
    }

    @Test
    fun findMmproj_matchesModelAndPrefersQuantizedEncoder() {
        val files = listOf(
            HfResolver.HfFile("LFM2.5-VL-3B-Q4_K_M.gguf", 2_000),
            HfResolver.HfFile("mmproj-LFM2.5-VL-3B-BF16.gguf", 900),
            HfResolver.HfFile("mmproj-LFM2.5-VL-3B-Q8_0.gguf", 400),
            HfResolver.HfFile("mmproj-OutroModelo-Q8_0.gguf", 300),
        )
        assertEquals(
            "mmproj-LFM2.5-VL-3B-Q8_0.gguf",
            HfResolver.findMmproj(files, "LFM2.5-VL-3B-Q4_K_M.gguf"),
        )
    }

    @Test
    fun findMmproj_bonsai2PairsWithQuantizedEncoder() {
        val files = listOf(
            HfResolver.HfFile("Ternary-Bonsai-2-2B-Q2_0.gguf", 547),
            HfResolver.HfFile("Ternary-Bonsai-2-2B-mmproj-BF16.gguf", 671),
            HfResolver.HfFile("Ternary-Bonsai-2-2B-mmproj-Q8_0.gguf", 365),
        )
        assertEquals(
            "Ternary-Bonsai-2-2B-mmproj-Q8_0.gguf",
            HfResolver.findMmproj(files, "Ternary-Bonsai-2-2B-Q2_0.gguf"),
        )
    }

    // ------------------------------------------------------------------
    // encoding
    // ------------------------------------------------------------------
    @Test
    fun encodeRepo_keepsSlashAndEscapesSegments() {
        assertEquals("org/repo", HfResolver.encodeRepo("org/repo"))
        assertEquals("org/repo%20com%20espaco", HfResolver.encodeRepo("org/repo com espaco"))
        assertTrue(HfResolver.encodePathSegment("a b.gguf").contains("%20"))
    }
}
