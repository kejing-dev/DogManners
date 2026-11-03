package io.embodit.dogmanners.domain

import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the park and the panels go (design container.placement, frame.sightLine, panels[].position).
 *
 * Park-local space: you stand at the origin on the floor, the path runs along -Z (ahead of you at placement time),
 * +X is your right, +Y up. Stage space = origin + yaw rotation of park-local ([ParkFrame]).
 *
 * Every pose given to a panel or to the park goes through [placeUpright]: only the HORIZONTAL heading of the head is
 * used (pitch and roll forced to 0), so a head tilted down / up / sideways never tilts the content (D9 hard gate).
 */
data class ParkFrame(val origin: Vec3, val yaw: Float) {
    private val q: Quat4
        get() = Quat4.axisAngle(Vec3.UP, yaw)

    fun toStage(local: Vec3): Vec3 = origin + q.rotate(local)

    fun toLocal(stage: Vec3): Vec3 = q.conjugate().rotate(stage - origin)

    fun dirToLocal(stage: Vec3): Vec3 = q.conjugate().rotate(stage)

    fun rotToStage(localYaw: Float): Quat4 = Quat4.axisAngle(Vec3.UP, yaw + localYaw)

    companion object {
        /** Eye heights above the floor (design: standing 1.60 m, seated 1.20 m). */
        const val STAND_EYE_M = 1.60f
        const val SIT_EYE_M = 1.20f

        /**
         * The park around a head pose: floor = head height minus the eye height, path along the head's yaw turned by
         * [pathYawDeg] ([Yaw] convention, + = left; the app passes -PanelPlaces.P1_YAW_DEG so the window ahead of
         * you sits left of the path).
         */
        fun around(head: HeadPose, eyeM: Float, pathYawDeg: Float = 0f): ParkFrame {
            val up = placeUpright(head, pathYawDeg, 0f, 0f)
            return ParkFrame(Vec3(head.position.x, head.position.y - eyeM, head.position.z), up.yaw)
        }
    }
}

/** A pose with no pitch and no roll: a position and a heading about the vertical (Yaw convention, + = left). */
data class Upright(val position: Vec3, val yaw: Float) {
    val rotation: Quat4
        get() = Quat4.axisAngle(Vec3.UP, yaw)
}

/**
 * The only way content is posed from the head (D9): [relYawDeg] around you (+ = left, the [Yaw] convention), [distM]
 * along the line of sight, [downDeg] below eye level. The result turns its face (+Z) back towards the head, about the
 * vertical only.
 */
fun placeUpright(head: HeadPose, relYawDeg: Float, distM: Float, downDeg: Float): Upright {
    val yaw0 = head.yaw(0f)
    val a = yaw0 + Math.toRadians(relYawDeg.toDouble()).toFloat()
    val down = Math.toRadians(downDeg.toDouble()).toFloat()
    val flat = distM * cos(down)
    val dir = Yaw.dir(a)
    val p = head.position + dir * flat - Vec3(0f, distM * sin(down), 0f)
    // the panel's +Z (its face) points back at the head: face yaw = a + pi in the panel's own convention
    val faceYaw = Yaw.wrap(a)
    return Upright(p, faceYaw)
}

/** Panel placement in park-local coordinates (you at the origin, eyes at [eyeM]). */
object PanelPlaces {
    /**
     * v0.1.2: the main window is a system window, which the system opens straight ahead of your head (≈ 1.75 m) and
     * code cannot move (native-window-move §4). So the park turns instead: the path runs [P1_YAW_DEG] to the right of
     * where you look when it is laid out, which keeps the design's angle between the main panel (now the window,
     * design −38° = left of the path) and the dog, and the dog never walks between you and the window.
     */
    const val P1_YAW_DEG = 38f // the window, left of the path (design −38°, design sign + = right)
    const val P1_DIST_M = 1.0f
    const val P1_DOWN_DEG = 15f

    /** The [打开面板] chip, where the window was, nearer and lower (only while the window is closed). */
    const val CHIP_DIST_M = 1.1f
    const val CHIP_DOWN_DEG = 18f

    /** P2 hangs at the dog's side towards your right, 0.95 m above the floor (never over its head). */
    const val P2_SIDE_M = 0.45f
    const val P2_HEIGHT_M = 0.95f

    /** Half the tag's width at scale 1 (design 0.34 m). */
    const val P2_HALF_W_M = 0.17f

    /** Panel distance at the closest stop (nose 1.3 m); further off it grows to keep that angular size (QA K2), up to [P2_MAX_K]. */
    const val P2_READ_M = 1.5f
    const val P2_MAX_K = 1.6f

    fun p2Scale(distM: Float): Float = (distM / P2_READ_M).coerceIn(1f, P2_MAX_K)

    fun local(relYawDeg: Float, distM: Float, downDeg: Float, eyeM: Float): Vec3 {
        val head = HeadPose(Vec3(0f, eyeM, 0f), Quat4.IDENTITY)
        return placeUpright(head, relYawDeg, distM, downDeg).position
    }
}

/** Panel facing helper: the yaw (Yaw convention) of a panel at [panel] that turns its face towards [viewer]. */
fun faceYaw(panel: Vec3, viewer: Vec3): Float {
    val to = viewer - panel
    return kotlin.math.atan2(to.x, to.z)
}

@Suppress("unused")
private fun unused(x: Float) = sin(x)
