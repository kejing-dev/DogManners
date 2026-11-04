package io.embodit.dogmanners.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * v0.1.3 (profile D9, native-window-move.md §7 item 9): a system window opens tilted like your head and code cannot
 * level it afterwards, so every open of the main window (first open, [打开面板], [回到面前]) waits here until the head
 * has been level (|pitch| <= [maxPitchDeg]) for [dwellMs]. The caller feeds time and head pitch every frame (degrees,
 * + up; null = no valid head pose). No head for [noHeadMs] from the first such frame -> open anyway.
 */
class LevelGate(val maxPitchDeg: Float = 10f, val dwellMs: Long = 300L, val noHeadMs: Long = 5000L) {
    var pending: String? = null
        private set
    private var askedMs = 0L
    private var levelSinceMs = -1L
    private var noHeadSinceMs = -1L

    val waiting: Boolean
        get() = pending != null

    fun ask(why: String, now: Long) {
        if (pending == null) askedMs = now
        pending = why
        levelSinceMs = -1L
        noHeadSinceMs = -1L
    }

    fun cancel() {
        pending = null
        levelSinceMs = -1L
        noHeadSinceMs = -1L
    }

    fun waitedMs(now: Long): Long = if (pending == null) 0L else now - askedMs

    /** The reason to open now, or null = keep waiting. */
    fun step(now: Long, pitchDeg: Float?): String? {
        val why = pending ?: return null
        if (pitchDeg == null || !pitchDeg.isFinite()) {
            levelSinceMs = -1L
            if (noHeadSinceMs < 0L) noHeadSinceMs = now // from the first frame, not from the ask
            if (now - noHeadSinceMs < noHeadMs) return null
            cancel()
            return "$why/nohead"
        }
        noHeadSinceMs = -1L
        if (abs(pitchDeg) > maxPitchDeg) {
            levelSinceMs = -1L
            return null
        }
        if (levelSinceMs < 0L) levelSinceMs = now
        if (now - levelSinceMs < dwellMs) return null
        cancel()
        return why
    }

    /** Show 「平视前方」 while waiting on a tilted head. */
    fun hintWanted(pitchDeg: Float?): Boolean = pending != null && pitchDeg != null && abs(pitchDeg) > maxPitchDeg

    companion object {
        const val HINT_DIST_M = 0.75f
        const val HINT_MAX_PITCH_DEG = 40f

        /**
         * Where the 「平视前方」 card goes: on your line of sight (pitch clamped to ±40°), [HINT_DIST_M] from the eyes, in
         * the head's yaw only. The card itself stays upright and turns only about the vertical (D9; PanelRig maxPitch 0).
         */
        fun hintPosition(head: HeadPose): Vec3 {
            val yaw = head.yaw()
            val max = Math.toRadians(HINT_MAX_PITCH_DEG.toDouble()).toFloat()
            val p = head.pitch.coerceIn(-max, max)
            val flat = Yaw.dir(yaw)
            return head.position + Vec3(flat.x * cos(p) * HINT_DIST_M, sin(p) * HINT_DIST_M, flat.z * cos(p) * HINT_DIST_M)
        }
    }
}
