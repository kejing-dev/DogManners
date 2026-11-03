package io.embodit.dogmanners.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import io.embodit.dogmanners.domain.Act
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The app's sounds (v0.1.2, profile B12: a dog park, not birds). Files are rendered offline by tools/make_sounds.py
 * into assets/sound (every file at -1 dBFS peak; the gains here set the loudness, same table as GAINS there):
 *
 * - bed: a seamless 24 s loop under everything: leaves, people talking far off, the odd far dog (AudioTrack, static).
 * - the dog you meet, panned to where it is: paw steps on the path + collar tags while it walks, panting while it waits
 *   or plays (mouth shut = no pant while wary), sniffing, a fur shake with the tags, one soft "wuf" when it guards the
 *   fence (L6).
 * - the park around you: far barks (3 dogs) and passers-by talking, every 15–45 s from a random side.
 * - birds: decoration only, -29 dB, every 20–40 s, off with the 鸟叫 switch (remembered).
 * Mute is the P1 switch (remembered, D15); the bed pauses with the app.
 */
class Sound(private val context: Context) {
    private var pool: SoundPool? = null
    private var bed: AudioTrack? = null
    private val ids = HashMap<String, Int>()
    private val rnd = Random(7)
    var on = true
        set(v) {
            field = v
            applyBed()
        }
    var birdsOn = true
    private var paused = false

    // timers (elapsedRealtime ms)
    private var nextStepMs = 0L
    private var stepN = 0
    private var nextPantMs = 0L
    private var nextSniffMs = 0L
    private var nextParkMs = 0L
    private var nextBirdMs = 0L
    private var lastAct: Act? = null

    /** Where the dog is for the mix: on (in the park), its act, walking, nose distance (m), pan (-1 left .. +1 right). */
    data class DogCue(val act: Act, val moving: Boolean, val distM: Float, val pan: Float, val guarding: Boolean)

    fun start() {
        if (pool != null) return
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val p = SoundPool.Builder().setMaxStreams(8).setAudioAttributes(attrs).build()
        pool = p
        val names = runCatching { context.assets.list("sound") }.getOrNull().orEmpty().filter { it.endsWith(".wav") && it != "bed.wav" }
        for (f in names) {
            runCatching { context.assets.openFd("sound/$f").use { ids[f.removeSuffix(".wav")] = p.load(it, 1) } }
                .onFailure { DogLog.e("SOUND load $f failed: $it") }
        }
        bed = runCatching { loadBed(attrs) }.onFailure { DogLog.e("SOUND bed failed: $it") }.getOrNull()
        applyBed()
        DogLog.i("SOUND ready ${ids.keys.sorted()} bed=${bed != null} birds=$birdsOn")
    }

    fun release() {
        runCatching { bed?.stop() }
        runCatching { bed?.release() }
        bed = null
        runCatching { pool?.release() }
        pool = null
        ids.clear()
    }

    /** App to the background / back (DogScreen lifecycle via ParkScene). */
    fun pause() {
        paused = true
        applyBed()
        runCatching { pool?.autoPause() }
    }

    fun resume() {
        paused = false
        applyBed()
        runCatching { pool?.autoResume() }
    }

    private fun applyBed() {
        val b = bed ?: return
        runCatching {
            if (on && !paused) {
                b.setVolume(GAIN_BED)
                if (b.playState != AudioTrack.PLAYSTATE_PLAYING) b.play()
            } else if (b.playState == AudioTrack.PLAYSTATE_PLAYING) b.pause()
        }
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

    fun chime() = play("chime", GAIN_CHIME)

    /**
     * Called every frame from the scene. [dog] is null outside a scene with a dog. Steps follow the walk, the pant
     * runs while the dog waits / plays with its mouth open, the park goes on around you.
     */
    fun ambient(nowMs: Long, dog: DogCue?) {
        if (nextParkMs == 0L) {
            nextParkMs = nowMs + 6000
            nextBirdMs = nowMs + 14000
        }
        if (dog != null) dogSounds(nowMs, dog)
        if (nowMs >= nextParkMs) {
            nextParkMs = nowMs + 15000 + rnd.nextInt(30000)
            val side = if (rnd.nextBoolean()) -1f else 1f
            val pan = side * (0.4f + 0.5f * rnd.nextFloat())
            if (rnd.nextInt(3) < 2) play("bark${rnd.nextInt(3)}", GAIN_BARK * (0.7f + 0.3f * rnd.nextFloat()), pan, 0.94f + 0.12f * rnd.nextFloat())
            else play("voices${rnd.nextInt(2)}", GAIN_VOICES, pan)
        }
        if (nowMs >= nextBirdMs) {
            nextBirdMs = nowMs + 20000 + rnd.nextInt(20000)
            if (birdsOn) play("bird", GAIN_BIRD, rnd.nextFloat() * 1.6f - 0.8f, 0.9f + 0.2f * rnd.nextFloat())
        }
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
            if (stepN % 3 == 0) play("tags${rnd.nextInt(3)}", GAIN_TAGS * near, d.pan, 0.97f + 0.06f * rnd.nextFloat())
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

    /** The bed loop: 16-bit mono PCM out of the WAV, looped in a static AudioTrack (no gap at the seam). */
    private fun loadBed(attrs: AudioAttributes): AudioTrack {
        val bytes = context.assets.open("sound/bed.wav").use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var off = 12
        var rate = 32000
        var data = 0
        var len = 0
        while (off + 8 <= bytes.size) {
            val id = String(bytes, off, 4)
            val size = bb.getInt(off + 4)
            if (id == "fmt ") rate = bb.getInt(off + 12)
            if (id == "data") {
                data = off + 8
                len = minOf(size, bytes.size - data)
                break
            }
            off += 8 + size + (size and 1)
        }
        val pcm = bytes.copyOfRange(data, data + len)
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val t = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt).setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size).build()
        t.write(pcm, 0, pcm.size)
        t.setLoopPoints(0, pcm.size / 2, -1)
        return t
    }

    companion object {
        // playback gains, linear (tools/make_sounds.py GAINS)
        const val GAIN_BED = 0.20f
        const val GAIN_PANT = 0.50f
        const val GAIN_SNIFF = 0.45f
        const val GAIN_STEP = 0.40f
        const val GAIN_TAGS = 0.24f
        const val GAIN_SHAKE = 0.45f
        const val GAIN_BARK = 0.12f
        const val GAIN_VOICES = 0.11f
        const val GAIN_WUF = 0.25f
        const val GAIN_CHIME = 0.45f
        const val GAIN_BIRD = 0.035f
        private const val PANT_MS = 2150L
        private val SQRT2 = sqrt(2f)
    }
}
