package dev.forgeai.data

/**
 * Helpers para extrair texto legível de HTML — compartilhados por WebAccess
 * e WebSearch.
 */
object HtmlText {

    fun stripTags(s: String): String = Regex("<[^>]*>").replace(s, " ")

    fun decodeEntities(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i)
                if (semi in (i + 1)..(i + 14)) {
                    val ent = s.substring(i + 1, semi)
                    val decoded = when {
                        ent.startsWith("#x") || ent.startsWith("#X") ->
                            ent.substring(2).toIntOrNull(16)?.let { code ->
                                runCatching { String(Character.toChars(code)) }.getOrNull()
                            }
                        ent.startsWith("#") ->
                            ent.substring(1).toIntOrNull()?.let { code ->
                                runCatching { String(Character.toChars(code)) }.getOrNull()
                            }
                        else -> HTML_ENTITIES[ent]
                    }
                    if (decoded != null) {
                        out.append(decoded)
                        i = semi + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** Converte um trecho de HTML em texto plano (tags removidas + entidades). */
    fun toText(html: String): String = decodeEntities(stripTags(html))

    private val HTML_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "copy" to "©", "reg" to "®", "trade" to "™",
        "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢",
        "deg" to "°", "plusmn" to "±", "middot" to "·", "bull" to "•",
        "hellip" to "…", "mdash" to "—", "ndash" to "–",
        "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“",
        "ouml" to "ö", "auml" to "ä", "uuml" to "ü", "ccedil" to "ç",
        "eacute" to "é", "aacute" to "á", "iacute" to "í", "oacute" to "ó",
        "uacute" to "ú", "atilde" to "ã", "otilde" to "õ",
    )
}
