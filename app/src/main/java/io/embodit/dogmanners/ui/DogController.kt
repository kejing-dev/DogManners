package io.embodit.dogmanners.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import io.embodit.dogmanners.domain.LessonId
import io.embodit.dogmanners.domain.Leniency
import io.embodit.dogmanners.domain.Mode
import io.embodit.dogmanners.domain.Mood
import io.embodit.dogmanners.domain.Outcome
import io.embodit.dogmanners.domain.Personality
import io.embodit.dogmanners.domain.Scenario
import io.embodit.dogmanners.domain.Snapshot
import io.embodit.dogmanners.platform.DogLog
import io.embodit.dogmanners.platform.EyePermissionActivity
import io.embodit.dogmanners.platform.Sound
import io.embodit.dogmanners.platform.Store
import io.embodit.dogmanners.ui.window.MainWindowStore
import io.embodit.dogmanners.ui.window.exitApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow

/** Everything the panels (and the debug receiver) can ask for. One path for real taps and debug commands. */
sealed interface Ui {
    data object Start : Ui
    data class OpenLesson(val id: LessonId) : Ui
    data object BeginLesson : Ui
    data object SkipIntro : Ui
    data object Restart : Ui
    data object Back : Ui
    /** The 小场面 page of the main window's TabBar. */
    data object Home : Ui
    data object Next : Ui
    data class Choose(val index: Int) : Ui
    data object OpenGuide : Ui
    data class GuideMood(val mood: Mood) : Ui
    data object OpenFree : Ui
    data object AnotherDog : Ui
    data object OpenSettings : Ui
    data class SetLang(val lang: Lang) : Ui
    data object ToggleSound : Ui
    data object ToggleBirds : Ui
    data object Bring : Ui
    /** The Stage chip after the main window's ×. */
    data object OpenPanel : Ui
    data class SetSeated(val seated: Boolean) : Ui
    data object ToggleTag : Ui
    data object AskEye : Ui
    data object ResetProgress : Ui
    data object QuitAsk : Ui
    data object QuitCancel : Ui
    data object QuitYes : Ui
    data class Background(val on: Boolean) : Ui
}

/**
 * All behaviour (state machine of the screens, lessons, preferences, permissions, exit). The per-frame dog logic lives
 * in [scenario], which the scene steps with the head data; it calls [onSnapshot] only when what the panels show changes
 * (tracking data never goes through Compose state). Main thread only.
 */
object DogController {
    private val _state = MutableStateFlow(DogUiState())
    val state: StateFlow<DogUiState> = _state

    /** The running scene logic (null while nothing runs). The scene steps it; panels read [DogUiState.snap]. */
    var scenario: Scenario = Scenario(Mode.Home)
        private set

    var activity: Activity? = null
    private var store: Store? = null
    private var sound: Sound? = null
    private var appContext: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private var started = false

    /** D27: one [Leniency] per lesson (and one for free mode), kept across [再玩一次] so near misses add up. */
    private val leniencies = HashMap<String, Leniency>()

    private fun leniencyFor(mode: Mode): Leniency =
        when (mode) {
            is Mode.Lesson -> leniencies.getOrPut(mode.id.name) { Leniency() }
            is Mode.Free -> leniencies.getOrPut("FREE") { Leniency() }
            else -> Leniency()
        }

    fun start(context: Context) {
        activity = findActivity(context)
        if (started) return
        started = true
        appContext = context.applicationContext
        val s = Store(context.applicationContext)
        store = s
        sound = Sound(context.applicationContext).also { it.on = s.soundOn; it.birdsOn = s.birdsOn; it.start() }
        val lang = runCatching { Lang.valueOf(s.lang) }.getOrDefault(Lang.SYSTEM)
        _state.value =
            DogUiState(
                lang = lang,
                soundOn = s.soundOn,
                birdsOn = s.birdsOn,
                seated = s.seated,
                tagOn = s.tagOn,
                completed = s.completed,
                introsSeen = s.introsSeen,
                disclaimerSeen = s.disclaimerSeen,
                eyeAsked = s.eyeAsked,
                eye = if (EyePermissionActivity.granted(context)) EyeState.UNKNOWN else EyeState.NO_PERMISSION,
            )
        EyePermissionActivity.onResult = { ok, blocked -> main.post { onEyePermission(ok, blocked) } }
        DogLog.i("CONTROLLER start state=${_state.value.designState}")
    }

    private fun findActivity(c: Context): Activity? {
        var x: Context? = c
        while (x != null) {
            if (x is Activity) return x
            x = (x as? ContextWrapper)?.baseContext
        }
        return null
    }

    val soundOrNull: Sound?
        get() = sound

    private fun set(why: String, f: (DogUiState) -> DogUiState) {
        val before = _state.value
        val after = f(before)
        if (after == before) return
        _state.value = after
        if (after.designState != before.designState || after.screen != before.screen) DogLog.i("STATE ${after.designState} screen=${after.screen} lesson=${after.lesson} why=$why")
    }

    // ------------------------------------------------------------------------------------------------ scene callbacks

    /** The scene finished building the park and calibrating the first dog: S1 -> S3 or S2. */
    fun onSceneReady() {
        val s = _state.value
        if (s.screen != Screen.STARTING) return
        set("ready") { it.copy(screen = if (it.disclaimerSeen) Screen.HOME else Screen.DISCLAIMER, loading = false) }
        startScenario(Mode.Home)
    }

    /** What the panels show changed (a few times per scene). */
    fun onSnapshot(snap: Snapshot) {
        val before = _state.value.snap
        set("snap") { it.copy(snap = snap) }
        if (snap.near != null && snap.near != before?.near) DogLog.i("LENIENT lesson=${_state.value.lesson} near=${snap.near.cause} loosened=${snap.near.loosened} level=${snap.near.level}")
        if (snap.outcome != null && before?.outcome == null) {
            sound?.chime()
            val id = _state.value.lesson
            if (snap.outcome == Outcome.OK && id != null) {
                val done = _state.value.completed + id.n
                store?.completed = done
                set("done") { it.copy(completed = done) }
            }
            DogLog.i("RESULT lesson=$id outcome=${snap.outcome} text=${snap.result} goals=${snap.goals}")
        }
    }

    /** The eye source changed (scene, on change only). */
    fun onEyeSource(state: EyeState) {
        if (_state.value.eye == state) return
        set("eye") { it.copy(eye = state) }
        DogLog.i("EYE source=$state")
    }

    /** Free mode: the dog walked off; a new one comes after a short pause. */
    fun onFreeDogGone() {
        if (_state.value.screen != Screen.FREE) return
        main.postDelayed({ if (_state.value.screen == Screen.FREE) onEvent(Ui.AnotherDog) }, 2500)
    }

    private fun startScenario(mode: Mode, personality: Personality = Personality.NORMAL) {
        scenario = Scenario(mode, personality, leniencyFor(mode))
        set("scenario") { it.copy(snap = scenario.snapshot(), sceneEpoch = it.sceneEpoch + 1) }
        DogLog.i("SCENARIO mode=$mode personality=$personality dogs=${scenario.roles.map { it.lineup }} props=${scenario.props} leniency=${scenario.leniency.level}")
    }

    // ------------------------------------------------------------------------------------------------------- events

    fun onEvent(e: Ui) {
        DogLog.i("EVENT $e")
        val s = _state.value
        when (e) {
            Ui.Start -> {
                store?.disclaimerSeen = true
                set("start") { it.copy(screen = Screen.HOME, disclaimerSeen = true) }
                if (!s.eyeAsked && s.eye != EyeState.ON) askEye()
            }
            is Ui.OpenLesson -> {
                set("lesson") { it.copy(screen = Screen.INTRO, lesson = e.id, quitConfirm = false) }
                startScenarioForIntro(e.id)
            }
            Ui.BeginLesson, Ui.SkipIntro -> {
                val id = s.lesson ?: return
                val seen = s.introsSeen + id.n
                store?.introsSeen = seen
                set("begin") { it.copy(screen = Screen.LESSON, introsSeen = seen) }
                startScenario(Mode.Lesson(id))
            }
            Ui.Restart -> when (s.screen) {
                Screen.LESSON, Screen.INTRO -> s.lesson?.let { id ->
                    set("restart") { it.copy(screen = Screen.LESSON) }
                    startScenario(Mode.Lesson(id))
                }
                Screen.FREE -> onEvent(Ui.AnotherDog)
                else -> Unit
            }
            Ui.Next -> {
                val next = s.lesson?.next
                if (next == null) goHome("next-end") else onEvent(Ui.OpenLesson(next))
            }
            is Ui.Choose -> {
                scenario.choose(e.index)
                set("choose") { it.copy(snap = scenario.snapshot()) }
            }
            Ui.Back -> when (s.screen) {
                Screen.SETTINGS -> set("back") { it.copy(screen = it.settingsFrom, quitConfirm = false) }
                else -> goHome("back")
            }
            // back to the scene you opened settings from (a running lesson keeps going), else home
            Ui.Home -> when {
                s.screen == Screen.SETTINGS && s.settingsFrom !in setOf(Screen.GUIDE, Screen.FREE) -> set("tab") { it.copy(screen = it.settingsFrom, quitConfirm = false) }
                s.screen in setOf(Screen.GUIDE, Screen.FREE, Screen.SETTINGS) -> goHome("tab")
                else -> Unit
            }
            Ui.OpenGuide -> {
                set("guide") { it.copy(screen = Screen.GUIDE, lesson = null, guideMood = Mood.WARY) }
                startScenario(Mode.Guide(Mood.WARY))
            }
            is Ui.GuideMood -> {
                set("guideMood") { it.copy(guideMood = e.mood) }
                startScenario(Mode.Guide(e.mood))
            }
            Ui.OpenFree -> openFree(s.freeSeed)
            Ui.AnotherDog -> openFree(s.freeSeed + 1)
            Ui.OpenSettings -> if (s.screen != Screen.SETTINGS) set("settings") { it.copy(screen = Screen.SETTINGS, settingsFrom = it.screen, quitConfirm = false, resetDone = false) }
            is Ui.SetLang -> {
                store?.lang = e.lang.name
                set("lang") { it.copy(lang = e.lang) }
            }
            Ui.ToggleSound -> {
                val on = !s.soundOn
                store?.soundOn = on
                sound?.on = on
                set("sound") { it.copy(soundOn = on) }
            }
            Ui.OpenPanel -> MainWindowStore.open("chip")
            Ui.ToggleBirds -> {
                val on = !s.birdsOn
                store?.birdsOn = on
                sound?.birdsOn = on
                set("birds") { it.copy(birdsOn = on) }
            }
            // the park is laid out again from where you look, and the main window is closed and opened in front of you
            // (a system window cannot be moved by code: native-window-move §3.5)
            Ui.Bring -> {
                set("bring") { it.copy(placeEpoch = it.placeEpoch + 1, backHint = false) }
                MainWindowStore.recall()
            }
            is Ui.SetSeated -> {
                store?.seated = e.seated
                set("seated") { it.copy(seated = e.seated, placeEpoch = it.placeEpoch + 1) }
            }
            Ui.ToggleTag -> {
                store?.tagOn = !s.tagOn
                set("tag") { it.copy(tagOn = !it.tagOn) }
            }
            Ui.AskEye -> askEye()
            Ui.ResetProgress -> {
                store?.clearProgress()
                leniencies.clear()
                set("reset") { it.copy(completed = emptySet(), introsSeen = emptySet(), resetDone = true) }
            }
            Ui.QuitAsk -> set("quitAsk") { it.copy(quitConfirm = true) }
            Ui.QuitCancel -> set("quitCancel") { it.copy(quitConfirm = false) }
            Ui.QuitYes -> quit()
            is Ui.Background -> if (!e.on && s.screen != Screen.STARTING) set("foreground") { it.copy(backHint = true) }
        }
    }

    private fun startScenarioForIntro(id: LessonId) {
        // the scene's props and dog are already set while you read the intro (design S5 "情景道具已摆好")
        scenario = Scenario(Mode.Lesson(id)).also { it.freeze = true }
        set("intro") { it.copy(snap = scenario.snapshot(), sceneEpoch = it.sceneEpoch + 1) }
    }

    private fun openFree(seed: Int) {
        val p = Personality.entries[Math.floorMod(seed, 3)]
        set("free") { it.copy(screen = Screen.FREE, lesson = null, freeSeed = seed, personality = p) }
        startScenario(Mode.Free(seed), p)
    }

    private fun goHome(why: String) {
        set(why) { it.copy(screen = Screen.HOME, lesson = null, quitConfirm = false) }
        startScenario(Mode.Home)
    }

    private fun askEye() {
        val c = appContext ?: return
        store?.eyeAsked = true
        set("askEye") { it.copy(eyeAsked = true) }
        if (_state.value.eye == EyeState.DENIED) return
        if (!EyePermissionActivity.open(c)) DogLog.w("EYE permission activity did not open")
    }

    private fun onEyePermission(ok: Boolean, blocked: Boolean) {
        set("eyePermission") { it.copy(eye = if (ok) EyeState.UNKNOWN else if (blocked) EyeState.DENIED else EyeState.NO_PERMISSION) }
    }

    /** D26 exit, the main window template's order: release, close the window and the Stage, remove the tasks, end. */
    private fun quit() {
        DogLog.i("EXIT confirmed")
        val nav = MainWindowStore.navigator
        val c = appContext
        if (nav != null && c != null) {
            MainWindowStore.scope.launch {
                runCatching { nav.closeWindowContainer(MainWindowStore.ID, MainWindowStore.TAG) }
                exitApp(c, nav) { sound?.release(); sound = null }
            }
            return
        }
        sound?.release()
        sound = null
        runCatching { activity?.finishAndRemoveTask() }
        main.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 300)
    }

    // ------------------------------------------------------------------------------------------------- debug seams

    fun debugLabel(label: String?) = set("label") { it.copy(label = label) }

    fun debugFirstRun() {
        store?.clearAll()
        set("firstRun") { DogUiState(screen = Screen.DISCLAIMER, loading = false, eye = it.eye, sceneEpoch = it.sceneEpoch, placeEpoch = it.placeEpoch) }
        startScenario(Mode.Home)
    }

    fun debugEye(state: EyeState?) = state?.let { onEyeSource(it) }
}
