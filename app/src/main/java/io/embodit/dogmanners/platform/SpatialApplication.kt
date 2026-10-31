package io.embodit.dogmanners.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.dogmanners.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
