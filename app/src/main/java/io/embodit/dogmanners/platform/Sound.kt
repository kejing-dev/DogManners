package io.embodit.dogmanners.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.RandomAccessFile
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The app's sounds (design audio): all synthesised here at start-up (no files to license, SOURCES.md): far birds
 * (-24 dB, a chirp every few seconds), a soft pant, a fur-shake rustle, one short low "wuf" (L6 wrong answer, -12 dB),
 * a soft result chime. No growls, no barking runs. Mute is the P1 switch (remembered, D15).
 */
class Sound(private val context: Context) {
    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()
    private val rnd = Random(7)
    var on = true
    private var nextBirdMs = 0L

    fun start() {
        if (pool != null) return
        val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        pool = p
        val dir = File(context.cacheDir, "snd").also { it.mkdirs() }
        fun make(name: String, pcm: ShortArray) {
            val f = File(dir, "$name.wav")
            writeWav(f, pcm)
            ids[name] = p.load(f.absolutePath, 1)
        }
        make("bird", bird())
        make("pant", pant())
        make("shake", shake())
        make("wuf", synthWuf())
        make("chime", synthChime())
        DogLog.i("SOUND ready ${ids.keys}")
    }

    fun release() {
        runCatching { pool?.release() }
        pool = null
        ids.clear()
    }

    private fun play(name: String, gain: Float) {
        if (!on) return
        val p = pool ?: return
        val id = ids[name] ?: return
        p.play(id, gain, gain, 1, 0, 1f)
    }

    fun chime() = play("chime", 0.5f)

    fun wuf() = play("wuf", 0.25f) // -12 dB

    fun pantOnce() = play("pant", 0.18f)

    fun shakeOnce() = play("shake", 0.3f)

    /** Called every frame: a far bird now and then (-24 dB). */
    fun ambient(nowMs: Long) {
        if (nextBirdMs == 0L) nextBirdMs = nowMs + 2500
        if (nowMs < nextBirdMs) return
        nextBirdMs = nowMs + 3500 + rnd.nextInt(5000)
        play("bird", 0.063f)
    }

    // ---------------------------------------------------------------------------------------- synthesis (peak -1 dBFS)

    private fun bird(): ShortArray {
        val sr = RATE
        val out = FloatArray((sr * 0.55).toInt())
        var t0 = 0
        repeat(3) { k ->
            val len = (sr * 0.09).toInt()
            var ph = 0.0
            for (i in 0 until len) {
                val u = i.toDouble() / len
                val f = 3200 + 1400 * sin(PI * u) + 300 * k
                ph += 2 * PI * f / sr
                val env = sin(PI * u)
                if (t0 + i < out.size) out[t0 + i] += (env * sin(ph)).toFloat()
            }
            t0 += (sr * 0.15).toInt()
        }
        return norm(out)
    }

    private fun pant(): ShortArray {
        val sr = RATE
        val out = FloatArray((sr * 0.8).toInt())
        var lp = 0f
        for (i in out.indices) {
            val t = i.toDouble() / sr
            val env = (sin(2 * PI * t / 0.4).let { if (it > 0) it else 0.0 }).toFloat()
            val n = rnd.nextFloat() * 2 - 1
            lp += (n - lp) * 0.15f
            out[i] = lp * env
        }
        return norm(out)
    }

    private fun shake(): ShortArray {
        val sr = RATE
        val out = FloatArray((sr * 1.1).toInt())
        var lp = 0f
        for (i in out.indices) {
            val t = i.toDouble() / sr
            val env = (sin(PI * t / 1.1) * (0.6 + 0.4 * sin(2 * PI * 8.4 * t))).toFloat()
            val n = rnd.nextFloat() * 2 - 1
            lp += (n - lp) * 0.35f
            out[i] = lp * env
        }
        return norm(out)
    }

    private fun synthWuf(): ShortArray {
        val sr = RATE
        val out = FloatArray((sr * 0.22).toInt())
        var ph = 0.0
        var lp = 0f
        for (i in out.indices) {
            val t = i.toDouble() / sr
            val f = 260 - 120 * t / 0.22
            ph += 2 * PI * f / sr
            val env = (1 - exp(-t * 80)) * exp(-t * 12)
            val n = rnd.nextFloat() * 2 - 1
            lp += (n - lp) * 0.2f
            out[i] = ((sin(ph) + 0.35 * sin(2 * ph) + 0.4 * lp) * env).toFloat()
        }
        return norm(out)
    }

    private fun synthChime(): ShortArray {
        val sr = RATE
        val out = FloatArray((sr * 0.9).toInt())
        for ((k, f) in listOf(880.0, 1318.5).withIndex()) {
            val start = (k * 0.12 * sr).toInt()
            for (i in 0 until out.size - start) {
                val t = i.toDouble() / sr
                out[start + i] += (sin(2 * PI * f * t) * exp(-t * 5)).toFloat()
            }
        }
        return norm(out)
    }

    private fun norm(x: FloatArray): ShortArray {
        var peak = 1e-6f
        for (v in x) peak = maxOf(peak, kotlin.math.abs(v))
        val k = PEAK / peak
        return ShortArray(x.size) { (x[it] * k * 32767f).toInt().coerceIn(-32767, 32767).toShort() }
    }

    private fun writeWav(f: File, pcm: ShortArray) {
        RandomAccessFile(f, "rw").use { r ->
            r.setLength(0)
            val data = pcm.size * 2
            fun le32(v: Int) { r.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())) }
            fun le16(v: Int) { r.write(byteArrayOf(v.toByte(), (v shr 8).toByte())) }
            r.writeBytes("RIFF"); le32(36 + data); r.writeBytes("WAVEfmt "); le32(16); le16(1); le16(1); le32(RATE); le32(RATE * 2); le16(2); le16(16)
            r.writeBytes("data"); le32(data)
            val b = ByteArray(data)
            for (i in pcm.indices) { b[2 * i] = pcm[i].toInt().toByte(); b[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
            r.write(b)
        }
    }

    companion object {
        const val RATE = 22050
        /** -1 dBFS */
        const val PEAK = 0.891f
    }
}
