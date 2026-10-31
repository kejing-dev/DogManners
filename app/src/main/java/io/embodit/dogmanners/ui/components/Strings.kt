package io.embodit.dogmanners.ui.components

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import io.embodit.dogmanners.ui.Lang
import java.util.Locale

/**
 * Copy for the language chosen in the app (follow system / 中文 / English), switched live (Rehearsal Hall Strings.kt):
 * the screen provides a Resources object for that locale and every panel reads its strings from it.
 */
val LocalDogResources = staticCompositionLocalOf<Resources> { error("LocalDogResources not provided") }

fun localizedResources(context: Context, lang: Lang): Resources {
    val tag =
        when (lang) {
            Lang.SYSTEM -> return context.resources
            Lang.ZH -> "zh-CN"
            Lang.EN -> "en"
        }
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(tag))
    return context.createConfigurationContext(configuration).resources
}

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int): String = LocalDogResources.current.getString(id)

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int, vararg args: Any): String = LocalDogResources.current.getString(id, *args)

/** Panel sizes (design panels[].size), dp; 0.0008 m per dp at scale 1. */
object PanelSizes {
    const val MAIN_W = 820f
    const val MAIN_H = 600f
    const val TAG_W = 420f
    const val TAG_H = 200f
    const val CARD_W = 650f
    const val CARD_H = 500f
}
