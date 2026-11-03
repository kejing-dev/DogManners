package io.embodit.dogmanners

import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.dogmanners.ui.DogScreen
import io.embodit.dogmanners.ui.components.PanelSizes
import io.embodit.dogmanners.ui.theme.DogTheme
import io.embodit.dogmanners.ui.window.DogWindow
import io.embodit.dogmanners.ui.window.MainWindowStore

/**
 * App entry (design container STAGE_FULL, skeleton C). The default Stage is Full (manifest pico.spatial.stage.style=3):
 * the park opens around you. v0.1.2 (D8 / D21): all 2D pages live in ONE system Planar window opened from the Stage;
 * the system moves / closes it (caption bar under the window), the app has no drag code.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage { DogTheme { DogScreen() } }
        WindowContainer(
            MainWindowStore.ID,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = PanelSizes.WINDOW_W.dp, height = PanelSizes.WINDOW_H.dp),
        ) { DogWindow() }
    }
