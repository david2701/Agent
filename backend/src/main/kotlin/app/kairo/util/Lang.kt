package app.kairo.util

import java.util.Locale

/**
 * Languages the server can speak. English is the default; Spanish is the second supported language.
 * The setting `language` can also be `auto` (mirror the user's language), which is not a [Lang]:
 * see [Lang.resolve].
 */
enum class Lang(val code: String, val englishName: String, val locale: Locale) {
    EN("en", "English", Locale.ENGLISH),
    ES("es", "Spanish", Locale.forLanguageTag("es"));

    companion object {
        val DEFAULT = EN

        /** Values accepted by the `language` setting. */
        val SETTING_VALUES = setOf("en", "es", "auto")

        /** `en`, `en-US`, `ES`, `es_CO`... -> language; anything else -> null. */
        fun fromCode(code: String?): Lang? {
            val c = code?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { c == it.code || c.startsWith(it.code + "-") || c.startsWith(it.code + "_") }
        }

        /**
         * Language of an `Accept-Language` header: the tag with the highest weight wins (ties go to the
         * first one listed); Spanish (`es*`) gives [ES], anything else (or no header) gives [EN].
         */
        fun fromAcceptLanguage(header: String?): Lang {
            if (header.isNullOrBlank()) return EN
            val best = header.split(',').withIndex().mapNotNull { (index, raw) ->
                val parts = raw.trim().split(';')
                val tag = parts[0].trim().takeIf { it.isNotEmpty() && it != "*" } ?: return@mapNotNull null
                val q = parts.drop(1).firstNotNullOfOrNull { p ->
                    p.trim().takeIf { it.startsWith("q=", ignoreCase = true) }?.substring(2)?.toDoubleOrNull()
                } ?: 1.0
                Triple(tag, q, index)
            }.filter { it.second > 0.0 }.minWithOrNull(compareByDescending<Triple<String, Double, Int>> { it.second }.thenBy { it.third })
            return if (fromCode(best?.first) == ES) ES else EN
        }

        /**
         * Language to use for server-written text: the `language` setting when it is `en` or `es`, otherwise
         * (`auto`, missing or unknown) the language of the request, otherwise [default].
         */
        fun resolve(setting: String?, acceptLanguage: String? = null, default: Lang = DEFAULT): Lang =
            fromCode(setting) ?: if (acceptLanguage.isNullOrBlank()) default else fromAcceptLanguage(acceptLanguage)
    }
}
