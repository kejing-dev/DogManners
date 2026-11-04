package io.embodit.dogmanners.debug

import android.content.Context
import io.embodit.dogmanners.domain.LessonId
import io.embodit.dogmanners.domain.Mood
import io.embodit.dogmanners.platform.DogLog
import io.embodit.dogmanners.ui.DogController
import io.embodit.dogmanners.ui.EyeState
import io.embodit.dogmanners.ui.Lang
import io.embodit.dogmanners.ui.Ui
import io.embodit.dogmanners.ui.debug.Probe
import io.embodit.dogmanners.ui.window.MainWindowStore

/**
 * Debug builds only (QA hooks, design verification.emulator). Every command goes through the same events / inputs as a
 * user would (DogController.onEvent, or the body inputs fed to BodyReader before the dog's state machine): there is no
 * shortcut that writes a dog state directly (factory rule 2026-10-29).
 *
 *   adb -s emulator-5556 shell "am broadcast -a io.embodit.dogmanners.DEBUG -p io.embodit.dogmanners --es cmd 'lesson L1'"
 *
 * Screens: home | firstRun | intro L1..L6 | lesson L1..L6 | guide [wary|relaxed|play] | free [seed] | settings | back |
 *          next | again | choose N (0-based) | start | bring | quitAsk | quitCancel | quitYes (same Ui.QuitYes as the key)
 * Body:    stare 0|1|auto | lean M|auto (the head moves M towards the dog, like a real lean) | yaw DEG (turn the face,
 *          + left) | eye off|on | dist M (dog nose distance) |
 *          pressure V (0..4) | dog wary|relaxed|play (input macros: wary = stare 1 + pressure 4; relaxed = stare 0,
 *          lean 0, yaw 60; play = relaxed + dist 1.3 -> it sniffs, then bows)
 * Prefs:   lang zh|en|system | sound on|off | seated 0|1 | tag on|off | resetProgress
 * Window:  panel open (= the chip's Ui.OpenPanel) | panel recall (= [回到面前]'s window part) | panel state
 * Shots:   view DEG (turn the park by DEG at re-placement; the window stays where the system put it: 38 = the dog's
 *          path straight ahead, 0 = back) | ftest on|off (F↑ on the ground label) | label TEXT | label off |
 *          light ibl EXP | light sun LUX | light shadows on|off (v0.1.3 lights, live; logs LIGHT ...)
 * Dump:    dump | where | panels | instances | pose | bounds window|p2|chip (every probed control's rect vs its panel)
 */
object DebugCommands {
    fun run(context: Context, cmd: String, s: String?, a: String?) {
        val scene = Probe.host?.scene()
        val r = scene?.reader
        val arg = s?.trim().orEmpty()
        val result: String =
            when (cmd) {
                "home" -> ev(Ui.Back).also { if (DogController.state.value.screen.name != "HOME") ev(Ui.Back) }
                "firstRun" -> { DogController.debugFirstRun(); "first run" }
                "start" -> ev(Ui.Start)
                "intro" -> ev(Ui.OpenLesson(lesson(arg)))
                "lesson" -> { ev(Ui.OpenLesson(lesson(arg))); ev(Ui.BeginLesson) }
                "guide" -> {
                    ev(Ui.OpenGuide)
                    if (arg.isNotEmpty()) ev(Ui.GuideMood(Mood.valueOf(arg.uppercase()))) else "guide"
                }
                "free" -> { ev(Ui.OpenFree); if (arg.isNotEmpty()) repeat((arg.toIntOrNull() ?: 1) - 1) { ev(Ui.AnotherDog) }; "free seed=${DogController.state.value.freeSeed}" }
                "settings" -> ev(Ui.OpenSettings)
                "back" -> ev(Ui.Back)
                "next" -> ev(Ui.Next)
                "again" -> ev(Ui.Restart)
                "choose" -> ev(Ui.Choose(arg.toInt()))
                "bring" -> ev(Ui.Bring)
                "quitAsk" -> ev(Ui.QuitAsk)
                "quitCancel" -> ev(Ui.QuitCancel)
                "quitYes" -> ev(Ui.QuitYes)
                "stare" -> { r?.stareOverride = when (arg) { "1" -> true; "0" -> false; else -> null }; "stare=${r?.stareOverride}" }
                "lean" -> { r?.leanOverride = null; scene?.headPushM = arg.toFloatOrNull() ?: 0f; "headPush=${scene?.headPushM}" }
                "yaw" -> { r?.yawOffsetDeg = arg.toFloatOrNull() ?: 0f; "yaw=${r?.yawOffsetDeg}" }
                "eye" -> { scene?.eyeForcedOff = arg == "off"; if (arg == "off") DogController.debugEye(EyeState.FORCED_OFF); "eyeOff=${scene?.eyeForcedOff}" }
                "dist" -> { scene?.teleportNose(arg.toFloat()); "nose=${scene?.mainNose()}" }
                "pressure" -> { DogController.scenario.forcePressure(arg.toFloat()); "P=${DogController.scenario.pressure}" }
                "dog" -> when (arg) {
                    "wary" -> { r?.stareOverride = true; DogController.scenario.forcePressure(4f); "macro wary" }
                    "relaxed" -> { r?.stareOverride = false; r?.leanOverride = 0f; scene?.headPushM = 0f; r?.yawOffsetDeg = 60f; "macro relaxed" }
                    "play" -> { r?.stareOverride = false; r?.leanOverride = 0f; scene?.headPushM = 0f; r?.yawOffsetDeg = 60f; DogController.scenario.forcePressure(0f); scene?.teleportNose(1.3f); "macro play" }
                    else -> "dog wary|relaxed|play"
                }
                "lang" -> ev(Ui.SetLang(when (arg) { "zh" -> Lang.ZH; "en" -> Lang.EN; else -> Lang.SYSTEM }))
                "sound" -> { if ((arg == "on") != DogController.state.value.soundOn) ev(Ui.ToggleSound); "sound=${DogController.state.value.soundOn}" }
                "panel" -> when (arg) {
                    "open" -> ev(Ui.OpenPanel)
                    "recall" -> { MainWindowStore.recall(); "recall state=${MainWindowStore.state}" }
                    else -> "window state=${MainWindowStore.state} open=${MainWindowStore.isOpen}"
                }
                "seated" -> ev(Ui.SetSeated(arg == "1"))
                "tag" -> { if ((arg == "on") != DogController.state.value.tagOn) ev(Ui.ToggleTag); "tag=${DogController.state.value.tagOn}" }
                "resetProgress" -> ev(Ui.ResetProgress)
                "view" -> { scene?.debugViewYawDeg = arg.toFloatOrNull() ?: 0f; ev(Ui.Bring); "view=${scene?.debugViewYawDeg}" }
                "light" -> scene?.debugLight(arg.substringBefore(' '), arg.substringAfter(' ', "")) ?: "no scene"
                "ftest" ->{ scene?.fTest = arg == "on"; "ftest=${scene?.fTest}" }
                "label" -> { DogController.debugLabel(if (arg == "off" || arg.isEmpty()) null else arg); "label=$arg" }
                "dump" -> DogController.state.value.let { "state=${it.designState} screen=${it.screen} lesson=${it.lesson} snap=${it.snap} eye=${it.eye} act=${DogController.scenario.act} P=${DogController.scenario.pressure}" }
                "where" -> scene?.where() ?: "no scene"
                "pose" -> scene?.poseLine() ?: "no scene"
                "panels" -> scene?.panelLines()?.onEach { DogLog.i(it) }?.size?.toString() ?: "no scene"
                "instances" -> scene?.logInstances()?.size?.toString() ?: "no scene"
                "bounds" -> bounds(arg.ifEmpty { "window" })
                else -> "unknown"
            }
        DogLog.i("CMD $cmd -> $result")
    }

    /** K7: is every probed control (e.g. quit_yes) fully inside its panel, so a ray can hit it? */
    private fun bounds(panel: String): String {
        val size = Probe.sizeOf(panel) ?: return "no panel $panel"
        val all = Probe.controlsOf(panel)
        var outside = 0
        for ((name, b) in all) {
            val inside = b.cx - b.w / 2 >= -0.5f && b.cy - b.h / 2 >= -0.5f && b.cx + b.w / 2 <= size.first + 0.5f && b.cy + b.h / 2 <= size.second + 0.5f
            if (!inside) outside++
            DogLog.i(String.format(java.util.Locale.US, "BOUNDS panel=%s name=%s cx=%.0f cy=%.0f w=%.0f h=%.0f panel=%dx%d inside=%s", panel, name, b.cx, b.cy, b.w, b.h, size.first, size.second, inside))
        }
        return "panel=$panel ${size.first}x${size.second} controls=${all.size} outside=$outside"
    }

    private fun ev(e: Ui): String {
        DogController.onEvent(e)
        return DogController.state.value.designState
    }

    private fun lesson(s: String): LessonId = LessonId.valueOf(s.uppercase().let { if (it.startsWith("L")) it else "L$it" })
}
