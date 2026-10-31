package io.embodit.dogmanners.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme

/** SpatialUI Vibrant (system scheme) with the design's alert / passable roles and the Noto Sans family. */
@Composable
fun DogTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val system = systemColorScheme(context)
    val colors = remember(system) { system.copy(error = DogColors.Error, alert = DogColors.Wary, passable = DogColors.Relaxed) }
    val family = rememberDogFontFamily()
    val base = PicoTheme.typography
    val typography = remember(base, family) { base.withFamily(family) }
    PicoTheme(colorScheme = colors, typography = typography, content = content)
}
