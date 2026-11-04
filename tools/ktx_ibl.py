"""KTX1 cubemap read / write in the layout PICO's renderer takes for IBL (StageEnvironmentLightingComponent).

Layout learnt from the SDK's own built-in map (PICO/sdk/6.1/editor/SpatialEditor/resources/builtin/assets/env/
SystemIbl_ibl_brightness.ktx): KTX 1.1, GL_UNSIGNED_INT_10F_11F_11F_REV / GL_R11F_G11F_B10F, 6 faces
(+X -X +Y -Y +Z -Z), a full prefiltered mip chain from 256 down to 16 (5 levels, roughness grows per level), and a
key "sh" with 9 RGB spherical-harmonics rows (the diffuse part, as the shader polynomial).
"""
import struct

import numpy as np

KTX_ID = b"\xabKTX 11\xbb\r\n\x1a\n"
GL_R11F_G11F_B10F = 35898
GL_RGB = 6407


def _uf_from_half(h, bits):
    # half: 1 sign, 5 exp, 10 mantissa; uf11 = 5 exp + 6 mant, uf10 = 5 exp + 5 mant (same bias), drop the sign
    return (h >> (10 - (bits - 5))) & ((1 << bits) - 1)


def pack_r11g11b10(rgb):
    x = np.clip(np.nan_to_num(rgb, nan=0.0), 0.0, 65000.0).astype(np.float16).view(np.uint16).astype(np.uint32)
    r = _uf_from_half(x[..., 0], 11)
    g = _uf_from_half(x[..., 1], 11)
    b = _uf_from_half(x[..., 2], 10)
    return (r | (g << 11) | (b << 22)).astype("<u4")


def unpack_r11g11b10(u):
    u = u.astype(np.uint32)

    def to_f(v, bits):
        half = (v << (10 - (bits - 5))).astype(np.uint16)
        return half.view(np.float16).astype(np.float32)

    return np.stack([to_f(u & 0x7FF, 11), to_f((u >> 11) & 0x7FF, 11), to_f((u >> 22) & 0x3FF, 10)], -1)


def read(path):
    d = open(path, "rb").read()
    assert d[:12] == KTX_ID
    h = struct.unpack("<13I", d[12:64])
    w, faces, mips, kvb = h[6], h[10], h[11], h[12]
    kv = {}
    o = 64
    end = 64 + kvb
    while o < end:
        n = struct.unpack("<I", d[o:o + 4])[0]
        blob = d[o + 4:o + 4 + n]
        k, _, v = blob.partition(b"\0")
        kv[k.decode()] = v.rstrip(b"\0").decode(errors="replace")
        o += 4 + n + (-n % 4)
    o = end
    levels = []
    for lv in range(mips):
        size = struct.unpack("<I", d[o:o + 4])[0]
        o += 4
        s = max(1, w >> lv)
        fl = []
        for f in range(faces):
            a = np.frombuffer(d[o:o + size], "<u4").reshape(s, s)
            fl.append(unpack_r11g11b10(a))
            o += size + (-size % 4)
        levels.append(fl)
    return levels, kv, h


def write(path, levels, sh_rows):
    """levels[l][face] = float RGB (s, s, 3); sh_rows = 9 RGB triples (the shader polynomial)."""
    w = levels[0][0].shape[0]
    val = "".join(f"{r:g} {g:g} {b:g}\n" for r, g, b in sh_rows).encode() + b"\0"
    kvp = b"sh\0" + val
    kv = struct.pack("<I", len(kvp)) + kvp + b"\0" * (-len(kvp) % 4)
    hdr = struct.pack("<13I", 0x04030201, GL_R11F_G11F_B10F, 1, GL_RGB, GL_R11F_G11F_B10F, GL_R11F_G11F_B10F,
                      w, w, 0, 0, 6, len(levels), len(kv))
    out = [KTX_ID, hdr, kv]
    for fl in levels:
        data = [pack_r11g11b10(f).tobytes() for f in fl]
        out.append(struct.pack("<I", len(data[0])))
        out.extend(data)
    with open(path, "wb") as fh:
        fh.write(b"".join(out))
