"""Offline sound design for Dog Manners (v0.1.2): the park is about dogs, not birds (profile B12).

Everything is synthesised here (no recordings to license) and written to app/src/main/assets/sound/*.wav
(32 kHz mono 16-bit, every file normalised to -1 dBFS peak; loudness is set by the playback gains in
platform/Sound.kt, mirrored in GAINS below). A QA mix of a dog walking up the path is rendered with those gains:

  python tools/make_sounds.py [QA_DIR]     # QA_DIR default ../spatial-app-factory/apps/dog-manners/qa

Sounds: park bed loop (leaves + far people talking + far dogs), pant, sniff, paw steps on the path (4), collar
tags / bell (3), fur shake (with the tags), far barks (3 dogs), passers-by talking (2), soft near "wuf" (L6),
result chime, a far bird (decoration only, off with the 鸟叫 switch).
"""
import os
import sys
import wave

import numpy as np
from scipy import signal

SR = 32000
PEAK = 10 ** (-1 / 20)
rng = np.random.default_rng(20261102)
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "sound")

# playback gains used by platform/Sound.kt (linear, applied to the -1 dBFS files)
GAINS = {
    "bed": 0.20, "pant": 0.50, "sniff": 0.45, "step": 0.40, "tags": 0.24, "shake": 0.45, "bark": 0.12,
    "voices": 0.11, "wuf": 0.25, "chime": 0.45, "bird": 0.035,
}


# ------------------------------------------------------------------------------------------------- helpers

def t_(n):
    return np.arange(n) / SR


def secs(s):
    return int(round(s * SR))


def noise(n):
    return rng.standard_normal(n)


def pink(n):
    w = np.fft.rfft(noise(n))
    f = np.fft.rfftfreq(n, 1 / SR)
    f[0] = f[1]
    return np.fft.irfft(w / np.sqrt(f), n)


def bp(x, lo, hi, order=2):
    sos = signal.butter(order, [lo, hi], "bandpass", fs=SR, output="sos")
    return signal.sosfilt(sos, x)


def lp(x, hi, order=2):
    return signal.sosfilt(signal.butter(order, hi, "lowpass", fs=SR, output="sos"), x)


def hp(x, lo, order=2):
    return signal.sosfilt(signal.butter(order, lo, "highpass", fs=SR, output="sos"), x)


def reson(x, f, bw):
    r = np.exp(-np.pi * bw / SR)
    a = [1, -2 * r * np.cos(2 * np.pi * f / SR), r * r]
    return signal.lfilter([1 - r], a, x)


def formants(x, fs_bw, gains=None):
    """Parallel formant bank (static)."""
    out = np.zeros_like(x)
    for i, (f, bw) in enumerate(fs_bw):
        g = 1.0 if gains is None else gains[i]
        out += g * reson(x, f, bw)
    return out


def formants_tv(x, tracks, bws, block=160):
    """Time-varying parallel formants: tracks = list of per-sample frequency arrays."""
    out = np.zeros_like(x)
    for k, (tr, bw) in enumerate(zip(tracks, bws)):
        zi = np.zeros(2)
        y = np.zeros_like(x)
        for s in range(0, len(x), block):
            f = float(np.mean(tr[s:s + block]))
            r = np.exp(-np.pi * bw / SR)
            a = [1, -2 * r * np.cos(2 * np.pi * f / SR), r * r]
            y[s:s + block], zi = signal.lfilter([1 - r], a, x[s:s + block], zi=zi)
        out += y * (1.0 / (k + 1) ** 0.6)
    return out


def glottal(f0, rolloff=1.3, jitter=0.01):
    """Band-limited voiced source along an f0 contour (per sample)."""
    f0 = f0 * (1 + jitter * lp(noise(len(f0)), 30))
    ph = 2 * np.pi * np.cumsum(f0) / SR
    out = np.zeros(len(f0))
    nmax = int(SR / 2 / max(60.0, float(f0.min())))
    for n in range(1, min(nmax, 70) + 1):
        a = 1 / n ** rolloff
        alive = (n * f0 < SR / 2 - 500).astype(float)
        out += a * alive * np.sin(n * ph)
    return out


def adsr(n, a, d, sustain_level=0.0, curve=4.0):
    e = np.ones(n)
    na = max(1, secs(a))
    e[:na] = np.linspace(0, 1, na) ** 1.5
    rest = n - na
    if rest > 0:
        tt = np.linspace(0, 1, rest)
        e[na:] = sustain_level + (1 - sustain_level) * np.exp(-curve * tt * (n / SR) / max(d, 1e-3))
    return e


def reverb_ir(length=1.4, tau=0.32, bright=3500):
    n = secs(length)
    ir = noise(n) * np.exp(-t_(n) / tau)
    ir = lp(ir, bright)
    ir[:secs(0.012)] = 0
    return ir / np.sqrt(np.sum(ir ** 2))


IR_FAR = reverb_ir(1.6, 0.38, 2600)
IR_NEAR = reverb_ir(0.6, 0.09, 6000)


def distance(x, metres, ir=IR_FAR):
    """Air absorption + more reverb with distance (loudness is set by the playback gain)."""
    cut = float(np.clip(9000 / (1 + metres / 12), 1200, 9000))
    dry = lp(x, cut)
    wet = signal.fftconvolve(dry, ir)[: len(dry) + len(ir) - 1]
    dry = np.concatenate([dry, np.zeros(len(wet) - len(dry))])
    mix = min(0.85, 0.15 + metres / 60)
    return (1 - mix) * dry + mix * wet * 0.6


def fade(x, a=0.005, r=0.02):
    x = x.copy()
    na, nr = secs(a), secs(r)
    if na:
        x[:na] *= np.linspace(0, 1, na)
    if nr:
        x[-nr:] *= np.linspace(1, 0, nr)
    return x


def norm(x):
    x = x - np.mean(x)
    p = np.max(np.abs(x)) + 1e-12
    return x * (PEAK / p)


def put(buf, at, x, g=1.0):
    i = secs(at)
    if i >= len(buf):
        return
    m = min(len(x), len(buf) - i)
    buf[i:i + m] += g * x[:m]


def write(path, x):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    pcm = np.clip(np.round(x * 32767), -32767, 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def db(v):
    return 20 * np.log10(max(v, 1e-9))


# ------------------------------------------------------------------------------------------------- voices (speech-like)

VOWELS = {  # F1, F2, F3 (adult average)
    "a": (750, 1250, 2550), "e": (480, 1900, 2550), "i": (320, 2250, 2900), "o": (520, 950, 2450),
    "u": (360, 900, 2300), "ae": (650, 1700, 2500), "er": (470, 1350, 1700),
}


def talker(dur, f0_base, female, rate=4.6):
    """One person talking (prosody, syllables, pauses); not words, but it reads as speech from a few metres off."""
    n = secs(dur)
    tt = t_(n)
    keys = list(VOWELS)
    # syllable grid with phrase pauses
    env = np.zeros(n)
    tracks = [np.zeros(n) for _ in range(3)]
    f0 = np.full(n, f0_base, dtype=float)
    s = rng.uniform(0, 0.3)
    k_scale = 1.17 if female else 1.0
    cur = VOWELS[keys[0]]
    while s < dur:
        phrase = rng.uniform(0.9, 2.6)
        pe = min(dur, s + phrase)
        # declination over the phrase, a rise at the end of some phrases
        i0, i1 = secs(s), secs(pe)
        if i1 > i0:
            u = np.linspace(0, 1, i1 - i0)
            rise = 0.18 if rng.random() < 0.3 else -0.05
            f0[i0:i1] = f0_base * (1.12 - 0.22 * u + rise * np.clip((u - 0.8) / 0.2, 0, 1))
        while s < pe:
            syl = rng.uniform(0.75, 1.3) / rate
            i0, i1 = secs(s), min(n, secs(s + syl))
            if i1 <= i0:
                break
            m = i1 - i0
            v = VOWELS[keys[rng.integers(len(keys))]]
            for j in range(3):
                tracks[j][i0:i1] = np.linspace(cur[j], v[j], m) * k_scale
            cur = v
            e = np.sin(np.pi * np.linspace(0, 1, m)) ** 0.8 * rng.uniform(0.55, 1.0)
            env[i0:i1] = np.maximum(env[i0:i1], e)
            f0[i0:i1] *= 1 + 0.06 * np.sin(np.pi * np.linspace(0, 1, m)) * rng.choice([-1, 1])
            s += syl
        s = pe + rng.uniform(0.35, 1.1)
    for j in range(3):  # fill gaps with neighbours to avoid zero formants
        tr = tracks[j]
        z = tr == 0
        if z.any():
            idx = np.where(~z, np.arange(n), 0)
            np.maximum.accumulate(idx, out=idx)
            tr[:] = tr[idx]
            tr[tr == 0] = VOWELS["a"][j]
    src = glottal(f0, rolloff=1.5 if female else 1.35, jitter=0.02) + 0.05 * noise(n)
    y = formants_tv(src, tracks, (90, 120, 180))
    # consonant-ish noise bursts at syllable starts
    burst = hp(noise(n), 2500) * (np.abs(np.diff(env, prepend=0)) > 0.002) * 0.5
    y = y + 0.08 * lp(burst, 7000)
    env = lp(env, 40)
    return y * np.clip(env, 0, 1)


def voices(dur, people, metres):
    mix = np.zeros(secs(dur) + len(IR_FAR))
    for female, f0 in people:
        v = talker(dur, f0, female)
        v = v / (np.max(np.abs(v)) + 1e-9)
        mix[: len(v)] += v * rng.uniform(0.6, 1.0)
    return distance(mix[: secs(dur)], metres)


# ------------------------------------------------------------------------------------------------- dog sounds

def bark(f0_start, f0_end, dur, form, breath=0.15):
    n = secs(dur)
    u = np.linspace(0, 1, n)
    f0 = f0_start + (f0_end - f0_start) * u ** 0.7
    f0 *= 1 + 0.25 * np.exp(-u * 12)  # the sharp onset of a bark
    src = glottal(f0, rolloff=1.1, jitter=0.04) + breath * noise(n)
    y = formants(src, form, (1.0, 0.8, 0.45))
    env = adsr(n, 0.006, dur * 0.35, 0.0, 3.0)
    return fade(y * env, 0.002, 0.01)


DOGS = {  # (f0 start, f0 end, length s, formants)
    "medium": (520, 380, 0.17, ((780, 160), (1550, 200), (2900, 260))),
    "big": (300, 210, 0.24, ((560, 140), (1150, 180), (2450, 240))),
    "small": (900, 700, 0.11, ((1100, 200), (2300, 260), (3600, 320))),
}


def far_bark(kind, count, metres):
    f0a, f0b, d, form = DOGS[kind]
    gap = {"medium": 0.32, "big": 0.45, "small": 0.2}[kind]
    out = np.zeros(secs(count * (d + gap) + 0.3))
    at = 0.05
    for i in range(count):
        k = rng.uniform(0.92, 1.06)
        b = bark(f0a * k, f0b * k, d * rng.uniform(0.9, 1.1), form)
        put(out, at, b, rng.uniform(0.7, 1.0))
        at += d + gap * rng.uniform(0.8, 1.25)
    return distance(out, metres)


def pant_cycle(n_breaths=6, rate=2.9):
    """Relaxed open-mouth panting: out-breath "ha" (lower, stronger), in-breath "hh" (higher, softer)."""
    period = 1 / rate
    out = np.zeros(secs(n_breaths * period + 0.15))
    for i in range(n_breaths):
        for half, (lo, hi, g, f) in enumerate(((500, 3600, 1.0, (1050, 1900, 3100)), (900, 5200, 0.55, (1400, 2500, 3800)))):
            L = secs(period * (0.46 if half == 0 else 0.38) * rng.uniform(0.9, 1.1))
            x = noise(L)
            x = formants(x, ((f[0], 380), (f[1], 520), (f[2], 700)), (1.0, 0.7, 0.4))
            x = bp(x, lo, hi)
            e = np.sin(np.pi * np.linspace(0, 1, L)) ** (1.4 if half == 0 else 2.0)
            put(out, i * period + half * period * 0.5, x * e, g * rng.uniform(0.8, 1.0))
    # a little wet tongue click now and then
    for _ in range(2):
        c = hp(noise(secs(0.006)), 3000) * np.exp(-t_(secs(0.006)) * 900)
        put(out, rng.uniform(0.1, n_breaths * period - 0.1), c, 0.25)
    return distance(out, 1.2, IR_NEAR)


def sniff_burst():
    out = np.zeros(secs(1.0))
    at = 0.04
    for _ in range(rng.integers(4, 7)):
        L = secs(rng.uniform(0.045, 0.07))
        x = bp(noise(L), 2200, 7500, 3)
        x = formants(x, ((3200, 900), (5200, 1400)))
        e = np.sin(np.pi * np.linspace(0, 1, L)) ** 0.7
        put(out, at, x * e, rng.uniform(0.7, 1.0))
        at += rng.uniform(0.085, 0.12)
    # one longer exhale through the nose at the end
    L = secs(0.22)
    put(out, at + 0.05, bp(noise(L), 600, 3000) * np.sin(np.pi * np.linspace(0, 1, L)) ** 2, 0.45)
    return distance(out, 1.0, IR_NEAR)


def step(variant):
    """A paw on the packed gravel path: soft pad thump + a few crunchy grains + a nail tick."""
    L = secs(0.09)
    tt = t_(L)
    thump = np.sin(2 * np.pi * rng.uniform(95, 130) * tt) * np.exp(-tt * 70)
    grains = np.zeros(L)
    for _ in range(rng.integers(5, 11)):
        i = int(abs(rng.normal(0.012, 0.01)) * SR)
        if i < L - 40:
            g = noise(40) * np.exp(-np.arange(40) / 6)
            grains[i:i + 40] += g * rng.uniform(0.3, 1.0)
    grains = bp(grains, 1500, 7000)
    tick = np.zeros(L)
    j = secs(rng.uniform(0.0, 0.006))
    tick[j:j + 24] = hp(noise(24), 4000) * np.exp(-np.arange(24) / 4)
    x = 0.9 * thump + 0.5 * grains + 0.25 * tick * (variant % 2)
    return distance(fade(x, 0.001, 0.02), 1.2, IR_NEAR)


def strike(dur, partials, decay):
    n = secs(dur)
    tt = t_(n)
    x = np.zeros(n)
    for f, d, a in zip(partials, decay, (1.0, 0.7, 0.5, 0.35, 0.25)):
        x += a * np.sin(2 * np.pi * f * tt + rng.uniform(0, 6.28)) * np.exp(-tt / d)
    return x


def tags(variant):
    """Two metal ID tags on the collar ring + a small brass bell, shaken by a step."""
    out = np.zeros(secs(0.6))
    tag_a = (3150, 4870, 7260, 9580)
    tag_b = (3620, 5410, 8030, 10400)
    bell = (4180, 6820, 9100)
    at = 0.0
    for i in range(rng.integers(2, 5)):
        which = rng.integers(3)
        k = rng.uniform(0.98, 1.02)
        if which == 0:
            s = strike(0.4, [f * k for f in tag_a], (0.09, 0.06, 0.04, 0.03))
        elif which == 1:
            s = strike(0.4, [f * k for f in tag_b], (0.08, 0.05, 0.035, 0.025))
        else:
            s = strike(0.5, [f * k for f in bell], (0.22, 0.14, 0.09)) * (1 + 0.3 * np.sin(2 * np.pi * 26 * t_(secs(0.5))))
        put(out, at, s, rng.uniform(0.4, 1.0))
        at += rng.uniform(0.018, 0.06)
    return distance(fade(out, 0.001, 0.05), 1.2, IR_NEAR)


def shake():
    """Fur shake: fast rustle that slows down, with the tags rattling."""
    dur = 1.1
    n = secs(dur)
    tt = t_(n)
    rate = 9.5 - 4 * tt / dur
    ph = np.cumsum(rate) / SR
    am = (0.5 + 0.5 * np.sin(2 * np.pi * ph)) ** 2
    fur = bp(pink(n), 700, 6000) * am * np.sin(np.pi * tt / dur) ** 0.6
    fur /= np.max(np.abs(fur)) + 1e-9
    out = np.concatenate([fur, np.zeros(secs(0.5))])
    for k in range(int(dur * 7)):
        if rng.random() < 0.8:
            tg = tags(k)
            put(out, k / 7 + rng.uniform(0, 0.03), tg / (np.max(np.abs(tg)) + 1e-9), 0.35)
    return distance(out, 1.3, IR_NEAR)


def near_wuf():
    f0a, f0b, d, form = 250, 170, 0.2, ((520, 150), (1100, 190), (2400, 260))
    b = bark(f0a, f0b, d, form, breath=0.3)
    return distance(np.concatenate([b, np.zeros(secs(0.1))]), 1.5, IR_NEAR)


def chime():
    n = secs(1.1)
    out = np.zeros(n)
    for k, f in enumerate((659.25, 987.77)):
        s = strike(1.0, (f, f * 2.0, f * 3.01), (0.45, 0.2, 0.1))
        put(out, k * 0.13, s * (0.9 if k else 1.0))
    return fade(out, 0.002, 0.1)


def far_bird():
    out = np.zeros(secs(0.9))
    at = 0.02
    base = rng.uniform(2600, 3400)
    for _ in range(rng.integers(2, 4)):
        L = secs(rng.uniform(0.06, 0.1))
        u = np.linspace(0, 1, L)
        f = base * (1 + 0.35 * np.sin(np.pi * u)) * (1 + 0.04 * np.sin(2 * np.pi * 38 * u))
        x = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.sin(np.pi * u) ** 2
        put(out, at, x, rng.uniform(0.6, 1.0))
        at += rng.uniform(0.12, 0.2)
    return distance(out, 25)


def park_bed(dur=24.0):
    """The loop under everything: leaves in a light wind, people talking far off, the odd far dog. Seamless."""
    xd = 2.0
    n = secs(dur + xd)
    tt = t_(n)
    gust = lp(noise(n), 0.25)
    gust = 0.55 + 0.45 * (gust - gust.min()) / (np.ptp(gust) + 1e-9)
    leaves = bp(pink(n), 350, 4200) * gust
    leaves /= np.max(np.abs(leaves)) + 1e-9
    rustle = bp(noise(n), 2500, 8000) * (lp(np.abs(noise(n)), 3) > 0.9) * 0.4
    talk = voices(dur + xd, [(False, 118), (True, 205), (True, 228), (False, 132), (True, 190)], 22)
    talk = talk[:n] / (np.max(np.abs(talk[:n])) + 1e-9)
    far = np.zeros(n)
    for at, kind, cnt in ((5.5, "medium", 2), (15.0, "small", 3)):
        b = far_bark(kind, cnt, 45)
        put(far, at, b / (np.max(np.abs(b)) + 1e-9))
    bed = 0.22 * lp(leaves, 2500) + 0.05 * rustle + 0.50 * talk + 0.22 * far + 0.05 * lp(pink(n), 180)
    # seamless loop: cross-fade the tail into the head
    m = secs(xd)
    head = bed[:m].copy()
    out = bed[m:].copy()
    w = np.linspace(0, 1, m)
    out[-m:] = out[-m:] * (1 - w) + head * w
    return out


# ------------------------------------------------------------------------------------------------- build

def build():
    s = {}
    s["bed"] = park_bed()
    s["pant"] = pant_cycle()
    s["sniff"] = sniff_burst()
    for i in range(4):
        s[f"step{i}"] = step(i)
    for i in range(3):
        s[f"tags{i}"] = tags(i)
    s["shake"] = shake()
    s["bark0"] = far_bark("medium", 2, 30)
    s["bark1"] = far_bark("big", 1, 38)
    s["bark2"] = far_bark("small", 3, 28)
    s["voices0"] = voices(3.6, [(True, 210), (False, 125)], 9)
    s["voices1"] = voices(3.0, [(False, 112), (True, 195)], 11)
    s["wuf"] = near_wuf()
    s["chime"] = chime()
    s["bird"] = far_bird()
    return {k: norm(v) for k, v in s.items()}


def group(name):
    return "".join(c for c in name if not c.isdigit())


def qa_mix(snd, birds=True, dur=32.0):
    """A dog walks up the path, waits panting, sniffs, shakes; the park goes on around you (app gains)."""
    n = secs(dur)
    mix = np.zeros(n + SR)
    bed = snd["bed"]
    reps = int(np.ceil(n / len(bed))) + 1
    mix[: n] += np.tile(bed, reps)[:n] * GAINS["bed"]
    # walk in: 0..9 s, trot steps getting louder (1/d) as it comes from 8 m to 1.3 m
    t = 0.4
    k = 0
    while t < 9.0:
        d = 8 - (8 - 1.3) * t / 9
        g = min(1.0, 1.3 / d)
        put(mix, t, snd[f"step{k % 4}"], GAINS["step"] * g)
        if k % 3 == 0:
            put(mix, t + 0.01, snd[f"tags{k % 3}"], GAINS["tags"] * g)
        t += 0.27 * rng.uniform(0.92, 1.08)
        k += 1
    # waits and pants (9..20 s)
    t = 9.3
    while t < 20:
        put(mix, t, snd["pant"], GAINS["pant"])
        t += len(snd["pant"]) / SR + rng.uniform(0.1, 0.6)
    put(mix, 20.4, snd["sniff"], GAINS["sniff"])
    put(mix, 21.6, snd["sniff"], GAINS["sniff"] * 0.8)
    put(mix, 24.0, snd["shake"], GAINS["shake"])
    t = 26.0
    while t < dur:
        put(mix, t, snd["pant"], GAINS["pant"])
        t += len(snd["pant"]) / SR + 0.3
    put(mix, 6.5, snd["bark0"], GAINS["bark"])
    put(mix, 17.0, snd["voices0"], GAINS["voices"])
    put(mix, 27.5, snd["bark2"], GAINS["bark"])
    if birds:
        put(mix, 13.0, snd["bird"], GAINS["bird"])
        put(mix, 29.0, snd["bird"], GAINS["bird"])
    put(mix, 30.5, snd["chime"], GAINS["chime"])
    return mix[:n]


def spectrogram_png(x, path, title):
    from PIL import Image, ImageDraw
    f, tt, S = signal.spectrogram(x, SR, nperseg=1024, noverlap=768)
    S = 10 * np.log10(S + 1e-12)
    S = np.clip((S - (S.max() - 80)) / 80, 0, 1)[::-1]
    img = (np.stack([S ** 0.8 * 255, S ** 1.5 * 200, (1 - S) * 60 + S * 40], -1)).astype(np.uint8)
    im = Image.fromarray(img).resize((1400, 420))
    ImageDraw.Draw(im).text((8, 6), title, fill=(255, 255, 255))
    im.save(path)


def main():
    qa = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "..", "..", "spatial-app-factory", "apps", "dog-manners", "qa")
    snd = build()
    os.makedirs(OUT, exist_ok=True)
    for f in os.listdir(OUT):
        if f.endswith(".wav"):
            os.remove(os.path.join(OUT, f))
    rows = []
    for k, v in snd.items():
        write(os.path.join(OUT, f"{k}.wav"), v)
        g = GAINS[group(k)]
        rows.append((k, len(v) / SR, db(np.max(np.abs(v))), db(np.sqrt(np.mean(v ** 2))), db(g), db(np.max(np.abs(v)) * g), db(np.sqrt(np.mean(v ** 2)) * g)))
    os.makedirs(qa, exist_ok=True)
    for birds, name in ((True, "ambience-v012-walkup.wav"), (False, "ambience-v012-walkup-birds-off.wav")):
        m = qa_mix(snd, birds)
        p = db(np.max(np.abs(m)))
        if p > -1:
            m *= PEAK / np.max(np.abs(m))
        write(os.path.join(qa, name), m)
        rows.append((name, len(m) / SR, db(np.max(np.abs(m))), db(np.sqrt(np.mean(m ** 2))), 0, db(np.max(np.abs(m))), db(np.sqrt(np.mean(m ** 2)))))
        if birds:
            spectrogram_png(m, os.path.join(qa, "ambience-v012-walkup-spectrogram.png"), "v0.1.2 walk-up mix: steps+tags 0-9 s, pant 9-20 s, sniff 20-22 s, shake 24 s, far barks 6.5 / 27.5 s, voices 17 s")
    sd = os.path.join(qa, "sounds-v012")
    os.makedirs(sd, exist_ok=True)
    for k, v in snd.items():
        write(os.path.join(sd, f"{k}.wav"), v)
    with open(os.path.join(qa, "sounds-v012.md"), "w", encoding="utf-8") as fh:
        fh.write("| sound | s | file peak dBFS | file RMS dBFS | app gain dB | played peak dBFS | played RMS dBFS |\n|---|---|---|---|---|---|---|\n")
        for r in rows:
            fh.write(f"| {r[0]} | {r[1]:.2f} | {r[2]:.1f} | {r[3]:.1f} | {r[4]:.1f} | {r[5]:.1f} | {r[6]:.1f} |\n")
    for r in rows:
        print(f"{r[0]:34s} {r[1]:6.2f}s peak {r[2]:6.1f} rms {r[3]:6.1f} gain {r[4]:6.1f} -> peak {r[5]:6.1f} rms {r[6]:6.1f}")


if __name__ == "__main__":
    main()
