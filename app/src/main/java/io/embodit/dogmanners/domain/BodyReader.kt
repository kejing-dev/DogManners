package io.embodit.dogmanners.domain

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot

/**
 * Turns the head (and, when there is one, the eye gaze) into what the dog reads (design
 * dogBehavior.inputsAsTeachingSignals): facing angle, stare with its dwell, lean against a baseline, sudden moves,
 * stillness. Everything is in park-local metres (you at the origin, the path along -Z). Pure Kotlin.
 *
 * Debug overrides ([stareOverride], [leanOverride], [yawOffsetDeg]) are fed in here, before the dog's state machine,
 * so a debug command goes through exactly the same transitions as a real body.
 */
class BodyReader {
    var stareOverride: Boolean? = null
    var leanOverride: Float? = null

    /** Debug: turns the reported head direction by this many degrees about the vertical (emulator: "turn sideways"). */
    var yawOffsetDeg = 0f

    private var stareDwell = 0f
    private var baseline: Vec3? = null
    private var lastPos: Vec3? = null
    private var lastFwd: Vec3? = null
    private var linSmooth = 0f
    private var angSmooth = 0f
    private var suddenCooldown = 0f

    /** True once a stare was detected by its dwell (the gaze light shows it before). */
    var gazeOnFace = false
        private set
    var lastLean = 0f
        private set

    fun resetBaseline() {
        baseline = null
    }

    fun reset() {
        stareDwell = 0f
        baseline = null
        lastPos = null
        lastFwd = null
        linSmooth = 0f
        angSmooth = 0f
        suddenCooldown = 0f
    }

    /**
     * @param head head position, park-local
     * @param forward head forward, park-local (unit)
     * @param eye eye ray (origin, unit dir), park-local, or null when the eyes are not usable
     * @param dogHead centre of the dog's head (the 0.18 m stare sphere)
     */
    fun read(dt: Float, head: Vec3, forward: Vec3, eye: Pair<Vec3, Vec3>?, dogHead: Vec3): BodyInput {
        val f = if (yawOffsetDeg != 0f) Quat4.axisAngle(Vec3.UP, Math.toRadians(yawOffsetDeg.toDouble()).toFloat()).rotate(forward) else forward
        // facing: horizontal angle between the face and the direction to the dog
        val toDog = Vec3(dogHead.x - head.x, 0f, dogHead.z - head.z)
        val fh = Vec3(f.x, 0f, f.z)
        val facing = angleDeg(fh, toDog)

        // stare: the eyes on the head sphere for 1.5 s, or the head within 10° of it for 3 s
        val byEyes = eye != null && yawOffsetDeg == 0f
        val hit =
            if (byEyes) rayHitsSphere(eye!!.first, eye.second, dogHead, STARE_RADIUS_M)
            else angleDeg(f, dogHead - head) <= HEAD_STARE_DEG
        gazeOnFace = hit
        stareDwell = if (hit) stareDwell + dt else 0f
        val need = if (byEyes) EYE_DWELL_S else HEAD_DWELL_S
        val staring = stareOverride ?: (stareDwell >= need)

        // lean: the neck pivot (head minus 0.10 m along the face) moving towards the dog
        val pivot = head - f * NECK_PIVOT_M
        val flatPivot = Vec3(pivot.x, 0f, pivot.z)
        val base = baseline ?: flatPivot.also { baseline = it }
        val dir = if (toDog.length > 1e-3f) toDog.normalized() else Vec3.AHEAD
        val lean = leanOverride ?: (flatPivot - base).dot(dir)
        lastLean = lean

        // speeds (smoothed over about 0.1 s)
        var sudden = false
        var still = true
        val lp = lastPos
        val lf = lastFwd
        if (lp != null && lf != null && dt > 1e-4f) {
            val lin = (head - lp).length / dt
            val ang = angleDeg(f, lf) / dt
            val k = (dt / SMOOTH_S).coerceIn(0f, 1f)
            linSmooth += (lin - linSmooth) * k
            angSmooth += (ang - angSmooth) * k
            suddenCooldown = (suddenCooldown - dt).coerceAtLeast(0f)
            if ((angSmooth > SUDDEN_DEG_S || linSmooth > SUDDEN_MPS) && suddenCooldown == 0f) {
                sudden = true
                suddenCooldown = SUDDEN_COOLDOWN_S
            }
            still = linSmooth < STILL_MPS && angSmooth < STILL_DEG_S
        }
        lastPos = head
        lastFwd = f
        return BodyInput(facing, staring, byEyes && stareOverride == null, lean, sudden, still)
    }

    companion object {
        const val STARE_RADIUS_M = 0.18f
        const val HEAD_STARE_DEG = 10f
        const val EYE_DWELL_S = 1.5f
        const val HEAD_DWELL_S = 3f
        const val NECK_PIVOT_M = 0.10f
        const val SMOOTH_S = 0.1f
        const val SUDDEN_DEG_S = 120f
        const val SUDDEN_MPS = 0.6f
        const val SUDDEN_COOLDOWN_S = 1f
        const val STILL_MPS = 0.10f
        const val STILL_DEG_S = 25f

        fun angleDeg(a: Vec3, b: Vec3): Float {
            val la = a.length
            val lb = b.length
            if (la < 1e-6f || lb < 1e-6f) return 180f
            return Math.toDegrees(acos((a.dot(b) / (la * lb)).coerceIn(-1f, 1f).toDouble())).toFloat()
        }

        fun rayHitsSphere(o: Vec3, d: Vec3, c: Vec3, r: Float): Boolean {
            val oc = c - o
            val t = oc.dot(d)
            if (t < 0f) return false
            val closest = o + d * t
            return (closest - c).length <= r
        }

        /** Horizontal distance between two points. */
        fun flat(a: Vec3, b: Vec3): Float = hypot(a.x - b.x, a.z - b.z)

        @Suppress("unused")
        private fun near(a: Float, b: Float) = abs(a - b) < 1e-4f
    }
}
