package io.embodit.dogmanners.domain

import kotlin.math.max
import kotlin.math.min

/** The three dog states the user is taught (design dogBehavior.states). */
enum class Mood { WARY, RELAXED, PLAY }

/** What the dog is doing (drives the clip, the walk target and the coach line). */
enum class Act {
    /** S2: sitting by the path, looking around. */
    SIT,
    /** S6: walking up the path towards you (shown as relaxed). */
    APPROACH,
    /** S8: relaxed, waiting at 1.3 m. */
    WAIT,
    /** S8: stepping in to 1.0 m and sniffing you. */
    SNIFF,
    /** S9: play bow at 1.3 m. */
    PLAY,
    /** S7: wary, frozen at 2.0 m or backing off to it. */
    WARY,
    /** wary -> relaxed transition: fur shake. */
    SHAKE,
    /** S15 / L1 success: walking away down the path. */
    LEAVE,
    /** L3: eating at the bowl. */
    EAT,
    /** L4: asleep on the mat. */
    SLEEP,
    /** L2: held on the leash next to the owner. */
    HELD,
    /** S10: standing for the cheat sheet, looping one mood. */
    SHOW,
}

/**
 * What the dog reads from you this frame (design dogBehavior.inputsAsTeachingSignals). Teaching inputs only: none of
 * these ever selects or places anything (D1).
 */
data class BodyInput(
    /** Angle between your horizontal face direction and the direction to the dog, degrees. */
    val facingDeg: Float,
    /** You are staring at its face (eyes, or the head fallback after its longer dwell). */
    val staring: Boolean,
    /** The stare comes from the eyes (+1.2/s) rather than the head fallback (+0.8/s). */
    val stareByEyes: Boolean,
    /** Horizontal lean of the neck pivot towards the dog, metres from the baseline. */
    val leanM: Float,
    /** A sudden head movement started this frame. */
    val sudden: Boolean,
    /** Head linear < 0.10 m/s and angular < 25°/s. */
    val still: Boolean,
) {
    val faceOn: Boolean
        get() = facingDeg <= FACE_ON_DEG

    val sideOn: Boolean
        get() = facingDeg >= SIDE_ON_DEG

    companion object {
        const val FACE_ON_DEG = 20f
        const val SIDE_ON_DEG = 40f
        val CALM = BodyInput(90f, staring = false, stareByEyes = false, leanM = 0f, sudden = false, still = true)
    }
}

/**
 * Pressure model (design dogBehavior.pressureModel): P in [0, 4]. Stare +1.2/s (head fallback +0.8/s), lean +1.0/s
 * while the nose is within 1.5 m, a sudden move +1.5 once; face-on alone +0.25/s but only up to 2.5; otherwise, while
 * still, -0.8/s; not still (fidgeting, but no stare / lean / face-on) still calms at half that rate, so P never sticks
 * between the thresholds with nothing left to end it (D27: every branch must be able to finish). [gain] scales the
 * rises (L5 is touchier, the free mode's shy / outgoing dogs).
 */
class Pressure(var gain: Float = 1f, var faceOnlyCap: Float = FACE_ONLY_CAP, var faceRate: Float = FACE_RATE) {
    var value = 0f
        private set

    fun reset(v: Float = 0f) {
        value = v.coerceIn(0f, MAX)
    }

    fun step(dt: Float, input: BodyInput, noseM: Float): Float {
        var up = 0f
        if (input.staring) up += if (input.stareByEyes) STARE_EYES else STARE_HEAD
        if (input.leanM >= LEAN_M && noseM <= LEAN_RANGE_M) up += LEAN
        if (input.sudden) value = min(MAX, value + SUDDEN * gain)
        value =
            when {
                up > 0f -> min(MAX, value + up * gain * dt)
                input.faceOn -> min(max(value, faceOnlyCap), value + faceRate * gain * dt)
                input.still -> max(0f, value - CALM * dt)
                else -> max(0f, value - CALM * FIDGET_CALM * dt)
            }
        return value
    }

    companion object {
        const val MAX = 4f
        const val STARE_EYES = 1.2f
        const val STARE_HEAD = 0.8f
        const val LEAN = 1.0f
        const val LEAN_M = 0.25f
        const val LEAN_RANGE_M = 1.5f
        const val SUDDEN = 1.5f
        const val FACE_RATE = 0.25f
        const val FACE_ONLY_CAP = 2.5f
        const val CALM = 0.8f
        const val FIDGET_CALM = 0.5f
        const val WARY_AT = 3f
        const val EASING_BELOW = 2.5f
        const val CALM_AT = 1f
        const val PLAY_BREAK = 2f
    }
}

/** Distances, nose tip to your head, horizontal (design dogBehavior.stops). */
object Stops {
    const val START_M = 6.0f
    const val WAIT_M = 1.3f
    const val SNIFF_M = 1.0f
    const val WARY_M = 2.0f
    const val TOO_CLOSE_M = 0.97f
    const val HARD_MIN_M = 0.8f
    const val LEAVE_M = 8.0f
    const val WALK_MPS = 0.5f
    const val BACK_MPS = 0.8f
}

/** Something the lesson / panel layer wants to know about (one-shot). */
enum class DogEvent { BECAME_WARY, CALMED, SNIFF_DONE, PLAY_BOW, LEFT_UPSET, LEFT_HAPPY, TOO_CLOSE, ARRIVED }

/**
 * The dog's behaviour for the body-language scenes (L1, L2 after the right answer, free mode): a pure state machine fed
 * once per frame with [BodyInput] and the current nose distance. It says where the nose should go ([targetM], null =
 * stand still where it is) and which clip plays ([act]). The scene moves the dog; the distance it reports back closes
 * the loop. Debug hooks go through [force] (same transitions as real input, no shortcut that skips the events).
 */
class DogBrain(val pressure: Pressure = Pressure()) {
    var act: Act = Act.APPROACH
        private set
    var mood: Mood = Mood.RELAXED
        private set
    var targetM: Float? = Stops.WAIT_M
        private set

    /** Seconds the dog has been at P >= 3 (the leave timer; cleared below 3). */
    var leaveTimer = 0f
        private set
    var sniffDone = false
        private set
    var leanEver = false
        private set
    private var calmHold = 0f
    private var waryHold = 0f
    private var playHold = 0f
    private var breakHold = 0f
    private var actTime = 0f
    private var sniffTime = 0f
    var minNoseM = Float.MAX_VALUE
        private set

    /** L1 ends with the dog leaving on its own after the play bow; elsewhere the bow just goes on. */
    var leaveAfterPlay = true

    /** After the sniff the dog asks to play (L1, free mode); L2 ends at the sniff. */
    var playAfterSniff = true

    val events = ArrayList<DogEvent>()

    fun start(fromM: Float = Stops.START_M) {
        act = Act.APPROACH
        mood = Mood.RELAXED
        targetM = Stops.WAIT_M
        pressure.reset()
        leaveTimer = 0f
        sniffDone = false
        leanEver = false
        calmHold = 0f
        waryHold = 0f
        playHold = 0f
        breakHold = 0f
        actTime = 0f
        sniffTime = 0f
        minNoseM = fromM
        events.clear()
    }

    private fun go(next: Act) {
        if (next == act) return
        act = next
        actTime = 0f
        mood =
            when (next) {
                Act.WARY -> Mood.WARY
                Act.PLAY -> Mood.PLAY
                else -> Mood.RELAXED
            }
        targetM =
            when (next) {
                Act.APPROACH, Act.WAIT, Act.PLAY -> Stops.WAIT_M
                Act.SNIFF -> Stops.SNIFF_M
                Act.LEAVE -> Stops.LEAVE_M
                else -> null
            }
    }

    /**
     * One frame. [noseM] = current nose-to-head horizontal distance. Returns the pressure.
     */
    fun step(dt: Float, input: BodyInput, noseM: Float): Float {
        events.clear()
        actTime += dt
        minNoseM = min(minNoseM, noseM)
        if (input.leanM >= Pressure.LEAN_M && noseM <= Pressure.LEAN_RANGE_M) leanEver = true
        if (act == Act.LEAVE) return pressure.value
        val p = pressure.step(dt, input, noseM)

        // the leave timer only runs at P >= 3 (S15)
        if (p >= Pressure.WARY_AT) leaveTimer += dt else leaveTimer = 0f

        // too close: back off to 1.3 m, whatever the state (S16); the scene also clamps 0.8 m every frame
        if (noseM < Stops.TOO_CLOSE_M && act != Act.SNIFF) {
            events += DogEvent.TOO_CLOSE
            if (act == Act.WAIT || act == Act.PLAY || act == Act.APPROACH) targetM = Stops.WAIT_M
        }
        if (act == Act.SNIFF && noseM < Stops.SNIFF_M - 0.06f) {
            events += DogEvent.TOO_CLOSE
            go(Act.WAIT)
        }

        when (act) {
            Act.APPROACH, Act.WAIT, Act.SNIFF, Act.PLAY -> {
                waryHold = if (p >= Pressure.WARY_AT) waryHold + dt else 0f
                if (waryHold >= HYSTERESIS_S) {
                    go(Act.WARY)
                    calmHold = 0f
                    events += DogEvent.BECAME_WARY
                    // wary: stop where it is beyond 2.0 m, back off to 2.0 m inside it
                    targetM = if (noseM < Stops.WARY_M) Stops.WARY_M else null
                    return p
                }
            }
            else -> Unit
        }

        when (act) {
            Act.APPROACH -> if (noseM <= Stops.WAIT_M + 0.03f) {
                events += DogEvent.ARRIVED
                go(Act.WAIT)
            }
            Act.WAIT -> {
                // D27: face-on alone (no stare, no lean) holds P at its 2.5 cap; it must not block the sniff forever
                val faceOnly = !input.staring && input.leanM < Pressure.LEAN_M && p <= pressure.faceOnlyCap + 1e-3f
                if (!sniffDone && actTime >= WAIT_BEFORE_SNIFF_S && (p < Pressure.EASING_BELOW || faceOnly)) go(Act.SNIFF)
                else if (sniffDone && playAfterSniff && p < Pressure.PLAY_BREAK) go(Act.PLAY).also { events += DogEvent.PLAY_BOW }
            }
            Act.SNIFF -> {
                if (noseM <= Stops.SNIFF_M + 0.04f) {
                    sniffTime += if (input.still && input.leanM < Pressure.LEAN_M) dt else -dt * SNIFF_DECAY
                    sniffTime = sniffTime.coerceAtLeast(0f)
                    if (sniffTime >= SNIFF_S) {
                        sniffDone = true
                        events += DogEvent.SNIFF_DONE
                        if (playAfterSniff) {
                            go(Act.PLAY)
                            events += DogEvent.PLAY_BOW
                        } else {
                            go(Act.WAIT)
                        }
                    }
                }
            }
            Act.PLAY -> {
                breakHold = if (p >= Pressure.PLAY_BREAK) breakHold + dt else 0f
                if (breakHold >= HYSTERESIS_S) {
                    go(Act.WAIT)
                } else {
                    playHold += dt
                    if (leaveAfterPlay && playHold >= PLAY_S) {
                        go(Act.LEAVE)
                        events += DogEvent.LEFT_HAPPY
                    }
                }
            }
            Act.WARY -> {
                if (targetM == null && noseM < Stops.WARY_M) targetM = Stops.WARY_M
                if (targetM != null && noseM >= Stops.WARY_M - 0.02f) targetM = null
                calmHold = if (p <= Pressure.CALM_AT) calmHold + dt else 0f
                if (calmHold >= CALM_HOLD_S) {
                    go(Act.SHAKE)
                    events += DogEvent.CALMED
                } else if (leaveTimer >= LEAVE_AFTER_S) {
                    go(Act.LEAVE)
                    events += DogEvent.LEFT_UPSET
                }
            }
            Act.SHAKE -> if (actTime >= SHAKE_S) go(if (sniffDone) Act.WAIT else Act.APPROACH).also { targetM = Stops.WAIT_M }
            else -> Unit
        }
        return p
    }

    /**
     * The scene's time is up (D27 "结束即判" in every branch): the dog walks off. Happy only after the sniff and when it
     * is not wary; the scenario turns the event into the result like any other leave.
     */
    fun timeUp() {
        if (act == Act.LEAVE) return
        val happy = sniffDone && act != Act.WARY
        go(Act.LEAVE)
        events += if (happy) DogEvent.LEFT_HAPPY else DogEvent.LEFT_UPSET
    }

    /** Debug: jump the pressure so the normal transitions run (no direct state writes). */
    fun forcePressure(v: Float) = pressure.reset(v)

    companion object {
        const val HYSTERESIS_S = 0.6f
        const val CALM_HOLD_S = 2f
        const val LEAVE_AFTER_S = 10f
        const val SNIFF_S = 2.5f
        const val SNIFF_DECAY = 0.5f
        const val PLAY_S = 3f
        const val SHAKE_S = 1.2f
        const val WAIT_BEFORE_SNIFF_S = 1.0f
    }
}
