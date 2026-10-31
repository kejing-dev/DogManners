package io.embodit.dogmanners.ui

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import io.embodit.dogmanners.platform.DogLog
import io.embodit.dogmanners.ui.components.DogStage
import io.embodit.dogmanners.ui.components.LocalDogResources
import io.embodit.dogmanners.ui.components.localizedResources
import io.embodit.dogmanners.ui.debug.Probe
import io.embodit.dogmanners.ui.scene.ParkFrameSystem
import io.embodit.dogmanners.ui.scene.ParkScene

/** The one screen: wires [DogController] (state), [ParkScene] (entities, per-frame work) and [DogStage] (panels). */
@Composable
fun DogScreen() {
    val context = LocalContext.current
    remember(context) {
        Probe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        DogController.start(context)
        true
    }
    val state by DogController.state.collectAsStateWithLifecycle()
    val scene = remember { ParkScene() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(scene, lifecycle) {
        registerSystem<ParkFrameSystem>()
        Probe.host = object : Probe.Host { override fun scene(): ParkScene = scene }
        // background: tracking stops, the dog's timers pause (scene stops stepping); back: no re-placement, a hint
        val observer =
            LifecycleEventObserver { _, event ->
                DogLog.i("LIFECYCLE event=$event")
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        scene.pause()
                        DogController.onEvent(Ui.Background(true))
                    }
                    Lifecycle.Event.ON_START -> {
                        scene.resume()
                        DogController.onEvent(Ui.Background(false))
                    }
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        DogLog.i("STAGE enter")
        onDispose {
            lifecycle.removeObserver(observer)
            Probe.host = null
            unregisterSystem<ParkFrameSystem>()
            scene.detach()
            DogLog.i("STAGE disposed")
        }
    }

    val resources = remember(state.lang, context) { localizedResources(context, state.lang) }
    CompositionLocalProvider(LocalDogResources provides resources) {
        DogStage(state = state, scene = scene, onEvent = DogController::onEvent)
    }
}
