package io.embodit.dogmanners

import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import io.embodit.dogmanners.ui.DogScreen
import io.embodit.dogmanners.ui.theme.DogTheme

/**
 * App entry (design container STAGE_FULL, skeleton C). The default Stage is Full (manifest pico.spatial.stage.style=3):
 * the park opens around you; there is no Shared Space window.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage { DogTheme { DogScreen() } }
    }
