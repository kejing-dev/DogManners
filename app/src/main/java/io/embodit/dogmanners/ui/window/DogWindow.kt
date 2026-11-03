package io.embodit.dogmanners.ui.window

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.dogmanners.R
import io.embodit.dogmanners.platform.DogLog
import io.embodit.dogmanners.ui.DogController
import io.embodit.dogmanners.ui.Screen
import io.embodit.dogmanners.ui.Ui
import io.embodit.dogmanners.ui.components.LessonsPage
import io.embodit.dogmanners.ui.components.FreePage
import io.embodit.dogmanners.ui.components.GuidePage
import io.embodit.dogmanners.ui.components.LocalDogResources
import io.embodit.dogmanners.ui.components.SettingsPageContent
import io.embodit.dogmanners.ui.components.localizedResources
import io.embodit.dogmanners.ui.components.rs
import io.embodit.dogmanners.ui.debug.LocalProbePanel
import io.embodit.dogmanners.ui.theme.DogTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The main window (v0.1.2, profile D8 / D21, prompts/snippets/native-window-move.md): ONE system Planar window holds
 * every 2D page (the old P1 main panel and the P3 choice / result card). Moving, closing and minimising it are the
 * system caption bar under the window; the app has no drag code. Pages: 小场面 (home / intro / lesson + its choice
 * and result) · 狗语小词典 · 随便逛逛 · 设置 (with the in-window quit confirm), switched with the system TabBar.
 */
object MainWindowStore {
    const val ID = "DogMain"
    const val TAG = "main"

    /** The window's lifecycle as the window itself reports it ("never" until it first opens). */
    var state by mutableStateOf("never")
        private set

    /** Set by the Stage composition (the window can be closed; the Stage stays). */
    var navigator: SpatialNavigator? = null

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private var recallPending = false

    val isOpen: Boolean
        get() = state != "never" && state != "ON_DESTROY"

    fun open(why: String) {
        val n = navigator ?: return
        DogLog.i("WINDOW open why=$why state=$state")
        runCatching { n.openWindowContainer(ID, TAG, null) }.onFailure { DogLog.e("WINDOW open failed: $it") }
    }

    /**
     * [回到面前]: an open window cannot be moved by code and a second open does not move it, so close it and open it
     * again once it reported ON_DESTROY (an open right after a close is swallowed: native-window-move §3.5).
     */
    fun recall() {
        val n = navigator ?: return
        if (!isOpen) {
            open("recall")
            return
        }
        recallPending = true
        DogLog.i("WINDOW recall close")
        runCatching { n.closeWindowContainer(ID, TAG) }.onFailure { DogLog.e("WINDOW close failed: $it") }
    }

    fun onLifecycle(event: String) {
        state = event
        DogLog.i("WINDOW lifecycle=$event")
        if (event == "ON_DESTROY" && recallPending) {
            recallPending = false
            main.postDelayed({ open("recall") }, 300)
        }
    }
}

/** Page ids of the main window's TabBar (the last one is the template's settings page). */
private const val PAGE_LESSONS = "lessons"
private const val PAGE_GUIDE = "guide"
private const val PAGE_FREE = "free"

private fun pageOf(screen: Screen): String =
    when (screen) {
        Screen.GUIDE -> PAGE_GUIDE
        Screen.FREE -> PAGE_FREE
        Screen.SETTINGS -> SETTINGS_PAGE
        else -> PAGE_LESSONS
    }

/** The window's content: the template, driven by the controller's screen (TabBar taps are ordinary Ui events). */
@Composable
fun DogWindow() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> MainWindowStore.onLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val state by DogController.state.collectAsStateWithLifecycle()
    val onEvent = DogController::onEvent
    val resources = remember(state.lang, context) { localizedResources(context, state.lang) }
    CompositionLocalProvider(LocalDogResources provides resources, LocalProbePanel provides "window") {
        MainWindow(
            appName = rs(R.string.app_name),
            pages =
                listOf(
                    MainWindowPage(PAGE_LESSONS, rs(R.string.tab_lessons), R.drawable.ic_home) { LessonsPage(state, onEvent) },
                    MainWindowPage(PAGE_GUIDE, rs(R.string.guide), R.drawable.ic_menu_book) { GuidePage(state, onEvent) },
                    MainWindowPage(PAGE_FREE, rs(R.string.free), R.drawable.ic_paw) { FreePage(state, onEvent) },
                ),
            settingsIcon = R.drawable.ic_settings,
            settings = {},
            onExit = {},
            current = pageOf(state.screen),
            onSelect = { id ->
                if (id != pageOf(state.screen)) {
                    when (id) {
                        PAGE_GUIDE -> onEvent(Ui.OpenGuide)
                        PAGE_FREE -> onEvent(Ui.OpenFree)
                        SETTINGS_PAGE -> onEvent(Ui.OpenSettings)
                        else -> onEvent(Ui.Home)
                    }
                }
            },
            settingsLabel = rs(R.string.settings),
            showPageTitle = false,
            settingsPage = { SettingsPageContent(state, onEvent) },
            theme = { content -> DogTheme { content() } },
        )
    }
}
