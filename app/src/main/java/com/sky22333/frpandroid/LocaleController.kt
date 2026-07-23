package com.sky22333.frpandroid

import android.content.Context
import android.content.res.Configuration
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
                Locale.setDefault(context.resources.configuration.locales[0])
                return context
            }
            LanguageMode.Chinese -> Locale.forLanguageTag("zh-CN")
            LanguageMode.English -> Locale.ENGLISH
        }
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList(locale))
        return context.createConfigurationContext(configuration)
    }
}
