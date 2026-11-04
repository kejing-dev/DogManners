package io.embodit.dogmanners.domain

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.1.3: the window waits for a level head (D9); natural light (B24). */
class V013Test {
    private fun head(pitchDeg: Float, yawDeg: Float = 0f, rollDeg: Float = 0f): HeadPose {
        val r = Quat4.axisAngle(Vec3.UP, Math.toRadians(yawDeg.toDouble()).toFloat()) *
            Quat4.axisAngle(Vec3.RIGHT, Math.toRadians(pitchDeg.toDouble()).toFloat()) *
            Quat4.axisAngle(Vec3(0f, 0f, 1f), Math.toRadians(rollDeg.toDouble()).toFloat())
        return HeadPose(Vec3(0.2f, 1.6f, -0.1f), r)
    }

    // ------------------------------------------------------------------------------------------ LevelGate (D9)

    @Test
    fun levelHeadOpensAfterTheDwell() {
        val g = LevelGate()
        g.ask("stage", 0)
        assertNull(g.step(0, 2f))
        assertNull(g.step(250, 2f))
        assertEquals("stage", g.step(300, 2f))
        assertFalse(g.waiting)
    }

    @Test
    fun headDown30WaitsAndHints() {
        val g = LevelGate()
        g.ask("stage", 0)
        for (t in 0L..20_000L step 16) assertNull(g.step(t, -30f))
        assertTrue(g.hintWanted(-30f))
        assertTrue(g.waiting)
    }

    @Test
    fun headUp30WaitsThenOpensOnceLevel() {
        val g = LevelGate()
        g.ask("recall", 0)
        for (t in 0L..3000L step 16) assertNull(g.step(t, 30f))
        assertNull(g.step(3100, 4f))
        assertEquals("recall", g.step(3400, -4f))
    }

    @Test
    fun aGlanceDownRestartsTheDwell() {
        val g = LevelGate()
        g.ask("chip", 0)
        assertNull(g.step(0, 0f))
        assertNull(g.step(200, -25f))
        assertNull(g.step(250, 0f))
        assertNull(g.step(500, 0f)) // 250 ms level only
        assertEquals("chip", g.step(550, 0f))
    }

    @Test
    fun noHeadOpensAfterFiveSecondsFromTheFirstFrame() {
        val g = LevelGate()
        g.ask("stage", 0)
        // the Stage's first frame comes late (headset ~6 s): the 5 s count starts there, not at the ask
        assertNull(g.step(6000, null))
        assertNull(g.step(10_900, null))
        assertEquals("stage/nohead", g.step(11_000, null))
    }

    @Test
    fun theBoundaryCountsAsLevel() {
        val g = LevelGate()
        g.ask("stage", 0)
        assertNull(g.step(0, 10f))
        assertEquals("stage", g.step(300, -10f))
        assertFalse(g.hintWanted(10f))
        assertTrue(LevelGate().also { it.ask("x", 0) }.hintWanted(10.5f))
    }

    @Test
    fun cancelLeavesNothingPending() {
        val g = LevelGate()
        g.ask("stage", 0)
        g.cancel()
        assertNull(g.step(1000, 0f))
        assertFalse(g.hintWanted(-30f))
        assertEquals(0L, g.waitedMs(5000))
    }

    @Test
    fun hintSitsOnTheLineOfSightAndStaysUpright() {
        for (pitch in listOf(-30f, 0f, 30f, -60f, 60f)) for (yaw in listOf(0f, 90f, -135f)) for (roll in listOf(0f, 20f)) {
            val h = head(pitch, yaw, roll)
            val p = LevelGate.hintPosition(h)
            val d = p - h.position
            assertEquals("distance", LevelGate.HINT_DIST_M, d.length, 1e-3f)
            val elev = Math.toDegrees(atan2(d.y, hypot(d.x, d.z)).toDouble()).toFloat()
            assertEquals("elevation follows pitch, clamped ±40", Math.toDegrees(h.pitch.toDouble()).toFloat().coerceIn(-40f, 40f), elev, 0.5f)
            val yawErr = abs(Yaw.wrap(Yaw.of(Vec3(d.x, 0f, d.z)) - h.yaw()))
            assertTrue("in the head's yaw", Math.toDegrees(yawErr.toDouble()) < 0.5)
            // the card turns about the vertical only (yaw towards the eyes, pitch 0): its up is the world up
            val face = Quat4.axisAngle(Vec3.UP, faceYaw(p, h.position))
            assertTrue("upright", face.rotate(Vec3.UP).y > 0.9998f)
        }
    }

    @Test
    fun everyWindowOpenGoesThroughTheGate() {
        val src = srcDir()
        val window = File(src, "main/java/io/embodit/dogmanners/ui/window/DogWindow.kt").readText()
        // exactly one openWindowContainer, inside openNow, which only the gate and its watchdog call
        assertEquals(1, Regex("openWindowContainer").findAll(window).count())
        val openNowBody = window.substringAfter("private fun openNow(").substringBefore("\n    }")
        assertTrue(openNowBody.contains("openWindowContainer"))
        assertEquals(3, Regex("openNow\\(").findAll(window).count()) // definition + gate + watchdog
        val all = File(src, "main/java").walkTopDown().filter { it.extension == "kt" }.map { it.readText() }.joinToString("\n")
        assertEquals(1, Regex("openWindowContainer").findAll(all).count())
    }

    // ------------------------------------------------------------------------------------------ light (B24)

    private fun srcDir(): File = File("src").takeIf { File(it, "main").isDirectory } ?: File("app/src")

    @Test
    fun parkIblIsAValidCubemap() {
        val f = File(srcDir(), "main/assets/park/park_ibl.ktx")
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val id = ByteArray(12).also { b.get(it) }
        assertEquals("«KTX 11»\r\n\u001a\n", String(id, Charsets.ISO_8859_1))
        val h = IntArray(13) { b.getInt(12 + it * 4) }
        assertEquals(35898, h[1]) // GL_UNSIGNED_INT_10F_11F_11F_REV, as the SDK's own map
        assertEquals(256, h[6])
        assertEquals(6, h[10])
        assertEquals(5, h[11])
        val kv = String(f.readBytes(), 64, h[12], Charsets.US_ASCII)
        val sh = kv.substringAfter("sh\u0000").trim('\u0000').trim().lines().map { l -> l.trim().split(" ").map { it.toFloat() } }
        assertEquals(9, sh.size)
        assertTrue("sky brighter than the lawn", sh[1].all { it > 0f } && sh[0].all { it > 0f })
        assertTrue("blue sky: more blue than red light from above", sh[1][2] > sh[1][0])
    }

    @Test
    fun parkIsLitNotGlowing() {
        val src = srcDir()
        val kit = File(src, "main/java/io/embodit/dogmanners/ui/scene/SceneKit.kt").readText()
        assertFalse("no emission standing in for ambient light", kit.contains("setEmissive"))
        val scene = File(src, "main/java/io/embodit/dogmanners/ui/scene/ParkScene.kt").readText()
        assertTrue(scene.contains("StageEnvironmentLightingComponent(ImageBasedLightSource.Single("))
        assertEquals("one sun, casting shadows", 1, Regex("DirectionalLightComponent\\(").findAll(scene).count())
        assertTrue(scene.contains("DirectionalLightComponent(Color4(1f, 0.95f, 0.86f, 1f), SUN_LUX, true)"))
        // unlit only for the sky dome; everything else in the park is PBR (overlays use m.sheer)
        assertEquals(1, Regex("m\\.unlit\\(").findAll(scene).count())
        assertTrue(scene.lines().single { it.contains("m.unlit(") }.contains("\"sky\""))
        // contact shadows: ground, grass and path receive; trees, bushes, bench, props and the dog cast
        assertEquals(3, Regex("groundShadow\\(false, true\\)").findAll(scene).count())
        val dog = File(src, "main/java/io/embodit/dogmanners/ui/scene/DogView.kt").readText()
        assertTrue(dog.contains("clone.groundShadow(true, false)"))
        assertFalse("the dark blob discs are gone", scene.contains("blob"))
    }
}
