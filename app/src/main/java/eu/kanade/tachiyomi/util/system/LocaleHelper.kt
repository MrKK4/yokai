package eu.kanade.tachiyomi.util.system

import android.content.Context
import androidx.core.os.LocaleListCompat
import yokai.i18n.MR
import yokai.util.lang.getString
import eu.kanade.tachiyomi.ui.source.SourcePresenter
import java.util.Locale

/**
 * Utility class to change the application's language in runtime.
 */
object LocaleHelper {

    private val flagIdCache = mutableMapOf<String, Int?>()

    fun getFlagResId(context: Context, lang: String): Int? {
        if (flagIdCache.containsKey(lang)) return flagIdCache[lang]

        var flagId = context.resources.getIdentifier(
            "ic_flag_${lang.replace("-", "_")}",
            "drawable",
            context.packageName,
        ).takeIf { it != 0 }

        if (flagId == null && lang.contains("-")) {
            flagId = context.resources.getIdentifier(
                "ic_flag_${lang.split("-").first()}",
                "drawable",
                context.packageName,
            ).takeIf { it != 0 }
        }

        flagIdCache[lang] = flagId
        return flagId
    }

    /**
     * Returns Display name of a string language code
     */
    fun getSourceDisplayName(lang: String?, context: Context): String {
        return when (lang) {
            "", "other" -> context.getString(MR.strings.other)
            SourcePresenter.LAST_USED_KEY -> context.getString(MR.strings.last_used)
            SourcePresenter.PINNED_KEY -> context.getString(MR.strings.pinned)
            "all" -> context.getString(MR.strings.all)
            else -> getLocalizedDisplayName(lang)
        }
    }

    fun getDisplayName(lang: String): String {
        val normalizedLang = when (lang) {
            "zh-CN" -> "zh-Hans"
            "zh-TW" -> "zh-Hant"
            else -> lang
        }

        return Locale.forLanguageTag(normalizedLang).displayName
    }

    /**
     * Returns Display name of a string language code
     *
     * @param lang empty for system language
     */
    fun getLocalizedDisplayName(lang: String?): String {
        if (lang == null) {
            return ""
        }

        val locale = when (lang) {
            "" -> LocaleListCompat.getAdjustedDefault()[0]
            "zh-CN" -> Locale.forLanguageTag("zh-Hans")
            "zh-TW" -> Locale.forLanguageTag("zh-Hant")
            else -> Locale.forLanguageTag(lang)
        }
        return locale!!.getDisplayName(locale).replaceFirstChar { it.uppercase(locale) }
    }
}
