package io.embodit.dogmanners.ui

import io.embodit.dogmanners.domain.LessonId
import io.embodit.dogmanners.domain.Mood
import io.embodit.dogmanners.domain.Personality
import io.embodit.dogmanners.domain.Snapshot

/** Base screens (design states S1 - S18; S4 / S16 / S17 / S18 are modifiers on top of these). */
enum class Screen { STARTING, DISCLAIMER, HOME, INTRO, LESSON, GUIDE, FREE, SETTINGS }

enum class Lang { SYSTEM, ZH, EN }

/** Where the eyes stand (S4). */
enum class EyeState { ON, NO_PERMISSION, DENIED, UNSUPPORTED, FORCED_OFF, UNKNOWN }

data class DogUiState(
    val screen: Screen = Screen.STARTING,
    val lesson: LessonId? = null,
    val snap: Snapshot? = null,
    val guideMood: Mood = Mood.WARY,
    val freeSeed: Int = 1,
    val personality: Personality = Personality.NORMAL,
    val eye: EyeState = EyeState.UNKNOWN,
    val eyeAsked: Boolean = false,
    val lang: Lang = Lang.SYSTEM,
    val soundOn: Boolean = true,
    val birdsOn: Boolean = true,
    val seated: Boolean = false,
    val tagOn: Boolean = true,
    val completed: Set<Int> = emptySet(),
    val introsSeen: Set<Int> = emptySet(),
    val disclaimerSeen: Boolean = false,
    val quitConfirm: Boolean = false,
    val settingsFrom: Screen = Screen.HOME,
    /** Bumped to re-place the park in front of you (start, [回到面前], stand / sit). */
    val placeEpoch: Int = 0,
    /** Bumped to restart the current scene (new dog, new scenario). */
    val sceneEpoch: Int = 0,
    val backHint: Boolean = false,
    val resetDone: Boolean = false,
    val loading: Boolean = true,
    /** Debug: name override for screenshots. */
    val label: String? = null,
) {
    /** Design state id for the logs (`STATE S7 ...`). */
    val designState: String
        get() {
            val s = snap
            val base =
                when (screen) {
                    Screen.STARTING -> "S1"
                    Screen.DISCLAIMER -> "S3"
                    Screen.HOME -> "S2"
                    Screen.INTRO -> "S5"
                    Screen.GUIDE -> "S10"
                    Screen.FREE -> "S11"
                    Screen.SETTINGS -> "S12"
                    Screen.LESSON ->
                        when {
                            s == null -> "S6"
                            s.outcome == io.embodit.dogmanners.domain.Outcome.LEFT -> "S15"
                            s.outcome != null -> "S14"
                            s.phase == io.embodit.dogmanners.domain.Phase.CHOICE -> "S13"
                            s.act == io.embodit.dogmanners.domain.Act.LEAVE -> if (s.coach == io.embodit.dogmanners.domain.Coach.LEFT) "S15" else "S9"
                            s.act == io.embodit.dogmanners.domain.Act.WARY -> "S7"
                            s.act == io.embodit.dogmanners.domain.Act.PLAY -> "S9"
                            s.act == io.embodit.dogmanners.domain.Act.SNIFF || s.act == io.embodit.dogmanners.domain.Act.WAIT || s.act == io.embodit.dogmanners.domain.Act.SHAKE -> "S8"
                            else -> "S6"
                        }
                }
            val mods = buildList {
                if (eye != EyeState.ON && eye != EyeState.UNKNOWN) add("S4")
                if (s?.coach == io.embodit.dogmanners.domain.Coach.TOO_CLOSE) add("S16")
                if (!soundOn) add("S17")
                if (lang == Lang.EN) add("S18")
            }
            return (listOf(base) + mods).joinToString("+")
        }
}
