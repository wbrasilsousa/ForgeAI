package dev.forgeai.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parâmetros de inferência de UM modelo específico. Cada modelo guarda a
 * própria configuração (Geração + Memória e velocidade), persistida em
 * SharedPreferences como JSON, chaveada pelo caminho do arquivo GGUF.
 */
data class ModelSettings(
    // geração
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val maxTokens: Int = 512,
    val randomSeed: Boolean = true,
    val fixedSeed: Long = 42L,
    // memória e velocidade
    val nCtx: Int = 2048,
    val threads: Int = 4,
    val gpuLayers: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("temperature", temperature)
        put("topK", topK)
        put("topP", topP)
        put("minP", minP)
        put("repeatPenalty", repeatPenalty)
        put("maxTokens", maxTokens)
        put("randomSeed", randomSeed)
        put("fixedSeed", fixedSeed)
        put("nCtx", nCtx)
        put("threads", threads)
        put("gpuLayers", gpuLayers)
    }

    companion object {
        fun fromJson(o: JSONObject): ModelSettings = ModelSettings(
            temperature = o.optDouble("temperature", 0.7).toFloat(),
            topK = o.optInt("topK", 40),
            topP = o.optDouble("topP", 0.95).toFloat(),
            minP = o.optDouble("minP", 0.05).toFloat(),
            repeatPenalty = o.optDouble("repeatPenalty", 1.1).toFloat(),
            maxTokens = o.optInt("maxTokens", 512),
            randomSeed = o.optBoolean("randomSeed", true),
            fixedSeed = o.optLong("fixedSeed", 42L),
            nCtx = o.optInt("nCtx", 2048),
            threads = o.optInt("threads", 4),
            gpuLayers = o.optInt("gpuLayers", 0),
        )
    }
}

/**
 * Configurações globais do app, persistidas em SharedPreferences como JSON.
 * Escopo global: ferramentas, aparência, prompt do sistema.
 */
data class AppSettings(
    val systemPrompt: String = "Você é um assistente de IA útil, conciso e amigável. Responda em português do Brasil, salvo se o usuário pedir outro idioma.",
    // tools
    val webAccess: Boolean = false,
    // ui
    val darkTheme: Boolean = true,
    val dynamicColor: Boolean = true,
    val immersive: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("systemPrompt", systemPrompt)
        put("webAccess", webAccess)
        put("darkTheme", darkTheme)
        put("dynamicColor", dynamicColor)
        put("immersive", immersive)
    }

    companion object {
        fun fromJson(o: JSONObject): AppSettings = AppSettings(
            systemPrompt = o.optString("systemPrompt", ""),
            webAccess = o.optBoolean("webAccess", false),
            darkTheme = o.optBoolean("darkTheme", true),
            dynamicColor = o.optBoolean("dynamicColor", true),
            immersive = o.optBoolean("immersive", true),
        )
    }
}

class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("forgeai", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        return runCatching { AppSettings.fromJson(JSONObject(raw)) }.getOrDefault(AppSettings())
    }

    fun save(settings: AppSettings) {
        prefs.edit().putString(KEY_SETTINGS, settings.toJson().toString()).apply()
    }

    /**
     * Carrega as configurações de inferência de um modelo. Na primeira vez que
     * um modelo é visto (não há entrada própria), migra os valores antigos que
     * ficavam no JSON global (antes da separação por modelo).
     */
    fun loadModelSettings(path: String): ModelSettings {
        val modelRaw = modelSettingsJson().optJSONObject(path)
        if (modelRaw != null) {
            return runCatching { ModelSettings.fromJson(modelRaw) }.getOrDefault(ModelSettings())
        }
        // migração: primeira vez deste modelo → aproveita os valores globais antigos
        val legacy = runCatching {
            JSONObject(prefs.getString(KEY_SETTINGS, null) ?: "{}")
        }.getOrDefault(JSONObject())
        val migrated = ModelSettings.fromJson(legacy)
        saveModelSettings(path, migrated) // torna a migração durável por modelo
        return migrated
    }

    fun saveModelSettings(path: String, ms: ModelSettings) {
        val map = modelSettingsJson()
        map.put(path, ms.toJson())
        prefs.edit().putString(KEY_MODEL_SETTINGS, map.toString()).apply()
    }

    private fun modelSettingsJson(): JSONObject =
        runCatching {
            JSONObject(prefs.getString(KEY_MODEL_SETTINGS, null) ?: "{}")
        }.getOrDefault(JSONObject())

    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun getString(key: String): String? = prefs.getString(key, null)

    // ------------------------------------------------------------------
    // Models that never close <think> (e.g. Bonsai Q1_0): their answer is
    // written inside the open block, so the UI streams it live per model.
    // ------------------------------------------------------------------
    fun isOpenThinkModel(path: String): Boolean {
        val raw = prefs.getString(KEY_OPEN_THINK_MODELS, null) ?: return false
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).any { arr.optString(it) == path }
        }.getOrDefault(false)
    }

    fun setOpenThinkModel(path: String, openThink: Boolean) {
        val arr = JSONArray()
        prefs.getString(KEY_OPEN_THINK_MODELS, null)?.let { raw ->
            runCatching {
                val old = JSONArray(raw)
                for (i in 0 until old.length()) {
                    val p = old.optString(i)
                    if (p.isNotEmpty() && p != path) arr.put(p)
                }
            }
        }
        if (openThink) arr.put(path)
        prefs.edit().putString(KEY_OPEN_THINK_MODELS, arr.toString()).apply()
    }

    companion object {
        private const val KEY_SETTINGS = "settings"
        private const val KEY_MODEL_SETTINGS = "model_settings_v2"
        private const val KEY_OPEN_THINK_MODELS = "open_think_models"
        const val KEY_CHAT = "chat_history"
        const val KEY_CHATS = "chat_history_v2"
        const val KEY_ACTIVE_CHAT = "active_chat"
        const val KEY_LAST_MODEL = "last_model"
    }
}
