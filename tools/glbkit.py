"""Minimal glTF/GLB toolkit for the Shiba model (no Blender on this machine).

read / write GLB, read accessors, node TRS, sample clips, forward kinematics, linear blend skinning, and a small
point-splat renderer used for the anim-check pictures. Pure numpy + PIL.
"""
import json
import math
import struct

import numpy as np

CT = {5126: ("f", 4, np.float32), 5123: ("H", 2, np.uint16), 5121: ("B", 1, np.uint8), 5125: ("I", 4, np.uint32)}
NC = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


def read_glb(path):
    data = open(path, "rb").read()
    magic, _, length = struct.unpack_from("<III", data, 0)
    assert magic == 0x46546C67, "not a GLB"
    off, js, binc = 12, None, b""
    while off < length:
        clen, ctype = struct.unpack_from("<II", data, off)
        chunk = data[off + 8: off + 8 + clen]
        if ctype == 0x4E4F534A:
            js = json.loads(chunk.decode("utf-8"))
        elif ctype == 0x004E4942:
            binc = chunk
        off += 8 + clen
    return js, bytearray(binc)


def write_glb(path, js, binc):
    jb = json.dumps(js, separators=(",", ":")).encode("utf-8")
    jb += b" " * ((4 - len(jb) % 4) % 4)
    bb = bytes(binc) + b"\x00" * ((4 - len(binc) % 4) % 4)
    js["buffers"][0]["byteLength"] = len(bb)
    jb = json.dumps(js, separators=(",", ":")).encode("utf-8")
    jb += b" " * ((4 - len(jb) % 4) % 4)
    total = 12 + 8 + len(jb) + 8 + len(bb)
    with open(path, "wb") as f:
        f.write(struct.pack("<III", 0x46546C67, 2, total))
        f.write(struct.pack("<II", len(jb), 0x4E4F534A))
        f.write(jb)
        f.write(struct.pack("<II", len(bb), 0x004E4942))
        f.write(bb)


def accessor(js, binc, i):
    a = js["accessors"][i]
    bv = js["bufferViews"][a["bufferView"]]
    _, size, dt = CT[a["componentType"]]
    n = NC[a["type"]]
    off = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
    stride = bv.get("byteStride", size * n)
    if stride == size * n:
        arr = np.frombuffer(bytes(binc[off: off + a["count"] * stride]), dtype=dt).reshape(a["count"], n)
    else:
        raw = np.frombuffer(bytes(binc[off: off + a["count"] * stride]), dtype=np.uint8).reshape(a["count"], stride)
        arr = raw[:, : size * n].copy().view(dt).reshape(a["count"], n)
    return arr.astype(np.float64) if dt == np.float32 else arr


# ------------------------------------------------------------------ quaternions (x, y, z, w)

def qmul(a, b):
    ax, ay, az, aw = a
    bx, by, bz, bw = b
    return np.array([aw * bx + ax * bw + ay * bz - az * by,
                     aw * by - ax * bz + ay * bw + az * bx,
                     aw * bz + ax * by - ay * bx + az * bw,
                     aw * bw - ax * bx - ay * by - az * bz])


def qinv(q):
    return np.array([-q[0], -q[1], -q[2], q[3]]) / max(1e-12, float(np.dot(q, q)))


def qaxis(axis, deg):
    axis = np.asarray(axis, dtype=np.float64)
    axis = axis / np.linalg.norm(axis)
    h = math.radians(deg) / 2
    return np.array([*(axis * math.sin(h)), math.cos(h)])


def qnorm(q):
    return q / np.linalg.norm(q)


def qmat(q):
    x, y, z, w = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def mat_q(m):
    t = m[0, 0] + m[1, 1] + m[2, 2]
    if t > 0:
        s = math.sqrt(t + 1.0) * 2
        q = [(m[2, 1] - m[1, 2]) / s, (m[0, 2] - m[2, 0]) / s, (m[1, 0] - m[0, 1]) / s, 0.25 * s]
    elif m[0, 0] > m[1, 1] and m[0, 0] > m[2, 2]:
        s = math.sqrt(1.0 + m[0, 0] - m[1, 1] - m[2, 2]) * 2
        q = [0.25 * s, (m[0, 1] + m[1, 0]) / s, (m[0, 2] + m[2, 0]) / s, (m[2, 1] - m[1, 2]) / s]
    elif m[1, 1] > m[2, 2]:
        s = math.sqrt(1.0 + m[1, 1] - m[0, 0] - m[2, 2]) * 2
        q = [(m[0, 1] + m[1, 0]) / s, 0.25 * s, (m[1, 2] + m[2, 1]) / s, (m[0, 2] - m[2, 0]) / s]
    else:
        s = math.sqrt(1.0 + m[2, 2] - m[0, 0] - m[1, 1]) * 2
        q = [(m[0, 2] + m[2, 0]) / s, (m[1, 2] + m[2, 1]) / s, 0.25 * s, (m[1, 0] - m[0, 1]) / s]
    return qnorm(np.array(q))


def trs_matrix(t, r, s):
    m = np.eye(4)
    m[:3, :3] = qmat(r) * np.asarray(s)[None, :]
    m[:3, 3] = t
    return m


# ------------------------------------------------------------------ scene

class Model:
    def __init__(self, path):
        self.js, self.bin = read_glb(path)
        js = self.js
        self.n = len(js["nodes"])
        self.parent = [-1] * self.n
        for i, nd in enumerate(js["nodes"]):
            for c in nd.get("children", []):
                self.parent[c] = i
        self.names = [nd.get("name", str(i)) for i, nd in enumerate(js["nodes"])]
        # default TRS (matrix nodes are kept as fixed matrices)
        self.T, self.R, self.S, self.M = [], [], [], []
        for nd in js["nodes"]:
            if "matrix" in nd:
                self.M.append(np.array(nd["matrix"]).reshape(4, 4).T)
            else:
                self.M.append(None)
            self.T.append(np.array(nd.get("translation", [0, 0, 0]), dtype=np.float64))
            self.R.append(np.array(nd.get("rotation", [0, 0, 0, 1]), dtype=np.float64))
            self.S.append(np.array(nd.get("scale", [1, 1, 1]), dtype=np.float64))
        self.order = []
        seen = set()

        def visit(i):
            if i in seen:
                return
            seen.add(i)
            self.order.append(i)
            for c in js["nodes"][i].get("children", []):
                visit(c)
        for r in js["scenes"][0]["nodes"]:
            visit(r)
        self.clips = {a["name"]: a for a in js.get("animations", [])}

    def find(self, sub):
        for i, nm in enumerate(self.names):
            if nm.startswith(sub + ".") or nm == sub:
                return i
        raise KeyError(sub)

    def pose_default(self):
        return {i: (self.T[i].copy(), self.R[i].copy(), self.S[i].copy()) for i in range(self.n) if self.M[i] is None}

    def sample(self, clip, t):
        """Local TRS of every animated node at time t (clamped), on top of the defaults."""
        pose = self.pose_default()
        a = self.clips[clip]
        for ch in a["channels"]:
            node, path = ch["target"]["node"], ch["target"]["path"]
            smp = a["samplers"][ch["sampler"]]
            ts = accessor(self.js, self.bin, smp["input"])[:, 0]
            vs = accessor(self.js, self.bin, smp["output"])
            if t <= ts[0]:
                v = vs[0]
            elif t >= ts[-1]:
                v = vs[-1]
            else:
                k = int(np.searchsorted(ts, t)) - 1
                u = (t - ts[k]) / max(1e-9, ts[k + 1] - ts[k])
                a0, a1 = vs[k], vs[k + 1]
                if path == "rotation" and np.dot(a0, a1) < 0:
                    a1 = -a1
                v = a0 * (1 - u) + a1 * u
                if path == "rotation":
                    v = qnorm(v)
            T, R, S = pose[node]
            if path == "translation":
                T = v.copy()
            elif path == "rotation":
                R = v.copy()
            else:
                S = v.copy()
            pose[node] = (T, R, S)
        return pose

    def world(self, pose):
        W = [None] * self.n
        for i in self.order:
            local = self.M[i] if self.M[i] is not None else trs_matrix(*pose[i])
            p = self.parent[i]
            W[i] = local if p < 0 else W[p] @ local
        return W

    def world_rot(self, W, i):
        m = W[i][:3, :3].copy()
        for k in range(3):
            m[:, k] /= np.linalg.norm(m[:, k])
        return mat_q(m)

    # skinning ------------------------------------------------------------------
    def skin_vertices(self, pose):
        js = self.js
        prim = js["meshes"][0]["primitives"][0]
        if not hasattr(self, "_mesh"):
            att = prim["attributes"]
            self._mesh = dict(
                pos=accessor(js, self.bin, att["POSITION"]),
                nrm=accessor(js, self.bin, att["NORMAL"]),
                uv=accessor(js, self.bin, att["TEXCOORD_0"]),
                j=accessor(js, self.bin, att["JOINTS_0"]).astype(np.int64),
                w=accessor(js, self.bin, att["WEIGHTS_0"]),
                ibm=accessor(js, self.bin, js["skins"][0]["inverseBindMatrices"]).reshape(-1, 4, 4).transpose(0, 2, 1),
                joints=js["skins"][0]["joints"],
                idx=accessor(js, self.bin, prim["indices"])[:, 0].astype(np.int64),
            )
        m = self._mesh
        W = self.world(pose)
        # skinned mesh node world matrix is ignored by glTF; joints are in scene space
        J = np.stack([W[j] @ m["ibm"][k] for k, j in enumerate(m["joints"])])
        P = np.concatenate([m["pos"], np.ones((len(m["pos"]), 1))], 1)
        N = m["nrm"]
        out = np.zeros((len(P), 3))
        outn = np.zeros((len(P), 3))
        for k in range(4):
            Mk = J[m["j"][:, k]]
            wk = m["w"][:, k][:, None]
            out += wk * np.einsum("nij,nj->ni", Mk, P)[:, :3]
            outn += wk * np.einsum("nij,nj->ni", Mk[:, :3, :3], N)
        outn /= np.maximum(1e-9, np.linalg.norm(outn, axis=1, keepdims=True))
        return out, outn, W


def render_points(verts, normals, colors, view, size=(480, 360), fit=None, light=(0.4, 0.8, 0.5), bg=(232, 236, 228)):
    """Orthographic point splat. view: 'side' (looking from +X: dog left side), 'front' (from +Z), 'top', '34'."""
    from PIL import Image
    v = verts.copy()
    n = normals.copy()

    def rot(axis, deg):
        return qmat(qaxis(axis, deg))
    if view == "side":
        R = rot([0, 1, 0], -90)          # +X towards the viewer (camera looks along -X)
    elif view == "front":
        R = np.eye(3)                    # camera looks along -Z: sees the face
    elif view == "top":
        R = rot([1, 0, 0], 90)
    else:
        R = rot([1, 0, 0], 18) @ rot([0, 1, 0], -40)
    v = v @ R.T
    n = n @ R.T
    w, h = size
    if fit is None:
        lo, hi = v.min(0), v.max(0)
    else:
        lo, hi = fit
    scale = 0.9 * min(w / max(1e-6, hi[0] - lo[0]), h / max(1e-6, hi[1] - lo[1]))
    cx, cy = (lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2
    px = (w / 2 + (v[:, 0] - cx) * scale).astype(int)
    py = (h / 2 - (v[:, 1] - cy) * scale).astype(int)
    L = np.asarray(light, dtype=np.float64)
    L /= np.linalg.norm(L)
    shade = np.clip(0.35 + 0.75 * np.clip(n @ L, 0, 1), 0, 1.1)
    col = np.clip(colors * shade[:, None], 0, 255)
    img = np.zeros((h, w, 3))
    img[:] = bg
    depth = np.full((h, w), -1e9)
    order = np.argsort(v[:, 2])
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            xs, ys = px[order] + dx, py[order] + dy
            ok = (xs >= 0) & (xs < w) & (ys >= 0) & (ys < h)
            xs, ys, zs, cs = xs[ok], ys[ok], v[order][ok][:, 2], col[order][ok]
            # later (nearer) points overwrite
            front = zs >= depth[ys, xs]
            img[ys[front], xs[front]] = cs[front]
            depth[ys[front], xs[front]] = np.maximum(depth[ys[front], xs[front]], zs[front])
    return Image.fromarray(img.astype(np.uint8)), (lo, hi)
