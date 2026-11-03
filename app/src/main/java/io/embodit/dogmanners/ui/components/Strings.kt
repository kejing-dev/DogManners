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
    /** The main system window (dp): the old P1 page plus the P3 card under the coach line. */
    const val WINDOW_W = 900f
    const val WINDOW_H = 760f
    const val TAG_W = 420f
    const val TAG_H = 200f
    /** Stage chip [打开面板] (shown only while the window is closed). */
    const val CHIP_W = 300f
    const val CHIP_H = 96f
}
