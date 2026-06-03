package com.sky22333.frpandroid

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.sky22333.frpandroid.core.data.SettingsStore
import com.sky22333.frpandroid.core.frp.LanguageMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Locale

object LocaleController {
    fun readLanguageMode(context: Context): LanguageMode =
        runBlocking {
            SettingsStore(context.applicationContext).settings.first().languageMode
        }

    fun wrap(context: Context, languageMode: LanguageMode): Context {
        val locale = when (languageMode) {
            LanguageMode.System -> {
                Locale.setDefault(context.currentLocale())
                return context
            }
            LanguageMode.Chinese -> Locale.forLanguageTag("zh-CN")
            LanguageMode.English -> Locale.ENGLISH
        }
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocales(LocaleList(locale))
        } else {
            configuration.setLocale(locale)
        }
        return context.createConfigurationContext(configuration)
    }

    private fun Context.currentLocale(): Locale =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            resources.configuration.locale
        }
}
