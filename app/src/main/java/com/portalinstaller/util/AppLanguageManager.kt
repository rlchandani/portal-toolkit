package com.portalinstaller.util

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.portalinstaller.R

enum class AppLanguage(
    val tag: String,
    @StringRes val labelRes: Int
) {
    SYSTEM("system", R.string.language_system),
    ROMANIAN("ro", R.string.language_romanian),
    ENGLISH("en", R.string.language_english)
}

object AppLanguageManager {

    private const val PREFS_NAME = "portal_installer_language"
    private const val KEY_LANGUAGE = "selected_language"

    fun applySavedLanguage(context: Context) {
        applyLanguage(context, getSavedLanguage(context), persist = false)
    }

    fun getSavedLanguage(context: Context): AppLanguage {
        val tag = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, AppLanguage.SYSTEM.tag)
            ?: AppLanguage.SYSTEM.tag
        return AppLanguage.entries.firstOrNull { it.tag == tag } ?: AppLanguage.SYSTEM
    }

    fun applyLanguage(context: Context, language: AppLanguage, persist: Boolean = true) {
        if (persist) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LANGUAGE, language.tag)
                .apply()
        }

        val locales = if (language == AppLanguage.SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(language.tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
