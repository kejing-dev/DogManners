package io.embodit.dogmanners.domain

import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.1.2: the park turned so the system window (straight ahead) sits left of the path; the park models; the mix. */
class ParkV012Test {
    private fun deg(r: Float) = Math.toDegrees(r.toDouble()).toFloat()

    @Test
    fun windowAheadSitsLeftOfThePathWhateverTheHeadTilt() {
        // head yaw 0 / 70 / -130 deg, pitched down 30, up 30, rolled 20: the path runs 38 deg to the right of the head yaw
        for (yawDeg in listOf(0f, 70f, -130f)) {
            for (tilt in listOf(Quat4.axisAngle(Vec3.RIGHT, -0.52f), Quat4.axisAngle(Vec3.RIGHT, 0.52f), Quat4.axisAngle(Vec3(0f, 0f, 1f), 0.35f))) {
                val head = HeadPose(Vec3(0.3f, 1.62f, -0.2f), Quat4.axisAngle(Vec3.UP, Math.toRadians(yawDeg.toDouble()).toFloat()) * tilt)
                val f = ParkFrame.around(head, ParkFrame.STAND_EYE_M, -PanelPlaces.P1_YAW_DEG)
                val pathDir = f.toStage(Vec3(0f, 0f, -1f)) - f.toStage(Vec3.ZERO)
                val rel = Yaw.wrap(Yaw.of(pathDir) - head.yaw(0f))
                assertEquals("path relative to the head ($yawDeg)", -PanelPlaces.P1_YAW_DEG, deg(rel), 0.5f)
                assertEquals("floor", 0.02f, f.origin.y, 1e-3f)
                assertEquals("path stays level", 0f, pathDir.y, 1e-4f)
            }
        }
    }

    @Test
    fun theDogsLineNeverCrossesTheWindowsLine() {
        // the window opens ~1.75 m straight ahead of the head: the dog's path (and its stops) is 38 deg off that line,
        // so at every stop the dog is at least 0.9 m to the side of the window's line of sight
        val head = HeadPose(Vec3(0f, 1.6f, 0f), Quat4.IDENTITY)
        val f = ParkFrame.around(head, ParkFrame.STAND_EYE_M, -PanelPlaces.P1_YAW_DEG)
        for (nose in listOf(Stops.SNIFF_M, Stops.WAIT_M, 2.0f, 3.0f, 6.0f)) {
            val dog = f.toStage(Vec3(0f, 0f, -nose))
            val sideways = abs(dog.x) // the window's line of sight is the stage -Z axis here
            assertTrue("dog at $nose m is ${sideways} m off the window line", sideways > 0.6f)
        }
    }

    @Test
    fun parkModelsAreCalibratedToTrueSize() {
        // targets: tools/make_park.py MODELS; every model's floor centre is the origin (D25)
        val targets = mapOf(
            ParkModels.OWNER to (1.68f to "h"), ParkModels.TREE_LINDEN to (5.0f to "h"), ParkModels.TREE_MAPLE to (5.6f to "h"),
            ParkModels.BENCH to (0.86f to "h"), ParkModels.FENCE to (1.0f to "h"), ParkModels.BUSH to (0.95f to "h"),
            ParkModels.BOWL to (0.26f to "w"), ParkModels.BED to (1.0f to "w"),
        )
        assertEquals(ParkModels.ALL.size, targets.size)
        for ((m, t) in targets) {
            val got = if (t.second == "h") m.heightM else maxOf(m.widthM, m.depthM)
            assertEquals("${m.asset} size", t.first, got, t.first * 0.1f)
            assertEquals("${m.asset} floor", 0f, m.minY, 0.01f)
            assertEquals("${m.asset} centre x", 0f, m.centreX, 0.01f)
            assertEquals("${m.asset} centre z", 0f, m.centreZ, 0.01f)
        }
        // the leash hand: at the owner's side, about hip height
        val h = ParkModels.OWNER_HAND
        assertTrue("hand height ${h.y}", h.y in 0.55f..1.05f)
        assertTrue("hand to the side ${h.x}", abs(h.x) in 0.1f..0.45f)
    }

    @Test
    fun appGainsAreTheRenderedGains() {
        // platform/Sound.kt GAIN_* must equal tools/make_sounds.py GAINS (the QA mix is rendered with those)
        val py = File("../tools/make_sounds.py").takeIf { it.exists() } ?: File("tools/make_sounds.py")
        val text = py.readText()
        val gains = Regex("\"(\\w+)\": ([0-9.]+)").findAll(text.substringAfter("GAINS = {").substringBefore("}")).associate { it.groupValues[1] to it.groupValues[2].toFloat() }
        val kt = File(py.parentFile, "../app/src/main/java/io/embodit/dogmanners/platform/Sound.kt").readText()
        for ((k, v) in gains) {
            val m = Regex("GAIN_${k.uppercase()} = ([0-9.]+)f").find(kt)
            assertTrue("Sound.kt has GAIN_${k.uppercase()}", m != null)
            assertEquals("gain $k", v, m!!.groupValues[1].toFloat(), 1e-6f)
        }
        // v0.1.3 (B24): only the dog's near sounds, every one at least 2.5 dB under v0.1.2; no tags jingle, no chime
        val v012 = mapOf("pant" to 0.50f, "sniff" to 0.45f, "step" to 0.40f, "shake" to 0.45f, "wuf" to 0.25f)
        assertEquals(v012.keys, gains.keys)
        for ((k, g) in v012) assertTrue("$k quieter", 20 * kotlin.math.log10(g / gains.getValue(k)) >= 2.5f)
        for (k in listOf("bed", "bark", "voices", "bird", "tags", "chime")) assertTrue("no GAIN_${k.uppercase()}", !kt.contains("GAIN_${k.uppercase()}"))
    }

    @Test
    fun noAmbientFilesShip() {
        // B24: no bed loop, far barks, passers-by or birds in the APK; Sound has no looping track
        // v0.1.3 headset 「删掉那个叮当声音」: no collar tags / bell, no chime
        val dir = (File("src/main/assets/sound").takeIf { it.isDirectory } ?: File("app/src/main/assets/sound"))
        val names = dir.list()!!.filter { it.endsWith(".wav") }.map { it.removeSuffix(".wav").trimEnd { c -> c.isDigit() } }.toSet()
        assertEquals(setOf("pant", "sniff", "step", "shake", "wuf"), names)
        val kt = File(dir, "../../java/io/embodit/dogmanners/platform/Sound.kt").readText()
        assertTrue("no AudioTrack loop", !kt.contains("AudioTrack") && !kt.contains("setLoopPoints"))
    }
}
