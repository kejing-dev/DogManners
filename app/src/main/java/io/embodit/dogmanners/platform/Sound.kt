package io.embodit.dogmanners.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import io.embodit.dogmanners.domain.Act
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The app's sounds. Files are rendered offline by tools/make_sounds.py into assets/sound (every file at -1 dBFS peak;
 * the gains here set the loudness, same table as GAINS there).
 *
 * v0.1.3 (profile B24, headset 2026-11-04 "不需要环境背景音，现在吵死了"): no ambient bed, no far barks, passers-by or
 * birds. Only the dog you meet, near-field and panned to where it is: paw steps on the path while it walks,
 * panting while it waits or plays (mouth shut = no pant while wary), sniffing, a fur shake, one soft "wuf" when it guards
 * the fence (L6). Everything ~3 dB under v0.1.2. Also v0.1.3 (headset 「删掉那个叮当声音」): no collar tags / bell
 * jingle (not with the steps, not in the shake) and no result chime. Mute is the P1 switch (remembered, D15).
 */
class Sound(private val context: Context) {
    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()
    private val rnd = Random(7)
    var on = true
    private var paused = false

    // timers (elapsedRealtime ms)
    private var nextStepMs = 0L
    private var stepN = 0
    private var nextPantMs = 0L
    private var nextSniffMs = 0L
    private var lastAct: Act? = null

    /** Where the dog is for the mix: on (in the park), its act, walking, nose distance (m), pan (-1 left .. +1 right). */
    data class DogCue(val act: Act, val moving: Boolean, val distM: Float, val pan: Float, val guarding: Boolean)

    fun start() {
        if (pool != null) return
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val p = SoundPool.Builder().setMaxStreams(8).setAudioAttributes(attrs).build()
        pool = p
        val names = runCatching { context.assets.list("sound") }.getOrNull().orEmpty().filter { it.endsWith(".wav") }
        for (f in names) {
            runCatching { context.assets.openFd("sound/$f").use { ids[f.removeSuffix(".wav")] = p.load(it, 1) } }
                .onFailure { DogLog.e("SOUND load $f failed: $it") }
        }
        DogLog.i("SOUND ready ${ids.keys.sorted()}")
    }

    fun release() {
        runCatching { pool?.release() }
        pool = null
        ids.clear()
    }

    /** App to the background / back (DogScreen lifecycle via ParkScene). */
    fun pause() {
        paused = true
        runCatching { pool?.autoPause() }
    }

    fun resume() {
        paused = false
        runCatching { pool?.autoResume() }
    }

    private fun play(name: String, gain: Float, pan: Float = 0f, rate: Float = 1f) {
        if (!on || paused) return
        val p = pool ?: return
        val id = ids[name] ?: return
        // equal-power pan
        val a = (pan.coerceIn(-1f, 1f) + 1f) * (PI / 4).toFloat()
        val l = gain * cos(a) * SQRT2
        val r = gain * sin(a) * SQRT2
        p.play(id, l.coerceIn(0f, 1f), r.coerceIn(0f, 1f), 1, 0, rate)
    }

    /**
     * Called every frame from the scene. [dog] is null outside a scene with a dog. Steps follow the walk, the pant
     * runs while the dog waits / plays with its mouth open. Nothing plays without a dog (B24: no ambient).
     */
    fun tick(nowMs: Long, dog: DogCue?) {
        if (dog != null) dogSounds(nowMs, dog)
    }

    private fun dogSounds(now: Long, d: DogCue) {
        // nearer = louder (1 / distance, full level at 1.3 m), never above the file gain
        val near = (1.3f / d.distM.coerceAtLeast(0.5f)).coerceIn(0.15f, 1f)
        val act = d.act
        if (act != lastAct) {
            if (act == Act.SHAKE) play("shake", GAIN_SHAKE * near, d.pan)
            if (act == Act.WARY && d.guarding) play("wuf", GAIN_WUF * near, d.pan)
            if (act == Act.SNIFF) nextSniffMs = now + 300
            lastAct = act
        }
        if (d.moving && now >= nextStepMs) {
            val trot = act == Act.LEAVE || act == Act.PLAY
            nextStepMs = now + (if (trot) 210L else 270L) + rnd.nextInt(40)
            play("step${stepN % 4}", GAIN_STEP * near, d.pan, 0.95f + 0.1f * rnd.nextFloat())
            stepN++
        }
        val panting = act == Act.WAIT || act == Act.APPROACH || act == Act.PLAY || act == Act.SIT
        if (panting && now >= nextPantMs) {
            nextPantMs = now + PANT_MS + rnd.nextInt(500)
            play("pant", GAIN_PANT * near, d.pan, 0.96f + 0.08f * rnd.nextFloat())
        }
        if (act == Act.SNIFF && now >= nextSniffMs) {
            nextSniffMs = now + 1300 + rnd.nextInt(500)
            play("sniff", GAIN_SNIFF * near, d.pan)
        }
    }

    companion object {
        // playback gains, linear (tools/make_sounds.py GAINS)
        const val GAIN_PANT = 0.35f
        const val GAIN_SNIFF = 0.32f
        const val GAIN_STEP = 0.28f
        const val GAIN_SHAKE = 0.32f
        const val GAIN_WUF = 0.18f
        private const val PANT_MS = 2150L
        private val SQRT2 = sqrt(2f)
    }
}
