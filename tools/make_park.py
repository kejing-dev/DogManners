"""The park around the dog, in the dog's style (v0.1.2, profile B12 / D25).

1. Ground textures: painted grass and a packed-gravel path, tileable (periodic noise), JPEG 1024 -> assets/park/.
2. Tripo models (owner, two trees, bench, fence, bowl, dog bed, bush) from RAW (default
   ../spatial-app-factory/apps/dog-manners/parkmodel/raw/<name>.glb) -> assets/park/<name>.glb, each with ONE
   calibration root node baked in (D25): floor centre of the bounding box on the origin, face on +Z, true size in
   metres. Materials forced OPAQUE + metallic 0 (D24, Full Stage has no reflections), images cut to 1024 px (owner
   2048). The owner's own leash, which Tripo let hang to the ground, is cut below the hand; the app draws the leash
   from that hand (OWNER_HAND) to the dog's collar.
3. Writes app/src/main/java/io/embodit/dogmanners/domain/ParkModels.kt (the one place for the props' sizes, the
   owner's hand) and tools/park_metrics.json, plus front / side / top renders and a line-up with the dog into
   ../spatial-app-factory/apps/dog-manners/build/park/.

  python tools/make_park.py [RAW_DIR]
"""
import io
import json
import math
import os
import struct
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import glbkit  # noqa: E402

ROOT = os.path.abspath(os.path.join(HERE, ".."))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "park")
FACTORY_APP = os.path.abspath(os.path.join(ROOT, "..", "spatial-app-factory", "apps", "dog-manners"))
RAW = sys.argv[1] if len(sys.argv) > 1 else os.path.join(FACTORY_APP, "parkmodel", "raw")
RENDERS = os.path.join(FACTORY_APP, "build", "park")
KOTLIN = os.path.join(ROOT, "app", "src", "main", "java", "io", "embodit", "dogmanners", "domain", "ParkModels.kt")

# name: (measure, metres, yaw degrees to turn the file's face onto +Z, max image px, Tripo task id)
MODELS = {
    "owner": ("height", 1.68, 0, 2048, "ee2421c7-6d1e-4c8f-b699-37e669c126d0"),
    "tree_linden": ("height", 5.0, 0, 1024, ""),
    "tree_maple": ("height", 5.6, 0, 1024, ""),
    "bench": ("height", 0.86, 0, 1024, ""),
    "fence": ("height", 1.0, 0, 1024, ""),
    "bowl": ("width", 0.26, 0, 1024, "ccfc9b77-f7df-4415-acc8-10d3058fe1f5"),
    "bed": ("width", 1.0, 0, 1024, "1b9bc284-f675-438a-b90c-005eeea3b598"),
    "bush": ("height", 0.95, 0, 1024, ""),
}


# ------------------------------------------------------------------------------------------------- ground textures

def periodic_noise(n, scale, rng):
    """Tileable noise: random spectrum around [scale] cycles per tile, inverse FFT (periodic by construction)."""
    f = np.fft.fftfreq(n)[:, None] ** 2 + np.fft.fftfreq(n)[None, :] ** 2
    f = np.sqrt(f) * n
    amp = np.exp(-((f - scale) ** 2) / (2 * (scale * 0.6) ** 2))
    amp[0, 0] = 0
    ph = rng.uniform(0, 2 * np.pi, (n, n))
    x = np.real(np.fft.ifft2(amp * np.exp(1j * ph)))
    return (x - x.min()) / (np.ptp(x) + 1e-9)


def grass_texture(n=1024, seed=3):
    rng = np.random.default_rng(seed)
    low = periodic_noise(n, 3, rng)
    mid = periodic_noise(n, 14, rng)
    fine = periodic_noise(n, 70, rng)
    base_a = np.array([112, 140, 72]) / 255  # sunlit grass
    base_b = np.array([62, 92, 46]) / 255  # deeper patches
    dry = np.array([150, 150, 82]) / 255
    t = np.clip(0.55 * low + 0.35 * mid + 0.1 * fine, 0, 1)[..., None]
    col = base_b + (base_a - base_b) * t
    col = col + (dry - col) * (np.clip(low - 0.72, 0, 1) * 1.6)[..., None]
    img = Image.fromarray((np.clip(col, 0, 1) * 255).astype(np.uint8))
    # painted blades: short soft strokes, wrapped so the tile stays seamless
    big = Image.new("RGB", (n * 3, n * 3))
    for i in range(3):
        for j in range(3):
            big.paste(img, (i * n, j * n))
    d = ImageDraw.Draw(big, "RGBA")
    for _ in range(26000):
        x, y = rng.uniform(n, 2 * n), rng.uniform(n, 2 * n)
        ln = rng.uniform(5, 14)
        a = rng.uniform(-0.6, 0.6) - math.pi / 2
        light = rng.uniform(0, 1)
        c = (int(70 + 80 * light), int(110 + 70 * light), int(40 + 35 * light), int(rng.uniform(50, 120)))
        for ox in (-n, 0, n):
            for oy in (-n, 0, n):
                d.line([(x + ox, y + oy), (x + ox + ln * math.cos(a), y + oy + ln * math.sin(a))], fill=c, width=2)
    img = big.crop((n, n, 2 * n, 2 * n)).filter(ImageFilter.GaussianBlur(0.6))
    return img


def path_texture(n=1024, seed=5):
    rng = np.random.default_rng(seed)
    low = periodic_noise(n, 4, rng)
    mid = periodic_noise(n, 20, rng)
    fine = periodic_noise(n, 120, rng)
    earth = np.array([168, 150, 122]) / 255
    dark = np.array([126, 110, 90]) / 255
    t = np.clip(0.5 * low + 0.3 * mid + 0.2 * fine, 0, 1)[..., None]
    col = dark + (earth - dark) * t
    img = Image.fromarray((np.clip(col, 0, 1) * 255).astype(np.uint8))
    big = Image.new("RGB", (n * 3, n * 3))
    for i in range(3):
        for j in range(3):
            big.paste(img, (i * n, j * n))
    d = ImageDraw.Draw(big, "RGBA")
    for _ in range(9000):
        x, y = rng.uniform(n, 2 * n), rng.uniform(n, 2 * n)
        r = rng.uniform(1.5, 6.5)
        g = int(rng.uniform(120, 215))
        c = (g, int(g * 0.93), int(g * 0.82), int(rng.uniform(140, 230)))
        sh = (60, 50, 40, 90)
        for ox in (-n, 0, n):
            for oy in (-n, 0, n):
                d.ellipse([x + ox - r + 1, y + oy - r * 0.8 + 1.5, x + ox + r + 1, y + oy + r * 0.8 + 1.5], fill=sh)
                d.ellipse([x + ox - r, y + oy - r * 0.8, x + ox + r, y + oy + r * 0.8], fill=c)
    return big.crop((n, n, 2 * n, 2 * n)).filter(ImageFilter.GaussianBlur(0.5))


# ------------------------------------------------------------------------------------------------- glTF helpers

def node_matrix(node):
    if "matrix" in node:
        return np.array(node["matrix"], dtype=float).reshape(4, 4).T
    t = np.array(node.get("translation", [0, 0, 0]), dtype=float)
    q = node.get("rotation", [0, 0, 0, 1])
    s = np.array(node.get("scale", [1, 1, 1]), dtype=float)
    x, y, z, w = q
    r = np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ])
    m = np.eye(4)
    m[:3, :3] = r * s[None, :]
    m[:3, 3] = t
    return m


def mesh_instances(js):
    """(mesh index, world matrix) for every mesh node of the default scene."""
    out = []
    scene = js["scenes"][js.get("scene", 0)]

    def walk(i, parent):
        n = js["nodes"][i]
        m = parent @ node_matrix(n)
        if "mesh" in n:
            out.append((n["mesh"], m))
        for c in n.get("children", []):
            walk(c, m)

    for r in scene["nodes"]:
        walk(r, np.eye(4))
    return out


def image_of(js, binc, tex_index):
    tex = js["textures"][tex_index]
    img = js["images"][tex["source"]]
    bv = js["bufferViews"][img["bufferView"]]
    off = bv.get("byteOffset", 0)
    return Image.open(io.BytesIO(bytes(binc[off: off + bv["byteLength"]]))).convert("RGB")


def triangles(js, binc):
    """World-space triangles with their base colour (sampled at the centroid UV) and a handle to cut them."""
    tris, cols, keys = [], [], []
    for mi, m in mesh_instances(js):
        for pi, prim in enumerate(js["meshes"][mi]["primitives"]):
            pos = glbkit.accessor(js, binc, prim["attributes"]["POSITION"])[:, :3]
            pw = (m @ np.c_[pos, np.ones(len(pos))].T).T[:, :3]
            idx = glbkit.accessor(js, binc, prim["indices"]).reshape(-1, 3).astype(np.int64) if "indices" in prim else np.arange(len(pos)).reshape(-1, 3)
            col = np.full((len(idx), 3), 0.7)
            mat = js["materials"][prim["material"]] if "material" in prim else {}
            pbr = mat.get("pbrMetallicRoughness", {})
            factor = np.array(pbr.get("baseColorFactor", [1, 1, 1, 1])[:3])
            if "baseColorTexture" in pbr and "TEXCOORD_0" in prim["attributes"]:
                uv = glbkit.accessor(js, binc, prim["attributes"]["TEXCOORD_0"])[:, :2]
                im = np.asarray(image_of(js, binc, pbr["baseColorTexture"]["index"]), dtype=float) / 255
                h, w = im.shape[:2]
                c = uv[idx].mean(axis=1)
                px = np.clip((c[:, 0] % 1) * (w - 1), 0, w - 1).astype(int)
                py = np.clip((c[:, 1] % 1) * (h - 1), 0, h - 1).astype(int)
                col = im[py, px] * factor
            else:
                col = col * factor
            tris.append(pw[idx])
            cols.append(col)
            keys.append((mi, pi, len(idx)))
    return np.concatenate(tris), np.concatenate(cols), keys


def repack(js, binc, image_max, new_indices):
    """Rebuild the binary chunk: resized images, replaced index arrays (prim -> uint32 array), aligned views."""
    out = bytearray()
    views = []
    for i, bv in enumerate(js["bufferViews"]):
        off = bv.get("byteOffset", 0)
        data = bytes(binc[off: off + bv["byteLength"]])
        views.append(data)
    # images
    for img in js.get("images", []):
        if "bufferView" not in img:
            continue
        data = views[img["bufferView"]]
        im = Image.open(io.BytesIO(data))
        has_alpha = im.mode in ("RGBA", "LA")
        im = im.convert("RGBA" if has_alpha else "RGB")
        if max(im.size) > image_max:
            k = image_max / max(im.size)
            im = im.resize((max(1, int(im.size[0] * k)), max(1, int(im.size[1] * k))), Image.LANCZOS)
        b = io.BytesIO()
        if has_alpha:
            im.save(b, "PNG", optimize=True)
            img["mimeType"] = "image/png"
        else:
            im.save(b, "JPEG", quality=88)
            img["mimeType"] = "image/jpeg"
        views[img["bufferView"]] = b.getvalue()
    # index arrays
    for (mi, pi), arr in new_indices.items():
        prim = js["meshes"][mi]["primitives"][pi]
        acc = js["accessors"][prim["indices"]]
        data = arr.astype("<u4").tobytes()
        views.append(data)
        js["bufferViews"].append({"buffer": 0, "byteLength": len(data), "target": 34963})
        acc.update({"bufferView": len(js["bufferViews"]) - 1, "byteOffset": 0, "componentType": 5125, "count": int(arr.size)})
        acc.pop("max", None)
        acc.pop("min", None)
    for i, data in enumerate(views):
        while len(out) % 4:
            out.append(0)
        js["bufferViews"][i]["byteOffset"] = len(out)
        js["bufferViews"][i]["byteLength"] = len(data)
        out += data
    js["buffers"] = [{"byteLength": len(out)}]
    return out


# ------------------------------------------------------------------------------------------------- renders

def render(tris, cols, view, size=640, title=""):
    """Flat-shaded painter's render of the calibrated model (metres). view: front (+Z towards the camera), side, top."""
    if view == "front":
        P = tris[..., [0, 1, 2]] * [1, 1, 1]
    elif view == "side":
        P = np.stack([-tris[..., 2], tris[..., 1], tris[..., 0]], -1)
    else:
        P = np.stack([tris[..., 0], -tris[..., 2], tris[..., 1]], -1)
    n = np.cross(tris[:, 1] - tris[:, 0], tris[:, 2] - tris[:, 0])
    n /= np.linalg.norm(n, axis=1, keepdims=True) + 1e-12
    light = np.array([0.35, 0.8, 0.5])
    light /= np.linalg.norm(light)
    shade = 0.45 + 0.55 * np.abs(n @ light)
    lo = P[..., :2].reshape(-1, 2).min(0)
    hi = P[..., :2].reshape(-1, 2).max(0)
    span = max(hi - lo) * 1.08 + 1e-9
    c = (lo + hi) / 2
    img = Image.new("RGB", (size, size), (236, 236, 232))
    d = ImageDraw.Draw(img)
    order = np.argsort(P[..., 2].mean(1))
    for i in order:
        q = (P[i, :, :2] - c) / span * size + size / 2
        pts = [(float(x), float(size - y)) for x, y in q]
        col = tuple(int(v) for v in np.clip(cols[i] * shade[i] * 255, 0, 255))
        d.polygon(pts, fill=col)
    d.text((8, 8), title, fill=(30, 30, 30))
    return img


# ------------------------------------------------------------------------------------------------- one model

# props Tripo stood on edge: laid flat on their thinnest axis
FLAT = {"bed"}
# props squashed to a height (metres) in the same calibration node: the L4 dog sleeps on the bed, not in it
SQUASH = {"bed": 0.16}


def lay_flat(pts):
    """Rotation taking the direction of least spread (PCA) onto +Y."""
    c = pts - pts.mean(0)
    w, v = np.linalg.eigh(c.T @ c)
    n = v[:, 0]
    if n[1] < 0:
        n = -n
    y = np.array([0.0, 1.0, 0.0])
    axis = np.cross(n, y)
    sa = np.linalg.norm(axis)
    if sa < 1e-9:
        return np.eye(3)
    axis /= sa
    ang = math.atan2(sa, float(n @ y))
    k = np.array([[0, -axis[2], axis[1]], [axis[2], 0, -axis[0]], [-axis[1], axis[0], 0]])
    return np.eye(3) + math.sin(ang) * k + (1 - math.cos(ang)) * k @ k


def quat_of(m):
    """glTF quaternion [x, y, z, w] of a rotation matrix."""
    tr = m[0, 0] + m[1, 1] + m[2, 2]
    if tr > 0:
        w = math.sqrt(1 + tr) / 2
        return [(m[2, 1] - m[1, 2]) / (4 * w), (m[0, 2] - m[2, 0]) / (4 * w), (m[1, 0] - m[0, 1]) / (4 * w), w]
    i = int(np.argmax([m[0, 0], m[1, 1], m[2, 2]]))
    j, k = (i + 1) % 3, (i + 2) % 3
    r = math.sqrt(1 + m[i, i] - m[j, j] - m[k, k])
    q = [0.0, 0.0, 0.0, 0.0]
    q[i] = r / 2
    q[j] = (m[j, i] + m[i, j]) / (2 * r)
    q[k] = (m[k, i] + m[i, k]) / (2 * r)
    q[3] = (m[k, j] - m[j, k]) / (2 * r)
    return q


def process(name, spec, metrics):
    measure, metres, yaw, image_max, task = spec
    src = os.path.join(RAW, f"{name}.glb")
    if not os.path.exists(src):
        print(f"  {name}: no raw file, skipped")
        return None
    js, binc = glbkit.read_glb(src)
    tris, cols, keys = triangles(js, binc)
    # turn the face onto +Z (about Y), then measure; flat props (the dog bed) first lie down on their thinnest axis
    a = math.radians(yaw)
    rot = np.array([[math.cos(a), 0, math.sin(a)], [0, 1, 0], [-math.sin(a), 0, math.cos(a)]])
    if name in FLAT:
        rot = rot @ lay_flat(tris.reshape(-1, 3))
    tr = tris @ rot.T
    lo, hi = tr.reshape(-1, 3).min(0), tr.reshape(-1, 3).max(0)
    size = hi - lo
    s = metres / (size[1] if measure == "height" else max(size[0], size[2]))
    centre = np.array([(lo[0] + hi[0]) / 2, lo[1], (lo[2] + hi[2]) / 2])
    sv = np.array([s, SQUASH[name] / size[1] if name in SQUASH else s, s])
    hand = None
    new_indices = {}
    if name == "owner":
        # the leash: saturated red triangles; keep the loop round the hand, cut what hangs below it
        ys = tr[:, :, 1].mean(1)
        # below 1.1 m only: the lips are red too
        red = (cols[:, 0] > 0.5) & (cols[:, 1] < 0.32) & (cols[:, 2] < 0.32) & (cols[:, 0] > cols[:, 1] * 2) & ((ys - lo[1]) * s < 1.1)
        if red.any():
            top = ys[red].max()
            loop = red & (ys > top - 0.11 / s)
            cut = red & (ys <= top - 0.11 / s)
            hp = tr[loop].reshape(-1, 3).mean(0)
            k = 0
            for mi, pi, cnt in keys:
                keep = ~cut[k:k + cnt]
                prim = js["meshes"][mi]["primitives"][pi]
                idx = glbkit.accessor(js, binc, prim["indices"]).reshape(-1, 3)
                new_indices[(mi, pi)] = idx[keep].reshape(-1)
                k += cnt
            tris, cols, tr = tris[~cut], cols[~cut], tr[~cut]
            # measure again without the cut leash: floor centre and size of what is left
            lo, hi = tr.reshape(-1, 3).min(0), tr.reshape(-1, 3).max(0)
            size = hi - lo
            s = metres / size[1]
            sv = np.array([s, s, s])
            centre = np.array([(lo[0] + hi[0]) / 2, lo[1], (lo[2] + hi[2]) / 2])
            hand = (hp - centre) * sv
            print(f"  owner: leash cut {int(cut.sum())} tris, loop {int(loop.sum())}, hand {np.round(hand, 3).tolist()}")
    # calibration root node: v' = s * R (v - centre')
    q = quat_of(rot)
    # the node's scale acts before its rotation, so a squash is applied on a child node after the rotation
    t = (-centre * sv).tolist()
    roots = list(js["scenes"][js.get("scene", 0)]["nodes"])
    if name in SQUASH:
        js["nodes"].append({"name": f"turn:{name}", "children": roots, "rotation": q})
        js["nodes"].append({"name": f"calib:{name}", "children": [len(js["nodes"]) - 1], "translation": t, "scale": sv.tolist()})
    else:
        js["nodes"].append({"name": f"calib:{name}", "children": roots, "translation": t, "rotation": q, "scale": [s, s, s]})
    js["scenes"][js.get("scene", 0)]["nodes"] = [len(js["nodes"]) - 1]
    # rotation is applied before translation in glTF TRS: R(v) then +t, with t computed on the rotated centre
    for mat in js.get("materials", []):
        mat["alphaMode"] = "OPAQUE"
        mat.pop("alphaCutoff", None)
        pbr = mat.setdefault("pbrMetallicRoughness", {})
        pbr["metallicFactor"] = 0.0
        pbr.setdefault("roughnessFactor", 0.85)
        mat.pop("extensions", None)
    js.pop("extensionsUsed", None) if not any("extensions" in m for m in js.get("materials", [])) else None
    binc2 = repack(js, binc, image_max, new_indices)
    os.makedirs(ASSETS, exist_ok=True)
    dst = os.path.join(ASSETS, f"{name}.glb")
    glbkit.write_glb(dst, js, binc2)
    cal = (tr - centre) * sv
    lo2, hi2 = cal.reshape(-1, 3).min(0), cal.reshape(-1, 3).max(0)
    m = {
        "asset": f"park/{name}.glb", "tripoTask": task, "measure": measure, "targetM": metres, "scale": s,
        "heightM": float(hi2[1] - lo2[1]), "widthM": float(hi2[0] - lo2[0]), "depthM": float(hi2[2] - lo2[2]),
        "minY": float(lo2[1]), "centreX": float((lo2[0] + hi2[0]) / 2), "centreZ": float((lo2[2] + hi2[2]) / 2),
        "triangles": int(len(cal)), "fileKB": os.path.getsize(dst) // 1024,
    }
    if hand is not None:
        m["hand"] = [float(v) for v in hand]
    metrics[name] = m
    os.makedirs(RENDERS, exist_ok=True)
    row = [render(cal, cols, v, 420, f"{name} {v} {m['heightM']:.2f} m") for v in ("front", "side", "top")]
    sheet = Image.new("RGB", (1260, 420))
    for i, im in enumerate(row):
        sheet.paste(im, (i * 420, 0))
    sheet.save(os.path.join(RENDERS, f"{name}_views.png"))
    print(f"  {name}: {m['heightM']:.2f} x {m['widthM']:.2f} x {m['depthM']:.2f} m, {m['triangles']} tris, {m['fileKB']} KB")
    return cal, cols


def lineup(parts):
    """Owner, the medium dog, bench, bowl, bed side by side at true size (B12 evidence: one style, one scale)."""
    dog = os.path.join(ROOT, "app", "src", "main", "assets", "dogs", "dog_red.glb")
    items = []
    if os.path.exists(dog):
        js, binc = glbkit.read_glb(dog)
        tris, cols, _ = triangles(js, binc)
        lo, hi = tris.reshape(-1, 3).min(0), tris.reshape(-1, 3).max(0)
        s = 0.58 / (hi[1] - lo[1])
        tris = (tris - [(lo[0] + hi[0]) / 2, lo[1], (lo[2] + hi[2]) / 2]) * s
        # the dog faces +Z: turn it side-on for the line-up
        tris = np.stack([tris[..., 2], tris[..., 1], -tris[..., 0]], -1)
        items.append(("dog", tris, cols))
    for n in ("owner", "bench", "bowl", "bed", "fence", "bush"):
        if n in parts:
            items.append((n, parts[n][0], parts[n][1]))
    if not items:
        return
    x = 0.0
    allt, allc = [], []
    for n, t, c in items:
        w = t[..., 0].max() - t[..., 0].min()
        allt.append(t + [x - t[..., 0].min(), 0, 0])
        allc.append(c)
        x += w + 0.25
    render(np.concatenate(allt), np.concatenate(allc), "front", 1400, "line-up at true size: " + ", ".join(n for n, _, _ in items)).save(os.path.join(RENDERS, "lineup.png"))


def kotlin(metrics):
    def f(v):
        return f"{v:.4f}f"

    lines = [
        "package io.embodit.dogmanners.domain",
        "",
        "// generated by tools/make_park.py from the calibrated Tripo models: edit the script, not this file",
        "",
        "/**",
        " * D25 for the park props: every GLB under assets/park carries ONE calibration root node (floor centre on the",
        " * origin, face on +Z, true size in metres), so the scene only moves / turns the parent; all instances of a model",
        " * share that one scale. [Model.heightM] etc. are the calibrated sizes, checked by DomainTest.",
        " */",
        "object ParkModels {",
        "    data class Model(val asset: String, val heightM: Float, val widthM: Float, val depthM: Float, val minY: Float, val centreX: Float, val centreZ: Float)",
        "",
    ]
    names = []
    for name in MODELS:
        m = metrics.get(name)
        if m is None:
            measure, metres = MODELS[name][0], MODELS[name][1]
            m = {"asset": f"park/{name}.glb", "heightM": metres if measure == "height" else metres * 0.4, "widthM": metres, "depthM": metres, "minY": 0, "centreX": 0, "centreZ": 0}
        const = name.upper()
        names.append(const)
        lines.append(f"    val {const} = Model(\"{m['asset']}\", {f(m['heightM'])}, {f(m['widthM'])}, {f(m['depthM'])}, {f(m['minY'])}, {f(m['centreX'])}, {f(m['centreZ'])})")
    hand = metrics.get("owner", {}).get("hand", [0.22, 0.80, 0.02])
    lines += [
        "",
        "    /** The owner's right hand (the leash loop), model space after calibration, metres. */",
        f"    val OWNER_HAND = Vec3({f(hand[0])}, {f(hand[1])}, {f(hand[2])})",
        "",
        f"    val ALL = listOf({', '.join(names)})",
        "}",
        "",
    ]
    open(KOTLIN, "w", encoding="utf-8").write("\n".join(lines))


def main():
    os.makedirs(ASSETS, exist_ok=True)
    grass_texture().save(os.path.join(ASSETS, "grass.jpg"), quality=86)
    path_texture().save(os.path.join(ASSETS, "path.jpg"), quality=86)
    print("ground textures written")
    metrics = {}
    parts = {}
    for name, spec in MODELS.items():
        r = process(name, spec, metrics)
        if r is not None:
            parts[name] = r
    json.dump(metrics, open(os.path.join(HERE, "park_metrics.json"), "w"), indent=1)
    kotlin(metrics)
    lineup(parts)
    print(f"models: {sorted(metrics)}")


if __name__ == "__main__":
    main()
