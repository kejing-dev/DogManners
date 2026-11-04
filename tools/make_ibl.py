"""Park environment light (IBL) for Dog Manners v0.1.3 (profile B24: PBR + environment light, not unlit / emissive).

Writes app/src/main/assets/park/park_ibl.ktx: an HDR cubemap of a soft summer sky over a lawn ringed by trees, in
the layout of the SDK's own map (tools/ktx_ibl.py): R11G11B10F, 256 px, 5 prefiltered levels (GGX, Filament's
lod = 4 * r * (2 - r) for perceptual roughness r), "sh" = the diffuse part as the shader polynomial.

The sky has no sun disc: the sun is the scene's DirectionalLightComponent (it casts the shadows); the map is the
sky light around it. Azimuth only adds a soft tree line, so the map needs no rotation when the park is turned.
Values are relative (sky ~1); the brightness is StageEnvironmentLightingComponent's exponent (ParkScene.IBL_EXP, 10).

  python -I tools/make_ibl.py [PREVIEW_PNG]
"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ktx_ibl  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "park", "park_ibl.ktx")
SIZE = 256
LEVELS = 5

ZENITH = np.array([0.40, 0.58, 0.95])
HORIZON = np.array([0.98, 0.98, 0.96])
TREES = np.array([0.16, 0.24, 0.12])
GRASS = np.array([0.30, 0.38, 0.17])


def face_dirs(face, n):
    """GL cube layout as the SDK map stores it (+X -X +Y -Y +Z -Z, row 0 on top); checked against its SH."""
    c = (np.arange(n) + 0.5) / n * 2 - 1
    t, s = np.meshgrid(c, c, indexing="ij")
    one = np.ones_like(s)
    d = [np.stack([one, -t, -s], -1), np.stack([-one, -t, s], -1), np.stack([s, one, t], -1),
         np.stack([s, -one, -t], -1), np.stack([s, -t, one], -1), np.stack([-s, -t, -one], -1)][face]
    w = 1.0 / np.sum(d * d, -1) ** 1.5
    return d / np.linalg.norm(d, axis=-1, keepdims=True), w


def smooth(e0, e1, x):
    u = np.clip((x - e0) / (e1 - e0), 0, 1)
    return u * u * (3 - 2 * u)


def env(d):
    x, y, z = d[..., 0], d[..., 1], d[..., 2]
    az = np.arctan2(x, z)
    up = np.sqrt(np.clip(y, 0, 1))
    sky = HORIZON * (1 - up[..., None]) + ZENITH * up[..., None]
    # tree crowns round the lawn: 4–10° above the horizon, uneven
    crown = 0.07 + 0.05 * (0.5 + 0.5 * np.sin(az * 7 + 1.3) * np.sin(az * 3 - 0.4))
    tree = 1 - smooth(crown - 0.03, crown + 0.02, y)
    sky = sky * (1 - 0.85 * tree[..., None]) + TREES * 0.85 * tree[..., None]
    ground = GRASS * (0.75 + 0.25 * smooth(-1, -0.1, -y))[..., None] + TREES * 0.4 * smooth(-0.12, 0, y)[..., None]
    return np.where((y >= 0)[..., None], sky, ground)


def ggx_samples(alpha, n, rng):
    """Half vectors around +Z for GGX(alpha), N = V = R: returns sample dirs L and weights (N·L)."""
    u1, u2 = rng.random(n), rng.random(n)
    ct = np.sqrt((1 - u1) / (1 + (alpha * alpha - 1) * u1))
    st = np.sqrt(1 - ct * ct)
    ph = 2 * np.pi * u2
    h = np.stack([st * np.cos(ph), st * np.sin(ph), ct], -1)
    v = np.array([0, 0, 1.0])
    L = 2 * (h @ v)[:, None] * h - v
    nl = L[:, 2]
    keep = nl > 0
    return L[keep], nl[keep]


def basis(n):
    """(T, B, N) per texel for rotating +Z samples onto direction n."""
    up = np.where(np.abs(n[..., 1:2]) < 0.999, np.array([0, 1.0, 0]), np.array([1.0, 0, 0]))
    t = np.cross(up, n)
    t /= np.linalg.norm(t, axis=-1, keepdims=True)
    b = np.cross(n, t)
    return t, b


def level(face, n, lv, rng):
    d, _ = face_dirs(face, n)
    if lv == 0:
        return env(d)
    r = 1 - np.sqrt(1 - lv / (LEVELS - 1))
    L, wt = ggx_samples(max(r * r, 1e-3), 192, rng)
    t, b = basis(d)
    acc = np.zeros(d.shape)
    for (lx, ly, lz), w in zip(L, wt):
        acc += w * env(t * lx + b * ly + d * lz)
    return acc / wt.sum()


def sh_poly(levels):
    """Diffuse irradiance / pi as the 9-term polynomial the shader evaluates (same basis and order as the SDK map)."""
    rows, cols, wts, rad = [], [], [], []
    n = 64
    for f in range(6):
        d, w = face_dirs(f, n)
        rows.append(d.reshape(-1, 3))
        wts.append(w.reshape(-1))
        rad.append(env(d).reshape(-1, 3))
    d = np.concatenate(rows)
    w = np.concatenate(wts)
    w /= w.sum()
    L = np.concatenate(rad)
    x, y, z = d[:, 0], d[:, 1], d[:, 2]
    # real SH (orthonormal) up to band 2
    Y = np.stack([0.282095 * np.ones_like(x), 0.488603 * y, 0.488603 * z, 0.488603 * x, 1.092548 * x * y, 1.092548 * y * z,
                  0.315392 * (3 * z * z - 1), 1.092548 * x * z, 0.546274 * (x * x - y * y)], 1)
    coef = (Y * w[:, None]).T @ L * 4 * np.pi
    a = np.array([1, 2 / 3, 2 / 3, 2 / 3, 1 / 4, 1 / 4, 1 / 4, 1 / 4, 1 / 4])  # A_l / pi
    E = Y @ (coef * a[:, None])  # irradiance / pi at every sample dir
    P = np.stack([np.ones_like(x), y, z, x, y * x, y * z, 3 * z * z - 1, z * x, x * x - y * y], 1)
    sol, *_ = np.linalg.lstsq(P * np.sqrt(w)[:, None], E * np.sqrt(w)[:, None], rcond=None)
    return sol, E, L, w


def preview(levels, path):
    from PIL import Image
    tiles = []
    for lv in (0, 2, 4):
        n = levels[lv][0].shape[0]
        row = [np.clip(levels[lv][f] / 1.1, 0, 1) ** (1 / 2.2) for f in range(6)]
        img = np.concatenate(row, 1)
        tiles.append(np.array(Image.fromarray((img * 255).astype(np.uint8)).resize((6 * 128, 128), Image.NEAREST)))
    Image.fromarray(np.concatenate(tiles, 0)).save(path)


def main():
    rng = np.random.default_rng(20261104)
    levels = [[level(f, SIZE >> lv, lv, rng).astype(np.float32) for f in range(6)] for lv in range(LEVELS)]
    sh, E, L, w = sh_poly(levels)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    ktx_ibl.write(OUT, levels, sh)
    back, kv, _ = ktx_ibl.read(OUT)
    err = max(float(np.max(np.abs(back[lv][f] - levels[lv][f]) / (levels[lv][f] + 1e-3))) for lv in range(LEVELS) for f in range(6))
    print(f"wrote {OUT} {os.path.getsize(OUT)} bytes, round-trip rel err {err:.3f}")
    print("sh\n" + kv["sh"])
    print("mean radiance up / down", float((L[:, :] * w[:, None]).sum(0).mean()))
    if len(sys.argv) > 1:
        preview(levels, sys.argv[1])


if __name__ == "__main__":
    main()
