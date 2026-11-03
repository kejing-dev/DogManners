package io.embodit.dogmanners.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.pico.spatial.ui.foundation.content.SpatialView
import io.embodit.dogmanners.platform.DogLog
import io.embodit.dogmanners.ui.DogUiState
import io.embodit.dogmanners.ui.Ui
import io.embodit.dogmanners.ui.debug.LocalProbePanel
import io.embodit.dogmanners.ui.debug.Probe
import io.embodit.dogmanners.ui.scene.PanelIds
import io.embodit.dogmanners.ui.scene.ParkScene

/**
 * The Stage content: one full-size SpatialView holding the park and two small attachment panels that go with the 3D
 * content (native-window-move §6): P2, the signal tag that follows the dog, and the [打开面板] chip shown only after
 * the main window's ×. The main panel and the choice card are the system window (ui/window/DogWindow.kt), moved by
 * the system's own caption bar (D21); [ParkScene] parents and places these two.
 */
@Composable
internal fun DogStage(state: DogUiState, scene: ParkScene, onEvent: (Ui) -> Unit) {
    val bindTick = scene.bindTick.intValue
    SpatialView(
        modifier = Modifier.fillMaxSize(),
        attachments = {
            AttachmentPanel(PanelIds.CHIP) { PoolPanel(PanelIds.CHIP, scene) { inView -> OpenChip(inView, onEvent) } }
            AttachmentPanel(PanelIds.TAG) { PoolPanel(PanelIds.TAG, scene) { inView -> TagPanel(if (inView) state else null) } }
        },
        update = { _, attachments ->
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/** Root of a pooled panel: content composes once the panel is in view (Spatial Ruler finding 3); debug logs presses. */
@Composable
private fun PoolPanel(id: String, scene: ParkScene, content: @Composable (inView: Boolean) -> Unit) {
    val inView = scene.panelShown[id] == true
    val watcher =
        Modifier.pointerInput(id) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Move) continue
                    if (Probe.enabled) {
                        val p = event.changes.firstOrNull()?.position
                        DogLog.i(String.format(java.util.Locale.US, "PTR panel=%s ev=%s x=%.1f y=%.1f", id, event.type, p?.x ?: 0f, p?.y ?: 0f))
                    }
                }
            }
        }
    CompositionLocalProvider(LocalProbePanel provides id) {
        Box(modifier = watcher) { content(inView) }
    }
}
