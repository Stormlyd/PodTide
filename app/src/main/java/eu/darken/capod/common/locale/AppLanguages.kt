package eu.darken.capod.common.locale

import android.content.Context
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

enum class AppLanguage(val tag: String) { SYSTEM(""), SIMPLIFIED_CHINESE("zh-CN"), ENGLISH("en") }
object AppLanguages {
    fun current(context: Context): AppLanguage {
        val language = if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales.get(0)?.language
        else AppCompatDelegate.getApplicationLocales().get(0)?.language
        return when (language) { "zh" -> AppLanguage.SIMPLIFIED_CHINESE; "en" -> AppLanguage.ENGLISH; else -> AppLanguage.SYSTEM }
    }
    fun select(context: Context, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language.tag)
        else AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
    }
}
