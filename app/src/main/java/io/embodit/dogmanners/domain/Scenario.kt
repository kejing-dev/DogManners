package io.embodit.dogmanners.domain

import kotlin.math.max

/** The six scenes (design content.lessons). [n] is 1-based. */
enum class LessonId(val n: Int, val lineup: Lineup, val choices: Int, val correct: Int) {
    L1(1, Lineup.MEDIUM, 0, -1),
    L2(2, Lineup.SMALL, 3, 1),
    L3(3, Lineup.MEDIUM, 2, 1),
    L4(4, Lineup.LARGE, 3, 2),
    L5(5, Lineup.MEDIUM, 2, 1),
    L6(6, Lineup.LARGE, 2, 1);

    val next: LessonId?
        get() = entries.getOrNull(ordinal + 1)
}

enum class Prop { OWNER, BOWL, MAT, FENCE, PUP }

enum class Phase { RUN, CHOICE, REACT, RESULT }

enum class Outcome { OK, BAD, LEFT }

enum class Coach {
    NONE, APPROACH, WARY, RELAXED, SNIFF, PLAY, TOO_CLOSE, LEFT, LEFT_WELL,
    OWNER_OK, OWNER_HELD, L2_RUSH, L2_RUN,
    EATING, L3_WAIT, L3_WARY, L3_PAT,
    SLEEPING, L4_WOKE, L4_SLEEP,
    MOM, L5_WAIT, L5_WARY, L5_CLOSE,
    FENCE, L6_REACH, L6_WAIT,
    FREE, GUIDE, HOME,
}

enum class ResultText { L1_OK, L1_LEFT, L2_OK, L2_RUSH, L2_RUN, L3_OK, L3_PAT, L3_STARE, L4_OK, L4_BAD, L5_OK, L5_BAD, L6_OK, L6_BAD }

enum class Goal { NO_STARE, SIDE, STILL, NO_LEAN, ASKED, WAITED, CHOICE }

enum class Personality(val gain: Float) { SHY(1f / 0.7f), NORMAL(1f), OUTGOING(1f / 1.3f) }

/** What kind of scene runs. */
sealed interface Mode {
    data object Home : Mode
    data class Lesson(val id: LessonId) : Mode
    data class Guide(val mood: Mood) : Mode
    data class Free(val seed: Int) : Mode
}

/** One dog in the scene: where it starts (nose distance, sideways offset) and how big it is. */
data class DogRole(val lineup: Lineup, val startNoseM: Float, val offsetX: Float, val yawDeg: Float)

/** What the panels show (changes a few times per lesson; the scene keeps its per-frame data to itself). */
data class Snapshot(
    val phase: Phase,
    val mood: Mood,
    val act: Act,
    val coach: Coach,
    val goals: List<Pair<Goal, Boolean>>,
    val outcome: Outcome?,
    val result: ResultText?,
    val showScared: Boolean,
    val waitLeftS: Int?,
    val hint: Boolean,
    /** D27: the last near miss while it is still worth showing ("差一点：…"), null otherwise. */
    val near: NearMiss? = null,
)

/**
 * One scene's flow (design content.lessons[], states S5 - S16): the choice, the dog's reaction, the waits and the result.
 * Pure Kotlin, stepped once per frame by the scene with the body input and the dog's nose distance; the scene reads
 * [act], [mood], [targetM], [speed] to move and animate the dog. Debug hooks call [choose] / [forcePressure] like
 * real input would.
 */
class Scenario(val mode: Mode, val personality: Personality = Personality.NORMAL, val leniency: Leniency = Leniency()) {
    val brain = DogBrain(Pressure(gain = personality.gain))
    var phase: Phase = Phase.RUN
        private set
    var act: Act = Act.SIT
        private set
    var mood: Mood = Mood.RELAXED
        private set
    var coach: Coach = Coach.NONE
        private set
    var targetM: Float? = null
        private set
    var speed: Float = Stops.WALK_MPS
        private set
    var outcome: Outcome? = null
        private set
    var result: ResultText? = null
        private set
    var choice: Int = -1
        private set

    /** Mom steps in front of her pup (L5): sideways offset of the main dog, metres. */
    var momShift = 0f
        private set

    /** While the intro is read the scene is set but nothing moves (S5). */
    var freeze = false

    private var usesBrain = false
    private var t = 0f
    private var phaseT = 0f
    private var waitT = 0f
    private var badDwell = 0f
    private var calmDwell = 0f
    private var waryEpisodes = 0
    private var resultDelay = -1f
    private var pendingOutcome: Outcome? = null
    private var pendingResult: ResultText? = null
    private var hint = false
    private var lastStareT = -100f
    private var sideHold = 0f
    private var sideEarned = false
    private var lastPressure = 0f
    private var brainT = 0f
    private var nearT = 0f
    private var nearShowT = 0f
    private var shownNear: NearMiss? = null
    private var lastFacing = Float.NaN
    private var turningAway = false

    /** The judged input of the last frame (after [Leniency.judge]); diagnostics and tests. */
    var judged: BodyInput = BodyInput.CALM
        private set

    val pressure: Float
        get() = if (usesBrain) brain.pressure.value else lastPressure

    val lesson: LessonId?
        get() = (mode as? Mode.Lesson)?.id

    val roles: List<DogRole> =
        when (mode) {
            Mode.Home -> listOf(DogRole(Lineup.MEDIUM, 3.0f, 0.55f, 70f))
            is Mode.Guide -> listOf(DogRole(Lineup.MEDIUM, 1.5f, 0f, 45f))
            is Mode.Free -> listOf(DogRole(Lineup.entries[Math.floorMod(mode.seed * 7 + 3, 3).let { if (it == 1) 1 else if (it == 0) 0 else 2 }], Stops.START_M, 0f, 0f))
            is Mode.Lesson ->
                when (mode.id) {
                    LessonId.L1 -> listOf(DogRole(Lineup.MEDIUM, Stops.START_M, 0f, 0f))
                    LessonId.L2 -> listOf(DogRole(Lineup.SMALL, 2.3f, 0.15f, 0f))
                    LessonId.L3 -> listOf(DogRole(Lineup.MEDIUM, 2.5f, 0f, 90f))
                    LessonId.L4 -> listOf(DogRole(Lineup.LARGE, 2.2f, 0f, 0f))
                    LessonId.L5 -> listOf(DogRole(Lineup.MEDIUM, 2.8f, 0f, 0f), DogRole(Lineup.PUPPY, 3.0f, 0.45f, -25f))
                    LessonId.L6 -> listOf(DogRole(Lineup.LARGE, 1.6f, 0f, 0f))
                }
        }

    val props: Set<Prop> =
        when (lesson) {
            LessonId.L2 -> setOf(Prop.OWNER)
            LessonId.L3 -> setOf(Prop.BOWL)
            LessonId.L4 -> setOf(Prop.MAT)
            LessonId.L5 -> setOf(Prop.PUP)
            LessonId.L6 -> setOf(Prop.FENCE)
            else -> emptySet()
        }

    init {
        when (mode) {
            Mode.Home -> setAct(Act.SIT, Mood.RELAXED, null, Coach.HOME)
            is Mode.Guide -> setAct(Act.SHOW, mode.mood, null, Coach.GUIDE)
            is Mode.Free -> startBrain(Coach.FREE, leaveAfterPlay = true)
            is Mode.Lesson ->
                when (mode.id) {
                    LessonId.L1 -> startBrain(Coach.APPROACH, leaveAfterPlay = true)
                    LessonId.L2 -> choiceAt(Act.HELD, Coach.OWNER_HELD)
                    LessonId.L3 -> choiceAt(Act.EAT, Coach.EATING)
                    LessonId.L4 -> choiceAt(Act.SLEEP, Coach.SLEEPING)
                    LessonId.L5 -> choiceAt(Act.WAIT, Coach.MOM)
                    LessonId.L6 -> choiceAt(Act.WAIT, Coach.FENCE)
                }
        }
    }

    private fun setAct(a: Act, m: Mood, target: Float?, c: Coach? = null) {
        act = a
        mood = m
        targetM = target
        if (c != null) coach = c
    }

    private fun startBrain(c: Coach, leaveAfterPlay: Boolean, playAfterSniff: Boolean = true, fromM: Float = Stops.START_M) {
        usesBrain = true
        phase = Phase.RUN
        phaseT = 0f
        brain.leaveAfterPlay = leaveAfterPlay
        brain.playAfterSniff = playAfterSniff
        brain.start(fromM)
        brainT = 0f
        coach = c
        syncBrain()
    }

    private fun choiceAt(a: Act, c: Coach) {
        usesBrain = false
        phase = Phase.CHOICE
        phaseT = 0f
        setAct(a, if (a == Act.WARY) Mood.WARY else Mood.RELAXED, null, c)
    }

    private fun syncBrain() {
        act = brain.act
        mood = brain.mood
        targetM = brain.targetM
        speed = if (act == Act.WARY || act == Act.WAIT && brain.targetM != null) Stops.BACK_MPS else Stops.WALK_MPS
    }

    /** A choice card answer (P3), 0-based. Ignored outside the choice phase. */
    fun choose(i: Int) {
        val id = lesson ?: return
        if (phase != Phase.CHOICE || i !in 0 until id.choices) return
        choice = i
        phase = Phase.REACT
        phaseT = 0f
        val right = i == id.correct
        when (id) {
            LessonId.L2 ->
                when (i) {
                    1 -> coach = Coach.OWNER_OK
                    0 -> { setAct(Act.WARY, Mood.WARY, 2.6f, Coach.L2_RUSH); finishAfter(3f, Outcome.BAD, ResultText.L2_RUSH) }
                    else -> { setAct(Act.APPROACH, Mood.PLAY, 1.6f, Coach.L2_RUN); speed = 1.5f; finishAfter(3.5f, Outcome.BAD, ResultText.L2_RUN) }
                }
            LessonId.L3 ->
                if (right) { coach = Coach.L3_WAIT; waitT = 0f } else { setAct(Act.WARY, Mood.WARY, null, Coach.L3_PAT); finishAfter(3f, Outcome.BAD, ResultText.L3_PAT) }
            LessonId.L4 ->
                if (right) { coach = Coach.L4_SLEEP; finishAfter(2.5f, Outcome.OK, ResultText.L4_OK) } else { setAct(Act.WARY, Mood.WARY, null, Coach.L4_WOKE); finishAfter(3f, Outcome.BAD, ResultText.L4_BAD) }
            LessonId.L5 ->
                if (right) { coach = Coach.L5_WAIT; waitT = 0f } else { setAct(Act.WARY, Mood.WARY, null, Coach.L5_CLOSE); momShift = 0.35f; finishAfter(3f, Outcome.BAD, ResultText.L5_BAD) }
            LessonId.L6 ->
                if (right) { coach = Coach.L6_WAIT; finishAfter(2.5f, Outcome.OK, ResultText.L6_OK) } else { setAct(Act.WARY, Mood.WARY, 1.3f, Coach.L6_REACH); speed = 1.6f; finishAfter(3f, Outcome.BAD, ResultText.L6_BAD) }
            LessonId.L1 -> Unit
        }
    }

    private fun finishAfter(s: Float, o: Outcome, r: ResultText) {
        if (pendingOutcome != null || outcome != null) return
        resultDelay = s
        pendingOutcome = o
        pendingResult = r
    }

    /** Debug `pressure V`: the brain then runs its own transitions. */
    fun forcePressure(v: Float) {
        if (usesBrain) brain.forcePressure(v) else lastPressure = v
    }

    /** One frame. Returns the snapshot (compare to decide whether the panels change). */
    fun step(dt: Float, raw: BodyInput, noseM: Float): Snapshot {
        if (freeze) return snapshot()
        t += dt
        phaseT += dt
        val input = leniency.judge(raw)
        judged = input
        // turning your face away from the dog (> 4°/s) is trying: a slow turn must not count against you (D27 "慢一倍")
        turningAway = !lastFacing.isNaN() && dt > 1e-4f && (input.facingDeg - lastFacing) / dt > TURN_AWAY_DEG_S
        lastFacing = input.facingDeg
        if (input.staring) lastStareT = t
        sideHold = if (input.facingDeg >= Leniency.CALM_SIDE_DEG && !input.staring) sideHold + dt else 0f
        if (sideHold >= Leniency.HOLD_S) sideEarned = true
        if (nearShowT > 0f) nearShowT -= dt

        if (resultDelay >= 0f) {
            resultDelay -= dt
            if (resultDelay < 0f) {
                outcome = pendingOutcome
                result = pendingResult
                phase = Phase.RESULT
            }
        }
        if (phase == Phase.CHOICE && phaseT >= CHOICE_HINT_S && lesson == LessonId.L2) hint = true

        if (tooCloseT > 0f) tooCloseT -= dt
        if (usesBrain) {
            brainT += dt
            brain.step(dt, input, noseM)
            if (brain.act == Act.WARY && pendingOutcome == null) {
                // the pressure model holds P while you are face-on (<= 20°): that is the "close" band here
                tryNear(dt, leniency.nearCause(input, BodyInput.FACE_ON_DEG + 0.01f, leanCounts = noseM <= Pressure.LEAN_RANGE_M))
            } else {
                nearT = 0f
            }
            val cap = brainCapS()
            if (cap != null && brainT >= cap && pendingOutcome == null && outcome == null) brain.timeUp()
            syncBrain()
            if (outcome == null && pendingOutcome == null) coach = brainCoach()
            for (e in brain.events) onBrainEvent(e)
        } else if (phase == Phase.REACT) {
            stepWait(dt, input)
        }
        return snapshot()
    }

    private fun brainCoach(): Coach {
        if (brain.events.contains(DogEvent.TOO_CLOSE)) tooCloseT = TOO_CLOSE_SHOW_S
        if (tooCloseT > 0f) return Coach.TOO_CLOSE
        return when (brain.act) {
            Act.APPROACH -> if (mode is Mode.Free) Coach.FREE else if (lesson == LessonId.L2) Coach.OWNER_OK else Coach.APPROACH
            Act.WAIT -> Coach.RELAXED
            Act.SNIFF -> Coach.SNIFF
            Act.PLAY -> Coach.PLAY
            Act.WARY -> Coach.WARY
            Act.SHAKE -> Coach.RELAXED
            Act.LEAVE -> if (brain.sniffDone) Coach.LEFT_WELL else Coach.LEFT
            else -> coach
        }
    }

    private var tooCloseT = 0f

    /** Lessons run by the brain end on their own at the latest after this (D27: every branch must finish). */
    private fun brainCapS(): Float? =
        when (lesson) {
            LessonId.L1 -> L1_CAP_S
            LessonId.L2 -> L2_CAP_S
            else -> null
        }

    /** One frame of "close but not there" while the dog is wary; [NEAR_TRY_S] of it is one near miss. */
    private fun tryNear(dt: Float, cause: NearCause?) {
        if (cause == null) {
            nearT = 0f
            return
        }
        nearT += dt
        if (nearT >= Leniency.NEAR_TRY_S) {
            nearT = 0f
            showNear(leniency.nearMiss(cause))
        }
    }

    private fun showNear(n: NearMiss) {
        shownNear = n
        nearShowT = Leniency.HINT_S
    }

    private fun onBrainEvent(e: DogEvent) {
        when (e) {
            DogEvent.CALMED -> leniency.success()
            DogEvent.LEFT_HAPPY -> if (mode is Mode.Lesson) finishAfter(2.5f, Outcome.OK, if (lesson == LessonId.L2) ResultText.L2_OK else ResultText.L1_OK)
            DogEvent.LEFT_UPSET -> if (mode is Mode.Lesson) finishAfter(2.5f, Outcome.LEFT, ResultText.L1_LEFT)
            DogEvent.SNIFF_DONE -> if (lesson == LessonId.L2) finishAfter(1.5f, Outcome.OK, ResultText.L2_OK)
            else -> Unit
        }
    }

    /** L2 after the right answer, L3 / L5 waits. */
    private fun stepWait(dt: Float, input: BodyInput) {
        when (lesson) {
            LessonId.L2 -> if (choice == 1 && phaseT >= OWNER_LINE_S && !usesBrain) {
                startBrain(Coach.OWNER_OK, leaveAfterPlay = false, playAfterSniff = false, fromM = 2.3f)
                brain.pressure.reset(0f)
                phase = Phase.REACT
            }
            LessonId.L3, LessonId.L5 -> {
                if (choice != (lesson?.correct ?: -1) || pendingOutcome != null) return
                val l5 = lesson == LessonId.L5
                val dwellNeed = (if (l5) 1f else 2f) * leniency.dwellScale
                val total = if (l5) L5_WAIT_S else L3_WAIT_S
                waitT += dt
                if (act != Act.WARY) {
                    nearT = 0f
                    val bad = input.staring || input.faceOn || input.leanM >= Pressure.LEAN_M
                    // turning away counts as trying: the dwell holds instead of growing (a slow turn is not a miss)
                    badDwell = if (!bad) 0f else if (turningAway) badDwell else badDwell + dt
                    lastPressure = (badDwell / dwellNeed * Pressure.WARY_AT).coerceAtMost(Pressure.WARY_AT)
                    if (badDwell >= dwellNeed) {
                        waryEpisodes++
                        lastPressure = Pressure.WARY_AT
                        setAct(Act.WARY, Mood.WARY, null, if (l5) Coach.L5_WARY else Coach.L3_WARY)
                        if (l5) momShift = 0.35f
                        calmDwell = 0f
                        // what made it wary: close-but-not-there counts towards loosening, a real stare does not
                        val cause = leniency.nearCause(input, BodyInput.FACE_ON_DEG + 0.01f)
                        if (cause != null) showNear(leniency.nearMiss(cause)) else leniency.clearMiss()
                        if (l5 || waryEpisodes >= 2) finishAfter(2.5f, Outcome.BAD, if (l5) ResultText.L5_BAD else ResultText.L3_STARE)
                    }
                } else {
                    // calming: no stare, no lean, face at least half-side (25°), held 0.5 s (D27)
                    val calm = !input.staring && input.facingDeg >= Leniency.CALM_SIDE_DEG && input.leanM < Pressure.LEAN_M
                    calmDwell = if (calm) calmDwell + dt else 0f
                    if (calm) nearT = 0f else tryNear(dt, leniency.nearCause(input, Leniency.CALM_SIDE_DEG))
                    if (calmDwell >= Leniency.HOLD_S && pendingOutcome == null) {
                        lastPressure = 0f
                        badDwell = 0f
                        leniency.success()
                        setAct(if (l5) Act.WAIT else Act.EAT, Mood.RELAXED, null, if (l5) Coach.L5_WAIT else Coach.L3_WAIT)
                    }
                }
                // "15 s 结束即判" (L5 12 s) in EVERY branch: still wary at the end = not quite, otherwise it worked
                if (waitT >= total && pendingOutcome == null) {
                    if (act == Act.WARY) finishAfter(0.5f, Outcome.BAD, if (l5) ResultText.L5_BAD else ResultText.L3_STARE)
                    else finishAfter(0.5f, Outcome.OK, if (l5) ResultText.L5_OK else ResultText.L3_OK)
                }
            }
            else -> Unit
        }
    }

    fun goals(): List<Pair<Goal, Boolean>> {
        val noStare = t - lastStareT > NO_STARE_S
        return when {
            mode is Mode.Free || lesson == LessonId.L1 -> listOf(Goal.NO_STARE to noStare, Goal.SIDE to sideEarned, Goal.STILL to brain.sniffDone, Goal.NO_LEAN to !brain.leanEver)
            lesson == LessonId.L2 -> listOf(Goal.ASKED to (choice == 1), Goal.STILL to brain.sniffDone, Goal.NO_LEAN to !brain.leanEver)
            lesson == LessonId.L3 || lesson == LessonId.L5 -> listOf(Goal.CHOICE to (choice == lesson?.correct), Goal.NO_STARE to (choice >= 0 && waryEpisodes == 0), Goal.WAITED to (outcome == Outcome.OK))
            lesson != null -> listOf(Goal.CHOICE to (choice == lesson?.correct))
            else -> emptyList()
        }
    }

    fun snapshot(): Snapshot {
        val wait =
            if (phase == Phase.REACT && pendingOutcome == null && (lesson == LessonId.L3 || lesson == LessonId.L5) && choice == lesson?.correct)
                max(0, ((if (lesson == LessonId.L5) L5_WAIT_S else L3_WAIT_S) - waitT).toInt() + 1)
            else null
        val near = if (nearShowT > 0f && outcome == null) shownNear else null
        return Snapshot(phase, mood, act, coach, goals(), outcome, result, outcome == Outcome.LEFT, wait, hint, near)
    }

    companion object {
        const val CHOICE_HINT_S = 60f
        const val OWNER_LINE_S = 2.5f
        const val L3_WAIT_S = 15f
        const val L5_WAIT_S = 12f
        const val NO_STARE_S = 3f
        const val TOO_CLOSE_SHOW_S = 2.5f
        const val L1_CAP_S = 60f
        const val L2_CAP_S = 45f
        const val TURN_AWAY_DEG_S = 4f
    }
}
