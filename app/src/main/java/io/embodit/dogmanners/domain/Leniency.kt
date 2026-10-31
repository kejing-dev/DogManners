package io.embodit.dogmanners.domain

/** What was a little off in a near miss (D27: the hint says where). */
enum class NearCause { FACE, LEAN }

/** One near miss as the panels show it: what was off, and whether the judging just got easier. */
data class NearMiss(val cause: NearCause, val loosened: Boolean, val level: Int)

/**
 * D27 lenient judging ("先让用户成功"), one per lesson and kept across [再玩一次] by the controller.
 *
 * - Level 0 already counts a half-side face (>= [CALM_SIDE_DEG] = 25°) as side-on for calming the dog and for the
 *   "turned sideways" goal; the design's 40° stays the teaching target (P1 sits at 38°, so looking at the panel counts).
 * - A try that is close but not there (face 10°–25°, or a lean up to 0.15 m past the 0.25 m line) is a near miss and
 *   the coach says what was off. Two near misses in a row loosen one level (max 2): every judged face angle gets
 *   +6° per level and every lean -0.07 m per level, the "bad for N s" dwell gets x1.5 per level.
 * - A clear miss (a real stare, a big lean) breaks the row; doing it right starts the row again (the level stays).
 *
 * [judge] turns the raw [BodyInput] into the judged one; every decision in [Scenario] / [DogBrain] reads the judged one.
 */
class Leniency {
    var level = 0
        private set
    private var nearInRow = 0
    var last: NearMiss? = null
        private set

    val bonusDeg: Float
        get() = level * BONUS_DEG
    val leanSlackM: Float
        get() = level * LEAN_SLACK_M
    val dwellScale: Float
        get() = 1f + 0.5f * level

    fun judge(input: BodyInput): BodyInput =
        if (level == 0) input
        else input.copy(facingDeg = (input.facingDeg + bonusDeg).coerceAtMost(180f), leanM = input.leanM - leanSlackM)

    /**
     * Is this (judged) input a near miss, and of what? [okFromDeg] is the face angle that would already be fine in the
     * current branch (calming in a wait: 25°; the pressure model: just past face-on, 20°). null = fine or clearly off.
     */
    fun nearCause(j: BodyInput, okFromDeg: Float, leanCounts: Boolean = true): NearCause? =
        when {
            j.staring -> null
            leanCounts && j.leanM >= Pressure.LEAN_M -> if (j.leanM < Pressure.LEAN_M + NEAR_LEAN_M) NearCause.LEAN else null
            j.facingDeg >= NEAR_FACE_FROM_DEG && j.facingDeg < okFromDeg -> NearCause.FACE
            else -> null
        }

    fun nearMiss(cause: NearCause): NearMiss {
        nearInRow++
        val loosen = nearInRow >= 2 && level < MAX_LEVEL
        if (loosen) {
            level++
            nearInRow = 0
        }
        return NearMiss(cause, loosen, level).also { last = it }
    }

    fun clearMiss() {
        nearInRow = 0
    }

    fun success() {
        nearInRow = 0
    }

    companion object {
        /** Half-side counts as side-on (D27; the design's 20°–40° "半侧" band starts at 20°, face-on ends there). */
        const val CALM_SIDE_DEG = 25f
        const val NEAR_FACE_FROM_DEG = 10f
        const val NEAR_LEAN_M = 0.15f
        const val BONUS_DEG = 6f
        const val LEAN_SLACK_M = 0.07f
        const val MAX_LEVEL = 2

        /** One "try" while the dog is wary: this long close-but-not-there counts as one near miss. */
        const val NEAR_TRY_S = 1.5f

        /** D27: holding the right pose this long is enough. */
        const val HOLD_S = 0.5f

        /** The near-miss hint stays on the panel this long. */
        const val HINT_S = 4f
    }
}
