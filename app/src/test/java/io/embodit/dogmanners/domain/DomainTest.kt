package io.embodit.dogmanners.domain

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainTest {
    private val stare = BodyInput(0f, staring = true, stareByEyes = true, leanM = 0f, sudden = false, still = true)
    private val faceOnly = BodyInput(5f, staring = false, stareByEyes = false, leanM = 0f, sudden = false, still = true)
    private val side = BodyInput(60f, staring = false, stareByEyes = false, leanM = 0f, sudden = false, still = true)

    // ------------------------------------------------------------------ pressure model (design dogBehavior.pressureModel)

    @Test
    fun faceOnAloneNeverReachesWary() {
        val p = Pressure()
        repeat(2000) { p.step(0.05f, faceOnly, 1.3f) }
        assertEquals(2.5f, p.value, 1e-3f)
    }

    @Test
    fun stareMakesWaryAndSideCalms() {
        val b = DogBrain()
        b.start()
        var t = 0f
        // walk-in: distance closes; staring from the start
        var nose = 6f
        while (t < 10f && b.act != Act.WARY) {
            b.step(0.05f, stare, nose)
            nose = maxOf(1.3f, nose - 0.025f)
            t += 0.05f
        }
        assertEquals(Act.WARY, b.act)
        assertTrue("wary after ~2.5 s of staring + 0.6 s hysteresis, took $t", t < 4f)
    }

    @Test
    fun fromMaxPressureCalmsBeforeLeaving() {
        // acceptance: from P = 4, the right moves reach relaxed before the 10 s leave timer
        val b = DogBrain()
        b.start()
        b.forcePressure(4f)
        b.step(0.05f, stare, 2f)
        repeat(20) { b.step(0.05f, stare, 2f) }
        assertEquals(Act.WARY, b.act)
        var t = 0f
        while (b.act == Act.WARY && t < 20f) {
            b.step(0.05f, side, 2f)
            t += 0.05f
        }
        assertEquals(Act.SHAKE, b.act)
        assertTrue("calmed in $t s, must be < 10 s", t < 10f)
        assertTrue(t in 5f..7.5f)
    }

    @Test
    fun staringTenSecondsMakesItLeave() {
        val b = DogBrain()
        b.start()
        b.forcePressure(4f)
        var t = 0f
        while (b.act != Act.LEAVE && t < 30f) {
            b.step(0.05f, stare, 2f)
            t += 0.05f
        }
        assertEquals(Act.LEAVE, b.act)
        assertTrue(t in 10f..11.5f)
    }

    @Test
    fun l1HappyPathSniffsBowsAndLeaves() {
        val sc = Scenario(Mode.Lesson(LessonId.L1))
        var nose = 6f
        var t = 0f
        var sniffed = false
        var bowed = false
        while (t < 60f && sc.outcome == null) {
            val s = sc.step(0.05f, side, nose)
            sc.targetM?.let { target -> nose += (target - nose).coerceIn(-0.025f, 0.025f) }
            if (s.act == Act.SNIFF) sniffed = true
            if (s.act == Act.PLAY) bowed = true
            t += 0.05f
        }
        assertTrue(sniffed)
        assertTrue(bowed)
        assertEquals(Outcome.OK, sc.outcome)
        assertTrue(sc.goals().all { it.second })
    }

    @Test
    fun tooCloseBacksOffAndHardMinimumHolds() {
        val b = DogBrain()
        b.start()
        b.step(0.05f, side, 0.9f)
        assertTrue(b.events.contains(DogEvent.TOO_CLOSE) || b.targetM == Stops.WAIT_M)
        assertTrue(Stops.HARD_MIN_M == 0.8f)
    }

    @Test
    fun choicesGiveTheDesignedResults() {
        val l4 = Scenario(Mode.Lesson(LessonId.L4))
        assertEquals(Phase.CHOICE, l4.phase)
        l4.choose(1)
        repeat(100) { l4.step(0.05f, side, 2.2f) }
        assertEquals(Outcome.BAD, l4.outcome)
        assertEquals(ResultText.L4_BAD, l4.result)
        assertEquals(Act.WARY, l4.act)
        val l6 = Scenario(Mode.Lesson(LessonId.L6))
        l6.choose(1)
        repeat(100) { l6.step(0.05f, side, 1.6f) }
        assertEquals(Outcome.OK, l6.outcome)
        val l3 = Scenario(Mode.Lesson(LessonId.L3))
        l3.choose(1)
        repeat(400) { l3.step(0.05f, side, 2.5f) }
        assertEquals(Outcome.OK, l3.outcome)
        val l3b = Scenario(Mode.Lesson(LessonId.L3))
        l3b.choose(1)
        repeat(400) { l3b.step(0.05f, stare, 2.5f) }
        assertNotEquals(Outcome.OK, l3b.outcome)
    }

    // ------------------------------------------------------------------ D9 placeUpright hard gate

    private fun headLooking(pitchDeg: Float, rollDeg: Float, yawDeg: Float): HeadPose {
        val q = Quat4.axisAngle(Vec3.UP, Math.toRadians(yawDeg.toDouble()).toFloat()) *
            Quat4.axisAngle(Vec3.RIGHT, Math.toRadians(pitchDeg.toDouble()).toFloat()) *
            Quat4.axisAngle(Vec3(0f, 0f, 1f), Math.toRadians(rollDeg.toDouble()).toFloat())
        return HeadPose(Vec3(0.3f, 1.6f, -0.2f), q)
    }

    @Test
    fun panelsStayUprightWhateverTheHeadTilt() {
        for ((pitch, roll) in listOf(-30f to 0f, 30f to 0f, 0f to 20f)) {
            val head = headLooking(pitch, roll, 25f)
            val u = placeUpright(head, PanelPlaces.P1_YAW_DEG, PanelPlaces.P1_DIST_M, PanelPlaces.P1_DOWN_DEG)
            val up = u.rotation.rotate(Vec3.UP)
            val ang = Math.toDegrees(acos(up.dot(Vec3.UP).coerceIn(-1f, 1f).toDouble()))
            assertTrue("up tilt $ang for pitch $pitch roll $roll", ang < 1.0)
            val rel = u.position - head.position
            val dist = rel.length
            val below = Math.toDegrees(atan2(-rel.y.toDouble(), hypot(rel.x, rel.z).toDouble()))
            assertTrue("dist $dist", dist in 0.8f..1.2f)
            assertTrue("below $below", below in 10.0..20.0)
        }
    }

    @Test
    fun fourSidedPlacementFacesTheUser() {
        // stand east / south / west / north of a marker and look at it: the panel faces back at you (< 10°), scale > 0
        for (yawDeg in listOf(0f, 90f, 180f, 270f)) {
            val head = headLooking(0f, 0f, yawDeg)
            val u = placeUpright(head, 0f, 1f, 12f)
            val face = u.rotation.rotate(Vec3(0f, 0f, 1f))
            val toUser = Vec3(head.position.x - u.position.x, 0f, head.position.z - u.position.z).normalized()
            val ang = Math.toDegrees(acos(face.dot(toUser).coerceIn(-1f, 1f).toDouble()))
            assertTrue("yaw $yawDeg angle $ang", ang < 10.0)
            val f = faceYaw(u.position, head.position)
            assertTrue(abs(Yaw.wrap(f - u.yaw)) < 0.17f)
        }
    }

    @Test
    fun parkFollowsTheHeadYawOnly() {
        val head = headLooking(-35f, 15f, 90f)
        val f = ParkFrame.around(head, ParkFrame.STAND_EYE_M)
        assertEquals(head.position.y - 1.6f, f.origin.y, 1e-4f)
        val ahead = f.toStage(Vec3(0f, 0f, -1f)) - f.origin
        assertEquals(0f, ahead.y, 1e-4f)
        val want = Yaw.dir(head.yaw())
        assertTrue(ahead.normalized().dot(want) > 0.999f)
    }

    // ------------------------------------------------------------------ D25 calibration

    @Test
    fun calibrationPutsTheDogOnTheOriginAtTrueSize() {
        val s = DogCalibration.size(Lineup.MEDIUM)
        assertEquals(0.58f, s.earM, 0.01f)
        val o = DogCalibration.fileOffset
        // origin point (between the paws, on the floor) -> (0, 0, 0) within 1 cm after scaling
        val x = (DogCalibration.FILE_ORIGIN_X + o.x) * s.scale
        val y = (DogCalibration.FILE_FLOOR_Y + o.y) * s.scale
        val z = (DogCalibration.FILE_ORIGIN_Z + o.z) * s.scale
        assertTrue(abs(x) < 0.01f && abs(y) < 0.01f && abs(z) < 0.01f)
        // forward = +Z, within 2°
        val f = DogCalibration.fileRotation.rotate(DogCalibration.dogForward)
        assertTrue(Math.toDegrees(acos(f.dot(Vec3(0f, 0f, 1f)).toDouble())) < 2.0)
        // the four dogs keep the design's shoulder ratios (within 10 %)
        val large = DogCalibration.size(Lineup.LARGE)
        assertEquals(0.57f / 0.39f, large.withersM / s.withersM, 0.1f * 0.57f / 0.39f)
        // all instances of one model share one scale
        assertEquals(DogCalibration.size(Lineup.MEDIUM).scale, DogCalibration.size(Lineup.MEDIUM).scale, 0f)
        assertEquals(1f, DogCalibration.renderFactor(85.5f), 0f)
        assertEquals(0.01f, DogCalibration.renderFactor(0.855f), 0f)
    }

    @Test
    fun bodyReaderTurnsTheHeadIntoFacingAndLean() {
        val r = BodyReader()
        val dogHead = Vec3(0f, 0.45f, -1.6f)
        val ahead = Vec3(0f, 0f, -1f)
        val i0 = r.read(0.05f, Vec3(0f, 1.6f, 0f), ahead, null, dogHead)
        assertTrue(i0.faceOn)
        val left = Quat4.axisAngle(Vec3.UP, Math.toRadians(60.0).toFloat()).rotate(ahead)
        val i1 = r.read(0.05f, Vec3(0f, 1.6f, 0f), left, null, dogHead)
        assertTrue(i1.sideOn)
        r.resetBaseline()
        r.read(0.05f, Vec3(0f, 1.6f, 0f), ahead, null, dogHead)
        val i2 = r.read(0.05f, Vec3(0f, 1.55f, -0.3f), ahead, null, dogHead)
        assertTrue("lean ${i2.leanM}", i2.leanM > 0.25f)
    }
}
