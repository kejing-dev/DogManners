#!/usr/bin/env python3
"""Builds the three dog GLBs of the app from quander's "Animated Dog, Shiba Inu" (CC BY 4.0).

    python3 tools/make_dog.py            # writes app/src/main/assets/dogs/dog_<coat>.glb + build/anim/*.png + tools/dog_metrics.json

No Blender on the factory Mac, so the clips are keyframed here, on the file's own skeleton (191 joints), in plain numpy:
every pose = the file's standing frame 0 (head turned straight) + rotations about MODEL axes at the joint pivots
(model space: +Y up, head towards +Z, the dog's left = +X). The file's own clips are dropped (shake_0 and sitting_0 are
single-frame poses: shake = "shake a paw", not a fur shake). What we keep from them: sitting_0 (home) and play_dead_0 at
4 s (lying on the side: the sleep base).

Clips written (names = what the Kotlin side asks for):
  sit, walk (0.8 s), wary (6 s: lick at 1.5 s, yawn at 4 s), relaxed (2 s: slow wag, light pant), play (1.6 s: play
  bow, fast wag), shake (1.2 s: fur shake, wary -> relaxed), sniff (2.5 s), eat (2 s: chew), sleep (4 s: breathing)

Coats: red = the author's texture; black_tan and cream recolour the base-colour texture (same rule as the design
station's coat.py). Material forced to metallic 0, alphaMode OPAQUE (profile D24).
"""
import io
import json
import math
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from glbkit import Model, qaxis, qinv, qmul, qnorm, write_glb, render_points  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
SRC = os.environ.get("DOG_SRC", "/Users/kejing/Documents/GitHub/spatial-app-factory/apps/dog-manners/dogmodel/raw/9abfce885a834399b2c3ccaed51cd474.glb")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "dogs")
SHOTS = os.environ.get("DOG_SHOTS", os.path.join(ROOT, "build", "anim"))
FPS = 30

X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])

m = Model(SRC)
SKIN = list(m.js["skins"][0]["joints"])


def J(name):
    return m.find(name)


def copy_pose(p):
    return {k: (t.copy(), r.copy(), s.copy()) for k, (t, r, s) in p.items()}


def rot_world(pose, joint, axis, deg):
    """Rotates [joint] (and its subtree) about a model-space axis through the joint's pivot."""
    if abs(deg) < 1e-6:
        return
    i = J(joint) if isinstance(joint, str) else joint
    W = m.world(pose)
    P = m.world_rot(W, m.parent[i])
    q = qaxis(axis, deg)
    t, r, s = pose[i]
    pose[i] = (t, qnorm(qmul(qmul(qmul(qinv(P), q), P), r)), s)


def move_world(pose, joint, d):
    """Moves [joint] by a model-space offset (file units)."""
    i = J(joint)
    W = m.world(pose)
    Pm = W[m.parent[i]][:3, :3]
    t, r, s = pose[i]
    pose[i] = (t + np.linalg.solve(Pm, np.asarray(d, dtype=np.float64)), r, s)


def pos(pose, joint):
    return m.world(pose)[J(joint)][:3, 3].copy()


# ------------------------------------------------------------------ base poses

STAND = m.sample("0|standing_0", 0.0)
SIT = m.sample("0|sitting_0", 0.0)
LIE = m.sample("0|play_dead_0", 4.0)
EARS = ["L_ear_base_jnt", "R_ear_base_jnt"]
TAIL = ["tail_%d_jnt" % k for k in range(1, 8)]


def straight_head(pose):
    """The standing frame looks to its left: turn the neck so the nose is on the body's centre line."""
    for _ in range(3):
        n, c = pos(pose, "nose_jnt"), pos(pose, "neck_base_jnt")
        yaw = math.degrees(math.atan2(n[0] - pos(pose, "COG_jnt")[0], n[2] - c[2]))
        rot_world(pose, "neck_base_jnt", Y, -yaw * 0.6)
        rot_world(pose, "head_jnt", Y, -yaw * 0.4)


BASE = copy_pose(STAND)
straight_head(BASE)
FEET = {k: pos(BASE, k) for k in ("L_hand_jnt", "R_hand_jnt", "L_foot_jnt", "R_foot_jnt")}


def ears(pose, back):
    """back > 0 flattens the ears backwards, < 0 pricks them forwards."""
    for e in EARS:
        rot_world(pose, e, X, -back)
    for e in ("L_ear_tip_jnt", "R_ear_tip_jnt"):
        rot_world(pose, e, X, -back * 0.35)


def tail_down(pose, k):
    """k in [0, 1]: 0 = the curl over the back, 1 = hanging low and straight."""
    rot_world(pose, "tail_1_jnt", X, -100 * k)
    for i, t in enumerate(TAIL[1:6]):
        rot_world(pose, t, X, -32 * k)


def tail_wag(pose, deg):
    rot_world(pose, "tail_1_jnt", Y, deg)
    rot_world(pose, "tail_3_jnt", Y, deg * 0.5)


def jaw(pose, deg):
    rot_world(pose, "jaw_jnt", X, deg)


def tongue(pose, deg):
    rot_world(pose, "tongue_1_jnt", X, deg)
    rot_world(pose, "tongue_3_jnt", X, deg * 0.6)


def head_down(pose, neck, head):
    rot_world(pose, "neck_base_jnt", X, neck)
    rot_world(pose, "head_jnt", X, head)


def plant(pose, leg, target, iters=60):
    """Two-angle search (upper joint, middle joint, about X) so that the paw lands on [target] (model units)."""
    upper, mid, paw = {
        "LF": ("L_shoulder_jnt", "L_elbow_jnt", "L_hand_jnt"),
        "RF": ("R_shoulder_jnt", "R_elbow_jnt", "R_hand_jnt"),
        "LB": ("L_hip_jnt", "L_knee_jnt", "L_foot_jnt"),
        "RB": ("R_hip_jnt", "R_knee_jnt", "R_foot_jnt"),
    }[leg]
    target = np.asarray(target, dtype=np.float64)

    def err(p):
        d = pos(p, paw) - target
        return d[1] ** 2 + d[2] ** 2

    step = 8.0
    best = copy_pose(pose)
    e0 = err(best)
    for _ in range(iters):
        improved = False
        for j in (upper, mid):
            for sgn in (1, -1):
                trial = copy_pose(best)
                rot_world(trial, j, X, sgn * step)
                e = err(trial)
                if e < e0:
                    best, e0, improved = trial, e, True
        if not improved:
            step *= 0.5
            if step < 0.05:
                break
    pose.clear()
    pose.update(best)


def relaxed_face(p):
    ears(p, 12)
    jaw(p, 10)
    tongue(p, 6)


def wary_pose(lick=0.0, yawn=0.0):
    p = copy_pose(BASE)
    move_world(p, "COG_jnt", [0, -1.5, -3.0])           # weight shifts back, a little lower
    rot_world(p, "COG_jnt", X, -3)
    head_down(p, 16, 8)
    ears(p, 55)
    tail_down(p, 0.95)
    jaw(p, -3 + 28 * yawn)
    tongue(p, 25 * lick + 10 * yawn)
    for leg in ("LF", "RF", "LB", "RB"):
        key = {"LF": "L_hand_jnt", "RF": "R_hand_jnt", "LB": "L_foot_jnt", "RB": "R_foot_jnt"}[leg]
        plant(p, leg, FEET[key], iters=25)
    return p


def bump(t, at, width):
    """0..1..0 bump centred at [at]."""
    u = (t - at) / width
    return max(0.0, math.cos(min(1.0, abs(u)) * math.pi / 2)) ** 2 if abs(u) < 1 else 0.0


# ------------------------------------------------------------------ clips (pose(t) for t in [0, length])

def clip_sit(t):
    p = copy_pose(SIT)
    rot_world(p, "chest_jnt", X, 0.8 * math.sin(2 * math.pi * t / 4.0))
    return p


def clip_walk(t):
    L = 0.8
    ph = 2 * math.pi * t / L
    p = copy_pose(BASE)
    relaxed_face(p)
    move_world(p, "COG_jnt", [0, 0.8 * math.cos(2 * ph), 0])
    # diagonal pairs: LF with RB, RF with LB
    for leg, phase in (("L_shoulder_jnt", 0), ("R_hip_jnt", 0), ("R_shoulder_jnt", math.pi), ("L_hip_jnt", math.pi)):
        s = math.sin(ph + phase)
        rot_world(p, leg, X, -24 * s)
        lift = max(0.0, math.cos(ph + phase))           # knee / elbow fold while the paw swings forward
        mid = leg.replace("shoulder", "elbow").replace("hip", "knee")
        rot_world(p, mid, X, (32 if "elbow" in mid else -32) * lift)
    tail_wag(p, 8 * math.sin(ph))
    rot_world(p, "neck_base_jnt", X, 2 * math.sin(2 * ph))
    return p


WARY0 = None


def clip_wary(t):
    global WARY0
    if WARY0 is None:
        WARY0 = wary_pose(0.0, 0.0)
    lick = bump(t, 1.5, 0.35)
    yawn = bump(t, 4.0, 0.7)
    p = copy_pose(WARY0)
    jaw(p, 28 * yawn)
    tongue(p, 25 * lick + 10 * yawn)
    rot_world(p, "chest_jnt", X, 0.6 * math.sin(2 * math.pi * t / 3.0))
    return p


def clip_relaxed(t):
    p = copy_pose(BASE)
    relaxed_face(p)
    jaw(p, 3 * math.sin(2 * math.pi * t * 3))           # light pant
    tail_wag(p, 18 * math.sin(2 * math.pi * t / 1.0))   # slow, sweeping
    rot_world(p, "spine_2_jnt", Y, 2.5 * math.sin(2 * math.pi * t / 1.0))
    return p


def bow_pose(depth):
    p = copy_pose(BASE)
    rot_world(p, "COG_jnt", X, 16 * depth)                # chest down, rump up
    move_world(p, "COG_jnt", [0, -6 * depth, 0])
    head_down(p, -14 * depth, -4 * depth)
    ears(p, -8)
    jaw(p, 18)
    tongue(p, 10)
    lf, rf = FEET["L_hand_jnt"].copy(), FEET["R_hand_jnt"].copy()
    lf[2] += 12 * depth
    rf[2] += 12 * depth
    plant(p, "LF", lf)
    plant(p, "RF", rf)
    plant(p, "LB", FEET["L_foot_jnt"])
    plant(p, "RB", FEET["R_foot_jnt"])
    return p


BOWS = {}


def clip_play(t):
    depth = round((0.85 + 0.15 * math.sin(2 * math.pi * t / 1.6)) * 20) / 20
    if depth not in BOWS:
        BOWS[depth] = bow_pose(depth)
    p = copy_pose(BOWS[depth])
    tail_wag(p, 26 * math.sin(2 * math.pi * t / 0.32))
    rot_world(p, "tail_1_jnt", X, 10)
    return p


def clip_shake(t):
    p = copy_pose(BASE)
    relaxed_face(p)
    env = math.sin(math.pi * min(1.0, t / 1.2))
    w = 2 * math.pi * 4.2 * t
    rot_world(p, "spine_1_jnt", Z, 9 * env * math.sin(w - 1.0))
    rot_world(p, "chest_jnt", Z, 11 * env * math.sin(w - 0.5))
    rot_world(p, "neck_base_jnt", Z, 14 * env * math.sin(w))
    rot_world(p, "head_jnt", Z, 16 * env * math.sin(w + 0.4))
    for e in EARS:
        rot_world(p, e, Z, 18 * env * math.sin(w + 0.9))
    return p


def clip_sniff(t):
    p = copy_pose(BASE)
    ears(p, 6)
    head_down(p, -6, -10)                                # neck stretched up towards your hand
    move_world(p, "COG_jnt", [0, 0, 2.5])
    rot_world(p, "head_jnt", X, 2.5 * math.sin(2 * math.pi * 5 * t))
    tail_wag(p, 10 * math.sin(2 * math.pi * t / 1.25))
    return p


EAT0 = None


def clip_eat(t):
    global EAT0
    if EAT0 is None:
        p = copy_pose(BASE)
        ears(p, 10)
        head_down(p, 42, 22)
        move_world(p, "COG_jnt", [0, -2, 0])
        for leg in ("LF", "RF"):
            plant(p, leg, FEET["L_hand_jnt" if leg == "LF" else "R_hand_jnt"], iters=20)
        EAT0 = p
    p = copy_pose(EAT0)
    jaw(p, 4 + 7 * max(0.0, math.sin(2 * math.pi * 2 * t)))
    return p


def clip_sleep(t):
    p = copy_pose(LIE)
    b = math.sin(2 * math.pi * t / 4.0)
    rot_world(p, "chest_jnt", X, 1.2 * b)
    rot_world(p, "spine_2_jnt", X, 0.8 * b)
    return p


CLIPS = [
    ("sit", 4.0, clip_sit, 10),
    ("walk", 0.8, clip_walk, FPS),
    ("wary", 6.0, clip_wary, 15),
    ("relaxed", 2.0, clip_relaxed, FPS),
    ("play", 1.6, clip_play, 15),
    ("shake", 1.2, clip_shake, FPS),
    ("sniff", 2.5, clip_sniff, 15),
    ("eat", 2.0, clip_eat, 15),
    ("sleep", 4.0, clip_sleep, 5),
]


# ------------------------------------------------------------------ GLB assembly

def build_glb(coat, clip_frames):
    js = json.loads(json.dumps(m.js))
    binc = m.bin
    # keep the mesh / skin accessors (0..6) and the two images; drop the file's clips
    keep_views = sorted({js["accessors"][i]["bufferView"] for i in range(7)} | {im["bufferView"] for im in js["images"]})
    out = bytearray()
    views = []
    remap = {}
    for k in keep_views:
        bv = js["bufferViews"][k]
        data = bytes(binc[bv.get("byteOffset", 0): bv.get("byteOffset", 0) + bv["byteLength"]])
        if k == js["images"][0]["bufferView"]:
            data = recolour(data, coat)
        while len(out) % 4:
            out.append(0)
        nv = {kk: vv for kk, vv in bv.items() if kk != "byteOffset"}
        nv["byteOffset"] = len(out)
        nv["byteLength"] = len(data)
        out += data
        remap[k] = len(views)
        views.append(nv)
    accessors = []
    for i in range(7):
        a = dict(js["accessors"][i])
        a["bufferView"] = remap[a["bufferView"]]
        accessors.append(a)
    for im in js["images"]:
        im["bufferView"] = remap[im["bufferView"]]

    def add(arr, typ, minmax=False):
        arr = np.ascontiguousarray(arr, dtype=np.float32)
        while len(out) % 4:
            out.append(0)
        views.append({"buffer": 0, "byteOffset": len(out), "byteLength": arr.nbytes})
        out.extend(arr.tobytes())
        a = {"bufferView": len(views) - 1, "componentType": 5126, "count": int(arr.shape[0]), "type": typ}
        if minmax:
            a["min"] = [float(arr.min())]
            a["max"] = [float(arr.max())]
        accessors.append(a)
        return len(accessors) - 1

    anims = []
    for name, (times, frames) in clip_frames.items():
        tin = add(np.array(times)[:, None], "SCALAR", minmax=True)
        samplers, channels = [], []
        for j in SKIN:
            T = np.array([f[j][0] for f in frames])
            R = np.array([f[j][1] for f in frames])
            for k in range(1, len(R)):                    # keep the quaternion hemisphere continuous
                if np.dot(R[k], R[k - 1]) < 0:
                    R[k] = -R[k]
            S = np.array([f[j][2] for f in frames])
            for path, arr, typ in (("translation", T, "VEC3"), ("rotation", R, "VEC4"), ("scale", S, "VEC3")):
                if np.allclose(arr, arr[0], atol=1e-5):
                    ti = add(np.array([[times[0]], [times[-1]]]), "SCALAR", minmax=True)
                    oi = add(np.stack([arr[0], arr[0]]), typ)
                else:
                    ti, oi = tin, add(arr, typ)
                samplers.append({"input": ti, "output": oi, "interpolation": "LINEAR"})
                channels.append({"sampler": len(samplers) - 1, "target": {"node": j, "path": path}})
        anims.append({"name": name, "samplers": samplers, "channels": channels})

    js["accessors"] = accessors
    js["bufferViews"] = views
    js["animations"] = anims
    mat = js["materials"][0]
    pbr = mat.setdefault("pbrMetallicRoughness", {})
    pbr["metallicFactor"] = 0.0
    pbr["roughnessFactor"] = 0.85
    pbr.pop("metallicRoughnessTexture", None)
    mat["alphaMode"] = "OPAQUE"
    # the metallic-roughness image is no longer referenced: drop it so the file stays small
    js["textures"] = js["textures"][:1]
    js["images"] = js["images"][:1]
    js["asset"]["generator"] = "dog-manners tools/make_dog.py (clips keyframed on quander's rig)"
    js["asset"]["copyright"] = "\"Animated Dog, Shiba Inu\" by quander, CC BY 4.0 (sketchfab 9abfce885a834399b2c3ccaed51cd474); coat recolour and clips by Dog Manners"
    return js, out


def recolour(png, coat):
    if coat == "red":
        return png
    im = Image.open(io.BytesIO(png)).convert("RGB")
    rgb = np.asarray(im).astype(np.float64) / 255.0
    mx, mn = rgb.max(2), rgb.min(2)
    sat = np.where(mx > 1e-4, (mx - mn) / np.maximum(mx, 1e-4), 0)
    wgt = np.clip((sat - 0.18) / 0.30, 0, 1)[..., None]
    lum = (0.3 * rgb[..., 0] + 0.59 * rgb[..., 1] + 0.11 * rgb[..., 2])[..., None]
    wgt = wgt * np.clip((lum - 0.12) / 0.08, 0, 1)
    if coat == "black_tan":
        black = np.array([0.035, 0.030, 0.028]) * (0.6 + 1.4 * lum)
        tan = np.array([0.55, 0.32, 0.14]) * (0.5 + lum)
        edge = np.clip(1 - np.abs(wgt - 0.45) / 0.35, 0, 1)
        new = black * wgt + rgb * (1 - wgt)
        new = new * (1 - edge * 0.8) + tan * edge * 0.8
    else:
        cream = np.array([0.82, 0.70, 0.52]) * (0.75 + 0.5 * lum)
        new = cream * wgt + rgb * (1 - wgt)
    outim = Image.fromarray((np.clip(new, 0, 1) * 255).astype(np.uint8))
    buf = io.BytesIO()
    outim.save(buf, format="PNG", optimize=True)
    return buf.getvalue()


def sample_clips():
    res = {}
    for name, length, fn, fps in CLIPS:
        n = max(2, int(round(length * fps)) + 1)
        times = [length * k / (n - 1) for k in range(n)]
        frames = []
        for t in times:
            p = fn(t)
            frames.append(p)
        res[name] = (times, frames)
        print("clip", name, len(times), "keys", flush=True)
    return res


def metrics():
    """Calibration facts in file units for the Kotlin side (D25: one place)."""
    v, _, W = m.skin_vertices(BASE)
    lo, hi = v.min(0), v.max(0)
    nose_tip_z = float(hi[2])
    tail_root = W[J("tail_1_jnt")][:3, 3]
    feet = np.array([W[J(k)][:3, 3] for k in ("L_hand_jnt", "R_hand_jnt", "L_foot_jnt", "R_foot_jnt")])
    origin = np.array([float(W[J("COG_jnt")][0, 3]), float(lo[1]), float(feet[:, 2].mean())])
    sel = (v[:, 2] > 8) & (v[:, 2] < 18) & (np.abs(v[:, 0] - origin[0]) < 12)
    withers = float(v[sel, 1].max())
    head = W[J("head_jnt")][:3, 3]
    out = {
        "box_min": lo.round(3).tolist(), "box_max": hi.round(3).tolist(),
        "ear_tip_y": float(hi[1]),
        "withers_y": withers,
        "nose_tip_z": nose_tip_z, "tail_root_z": float(tail_root[2]),
        "body_len": nose_tip_z - float(tail_root[2]),
        "origin": origin.round(3).tolist(),
        "nose_from_origin": [float(W[J("nose_jnt")][0, 3] - origin[0]), float(W[J("nose_jnt")][1, 3] - origin[1]), nose_tip_z - origin[2]],
        "head_from_origin": (head - origin).round(3).tolist(),
        "forward": "+Z", "up": "+Y",
    }
    return out


def anim_check(clip_frames, coat_png):
    os.makedirs(SHOTS, exist_ok=True)
    im = Image.open(io.BytesIO(coat_png)).convert("RGB")
    tex = np.asarray(im).astype(np.float64)
    uv = m._mesh["uv"] if hasattr(m, "_mesh") else None
    v0, n0, _ = m.skin_vertices(BASE)
    uv = m._mesh["uv"]
    h, w = tex.shape[:2]
    cols = tex[np.clip((uv[:, 1] * h).astype(int), 0, h - 1), np.clip((uv[:, 0] * w).astype(int), 0, w - 1)]
    fit = None
    sheet = []
    for name, (times, frames) in clip_frames.items():
        row = []
        for k in (0, len(frames) // 3, (2 * len(frames)) // 3):
            v, n, _ = m.skin_vertices(frames[k])
            side, _ = render_points(v, n, cols, "side", size=(300, 240), fit=(np.array([-50, -5, 0]), np.array([110, 95, 0])))
            front, _ = render_points(v, n, cols, "front", size=(200, 240), fit=(np.array([-45, -5, 0]), np.array([45, 95, 0])))
            tile = Image.new("RGB", (500, 260), (255, 255, 255))
            tile.paste(side, (0, 20))
            tile.paste(front, (300, 20))
            from PIL import ImageDraw
            ImageDraw.Draw(tile).text((6, 4), "%s t=%.2fs" % (name, times[k]), fill=(0, 0, 0))
            row.append(tile)
            tile.save(os.path.join(SHOTS, "%s_%d.png" % (name, k)))
        sheet.append(row)
    S = Image.new("RGB", (1500, 260 * len(sheet)), (255, 255, 255))
    for r, row in enumerate(sheet):
        for c, tile in enumerate(row):
            S.paste(tile, (c * 500, r * 260))
    S.save(os.path.join(SHOTS, "anim_sheet.png"))


def main():
    os.makedirs(OUT, exist_ok=True)
    met = metrics()
    print(json.dumps(met, indent=1))
    json.dump(met, open(os.path.join(ROOT, "tools", "dog_metrics.json"), "w"), indent=1)
    frames = sample_clips()
    for coat in ("red", "black_tan", "cream"):
        js, binc = build_glb(coat, frames)
        path = os.path.join(OUT, "dog_%s.glb" % coat)
        write_glb(path, js, binc)
        print("wrote", path, os.path.getsize(path))
    if os.environ.get("ANIM_CHECK", "1") == "1":
        bv = m.js["bufferViews"][m.js["images"][0]["bufferView"]]
        png = bytes(m.bin[bv.get("byteOffset", 0): bv.get("byteOffset", 0) + bv["byteLength"]])
        anim_check(frames, png)


if __name__ == "__main__":
    main()
