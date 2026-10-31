package io.embodit.dogmanners.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D27 hard gate (prompts/build.md, 2026-10-31): at least 10 deliberately sloppy inputs, all of them must pass, plus
 * "every branch ends" checks. Sloppy = half-side 25–30°, 15–20° short of the taught 40°, a head turn twice as slow,
 * a 0.1 m lean, the head 10 cm off. QA #1 (q1-1) case: L3, wary after a face-on start, then the face only at 28°.
 *
 * Test names `sloppyNN_*` are the sloppy set; `endsNN_*` are the all-branch timeout set.
 */
class SloppyTest {
    private fun body(facing: Float, lean: Float = 0f, stare: Boolean = false, still: Boolean = true) =
        BodyInput(facing, staring = stare, stareByEyes = false, leanM = lean, sudden = false, still = still)

    private class Run(val endS: Float?, val waryEver: Boolean, val nears: List<NearMiss>, val last: Snapshot)

    /** Steps [sc] for up to [maxS] s; the nose follows the dog's target at its own speed (as the scene moves it). */
    private fun run(sc: Scenario, maxS: Float, startNose: Float, input: (t: Float, nose: Float) -> BodyInput): Run {
        var nose = startNose
        var t = 0f
        var wary = false
        val nears = ArrayList<NearMiss>()
        var last = sc.snapshot()
        while (t < maxS) {
            last = sc.step(DT, input(t, nose), nose)
            if (sc.act == Act.WARY) wary = true
            last.near?.let { if (nears.lastOrNull() !== it) nears += it }
            sc.targetM?.let { target -> nose += (target - nose).coerceIn(-sc.speed * DT, sc.speed * DT) }
            t += DT
            if (last.phase == Phase.RESULT) return Run(t, wary, nears, last)
        }
        return Run(null, wary, nears, last)
    }

    private fun l3(leniency: Leniency = Leniency()) = Scenario(Mode.Lesson(LessonId.L3), leniency = leniency).also { it.choose(1) }

    private fun l5(leniency: Leniency = Leniency()) = Scenario(Mode.Lesson(LessonId.L5), leniency = leniency).also { it.choose(1) }

    // ------------------------------------------------------------------------------------------- BodyReader drive

    private val ahead = Vec3(0f, 0f, -1f)

    private fun yawed(deg: Float) = Quat4.axisAngle(Vec3.UP, Math.toRadians(deg.toDouble()).toFloat()).rotate(ahead)

    /** The dog's head centre for a nose distance (park-local: you at the origin, the path along -Z). */
    private fun dogHead(nose: Float, x: Float = 0f) = Vec3(x, 0.45f, -(nose + 0.1f))

    // =================================================================================================== sloppy set

    @Test
    fun sloppy01_L3_halfSide25AfterWary_calmsAndPasses() {
        val sc = l3()
        val r = run(sc, 20f, 2.5f) { t, _ -> if (t < 3f) body(0f) else body(25f) }
        assertTrue("went wary first", r.waryEver)
        assertNotNull("must end", r.endS)
        assertEquals(Outcome.OK, sc.outcome)
        assertTrue("ends at the 15 s mark, took ${r.endS}", r.endS!! in 15f..16.5f)
    }

    @Test
    fun sloppy02_L3_qaRepro_face28AfterWary_endsOk() {
        // q1-1: face-on after choosing -> wary; then the face only at 28° (looking at P1 is 38°)
        val sc = l3()
        var calmedAt = -1f
        val r = run(sc, 20f, 2.5f) { t, _ -> if (sc.act == Act.EAT && sc.judged.facingDeg >= 25f && calmedAt < 0f && t > 3f) calmedAt = t; if (t < 3f) body(0f) else body(28f) }
        assertEquals(Outcome.OK, sc.outcome)
        assertTrue("calmed within 1 s of turning to 28°, at $calmedAt", calmedAt in 3f..4f)
        assertNotNull(r.endS)
    }

    @Test
    fun sloppy03_L3_halfSide30FromTheStart_neverWary() {
        val sc = l3()
        val r = run(sc, 20f, 2.5f) { _, _ -> body(30f) }
        assertFalse(r.waryEver)
        assertEquals(Outcome.OK, sc.outcome)
        assertTrue(sc.goals().all { it.second })
    }

    @Test
    fun sloppy04_L3_18DegShortOf40_nearMissesLoosenAndPass() {
        // 40° taught, 18° short = 22°: under the 25° calm line; two near misses in a row loosen one level and say why
        val sc = l3()
        val r = run(sc, 20f, 2.5f) { t, _ -> if (t < 2.5f) body(0f) else body(22f) }
        assertEquals(Outcome.OK, sc.outcome)
        assertTrue("near misses say FACE", r.nears.isNotEmpty() && r.nears.all { it.cause == NearCause.FACE })
        assertTrue("loosened", r.nears.any { it.loosened })
        assertEquals(1, sc.leniency.level)
    }

    @Test
    fun sloppy05_L5_halfSide27_passesAt12s() {
        val sc = l5()
        val r = run(sc, 16f, 2.8f) { _, _ -> body(27f) }
        assertFalse(r.waryEver)
        assertEquals(Outcome.OK, sc.outcome)
        assertTrue(r.endS!! in 12f..13.5f)
    }

    @Test
    fun sloppy06_L5_20DegShortOf35_retriesLoosenUntilItPasses() {
        // face 15°: face-on for the touchy mom (design: 1 s face-on = wary). Near miss, near miss -> loosened -> pass
        val len = Leniency()
        val first = l5(len)
        run(first, 16f, 2.8f) { _, _ -> body(15f) }
        assertEquals(Outcome.BAD, first.outcome)
        val second = l5(len)
        run(second, 16f, 2.8f) { _, _ -> body(15f) }
        assertEquals(1, len.level)
        val third = l5(len)
        val r = run(third, 16f, 2.8f) { _, _ -> body(15f) }
        assertEquals("third go passes", Outcome.OK, third.outcome)
        assertFalse(r.waryEver)
    }

    @Test
    fun sloppy07_L1_15DegShortOf40_passes() {
        val sc = Scenario(Mode.Lesson(LessonId.L1))
        val r = run(sc, 70f, 6f) { _, _ -> body(25f) }
        assertEquals(Outcome.OK, sc.outcome)
        assertFalse(r.waryEver)
        assertTrue("turned-sideways goal ticks at 25°", sc.goals().first { it.first == Goal.SIDE }.second)
    }

    @Test
    fun sloppy08_L1_halfSide28_sideGoalTicks() {
        // QA K5: at 28° L1 passed but "侧过身" stayed unticked
        val sc = Scenario(Mode.Lesson(LessonId.L1))
        run(sc, 70f, 6f) { _, _ -> body(28f) }
        assertEquals(Outcome.OK, sc.outcome)
        assertTrue(sc.goals().all { it.second })
    }

    @Test
    fun sloppy09_L1_face15NotStaring_stillGetsSniffed() {
        // face-on alone holds P at its 2.5 cap; before the fix that blocked the sniff forever
        val sc = Scenario(Mode.Lesson(LessonId.L1))
        var sniffed = false
        run(sc, 70f, 6f) { _, _ -> if (sc.act == Act.SNIFF) sniffed = true; body(15f) }
        assertTrue(sniffed)
        assertEquals(Outcome.OK, sc.outcome)
    }

    @Test
    fun sloppy10_L3_slowHeadTurn_viaBodyReader() {
        // the face turns from the dog to 45° in 6 s (twice as slow as 3 s): turning away is trying, never a miss
        val sc = l3()
        val reader = BodyReader()
        var sudden = false
        var maxAng = 0f
        val r =
            run(sc, 20f, 2.5f) { t, nose ->
                val yaw = minOf(45f, 45f * t / 6f)
                val i = reader.read(DT, Vec3(0f, 1.6f, 0f), yawed(yaw), null, dogHead(nose))
                if (i.sudden) sudden = true
                if (!i.still) maxAng = maxOf(maxAng, yaw)
                i
            }
        assertFalse("a slow turn is not sudden", sudden)
        assertFalse("a slow turn away does not make it wary", r.waryEver)
        assertEquals(Outcome.OK, sc.outcome)
    }

    @Test
    fun sloppy11_L1_slowHeadTurn_viaBodyReader() {
        val sc = Scenario(Mode.Lesson(LessonId.L1))
        val reader = BodyReader()
        var sudden = false
        run(sc, 70f, 6f) { t, nose ->
            val yaw = minOf(50f, 50f * t / 8f)
            reader.read(DT, Vec3(0f, 1.6f, 0f), yawed(yaw), null, dogHead(nose)).also { if (it.sudden) sudden = true }
        }
        assertFalse(sudden)
        assertEquals(Outcome.OK, sc.outcome)
    }

    @Test
    fun sloppy12_L1_lean10cm_viaBodyReader() {
        // the head drifts 0.1 m towards the dog after the baseline: a lean of 0.1 m is not "leaning in"
        val sc = Scenario(Mode.Lesson(LessonId.L1))
        val reader = BodyReader()
        var maxLean = 0f
        run(sc, 70f, 6f) { t, nose ->
            val z = -0.1f * (t / 4f).coerceIn(0f, 1f)
            reader.read(DT, Vec3(0f, 1.6f, z), yawed(40f), null, dogHead(nose)).also { maxLean = maxOf(maxLean, it.leanM) }
        }
        assertEquals(0.1f, maxLean, 0.02f)
        assertEquals(Outcome.OK, sc.outcome)
        assertTrue("didn't lean in", sc.goals().first { it.first == Goal.NO_LEAN }.second)
    }

    @Test
    fun sloppy13_L3_lean10cmAt30Deg() {
        val sc = l3()
        val r = run(sc, 20f, 2.5f) { _, _ -> body(30f, lean = 0.1f) }
        assertFalse(r.waryEver)
        assertEquals(Outcome.OK, sc.outcome)
    }

    @Test
    fun sloppy14_L3_head10cmOff_viaBodyReader() {
        // standing 10 cm off to either side (and 10 cm closer) with the face at 30°
        for (pos in listOf(Vec3(0.1f, 1.6f, 0f), Vec3(-0.1f, 1.6f, 0f), Vec3(0f, 1.6f, -0.1f))) {
            val sc = l3()
            val reader = BodyReader()
            val r = run(sc, 20f, 2.5f) { _, nose -> reader.read(DT, pos, yawed(30f), null, dogHead(nose)) }
            assertFalse("head at $pos", r.waryEver)
            assertEquals("head at $pos", Outcome.OK, sc.outcome)
        }
    }

    @Test
    fun sloppy15_L3_lean30cm_nearMissesLoosenAndPass() {
        // 5 cm past the 0.25 m lean line: wary, then "身子往后收一点", loosened, eats again, passes
        val sc = l3()
        val r = run(sc, 20f, 2.5f) { _, _ -> body(30f, lean = 0.3f) }
        assertTrue(r.waryEver)
        assertTrue(r.nears.any { it.cause == NearCause.LEAN && it.loosened })
        assertEquals(Outcome.OK, sc.outcome)
    }

    @Test
    fun sloppy16_L2_halfSide28_sniffsAndPasses() {
        val sc = Scenario(Mode.Lesson(LessonId.L2))
        sc.choose(1)
        run(sc, 60f, 2.3f) { _, _ -> body(28f) }
        assertEquals(Outcome.OK, sc.outcome)
        assertEquals(ResultText.L2_OK, sc.result)
    }

    // ============================================================================== every branch ends (D27 hard gate)

    @Test
    fun ends01_L3_wary_neverCalms_stillEndsAt15s() {
        // q1-1: the countdown at 0 s never ended while wary
        val sc = l3()
        val r = run(sc, 20f, 2.5f) { _, _ -> body(0f) }
        assertNotNull("must end while wary", r.endS)
        assertTrue(r.endS!! <= 16.5f)
        assertEquals(Outcome.BAD, sc.outcome)
        assertEquals(ResultText.L3_STARE, sc.result)
    }

    @Test
    fun ends02_L5_wary_ends() {
        val sc = l5()
        val r = run(sc, 20f, 2.8f) { _, _ -> body(0f, stare = true) }
        assertNotNull(r.endS)
        assertEquals(Outcome.BAD, sc.outcome)
    }

    @Test
    fun ends03_L1_fidgeting_neverSticks() {
        // not still, no stare: the sniff never completes, P used to freeze; the 60 s cap ends it
        val sc = Scenario(Mode.Lesson(LessonId.L1))
        val r = run(sc, 70f, 6f) { _, _ -> body(30f, still = false) }
        assertNotNull(r.endS)
        assertTrue(r.endS!! <= Scenario.L1_CAP_S + 3f)
    }

    @Test
    fun ends04_L1_faceOnAllTheTime_ends() {
        val sc = Scenario(Mode.Lesson(LessonId.L1))
        val r = run(sc, 70f, 6f) { _, _ -> body(0f) }
        assertNotNull(r.endS)
    }

    @Test
    fun ends05_L2_staring_ends() {
        val sc = Scenario(Mode.Lesson(LessonId.L2))
        sc.choose(1)
        val r = run(sc, 60f, 2.3f) { _, _ -> body(0f, stare = true) }
        assertNotNull(r.endS)
        assertEquals(Outcome.LEFT, sc.outcome)
    }

    @Test
    fun ends06_everyLessonEveryChoiceEveryBody_ends() {
        val bodies = listOf(body(0f, lean = 0.5f, stare = true), body(0f), body(30f), body(60f), body(28f, still = false))
        for (id in LessonId.entries) {
            val picks = if (id.choices == 0) listOf(-1) else (0 until id.choices).toList()
            for (pick in picks) for (b in bodies) {
                val sc = Scenario(Mode.Lesson(id))
                if (pick >= 0) sc.choose(pick)
                val start = sc.roles.first().startNoseM
                val r = run(sc, 75f, start) { _, _ -> b }
                assertNotNull("$id pick $pick body $b never ended (phase ${sc.phase} act ${sc.act})", r.endS)
            }
        }
    }

    @Test
    fun sloppySetHasAtLeastTenCases() {
        val n = SloppyTest::class.java.declaredMethods.count { it.name.startsWith("sloppy") && it.name[6].isDigit() && it.isAnnotationPresent(Test::class.java) }
        assertTrue("D27 needs >= 10 sloppy cases, has $n", n >= 10)
    }

    companion object {
        const val DT = 0.05f
    }
}
