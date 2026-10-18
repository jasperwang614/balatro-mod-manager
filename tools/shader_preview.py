"""原版 background.fs 的 Python 移植，用来预览配色与流速。"""
import math, zlib, struct, sys

W, H = 240, 320
TILE = 3
SIZE_W, SIZE_H = W * TILE, H


def write_png(path, w, h, buf):
    raw = b''.join(b'\x00' + bytes(buf[y * w * 3:(y + 1) * w * 3]) for y in range(h))

    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)

    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(raw, 6)) + chunk(b'IEND', b'')
    open(path, 'wb').write(png)


def hexc(s):
    return (int(s[0:2], 16) / 255.0, int(s[2:4], 16) / 255.0, int(s[4:6], 16) / 255.0)


def background(x, y, w, h, t, spin_time, c1, c2, c3, contrast, spin_amount):
    L = math.hypot(w, h)
    ux = (x - 0.5 * w) / L - 0.12
    uy = (y - 0.5 * h) / L
    ux_len = math.hypot(ux, uy)

    speed = (spin_time * 0.5 * 0.2) + 302.2
    ang = math.atan2(uy, ux) + speed - 0.5 * 20.0 * (spin_amount * ux_len + (1.0 - spin_amount))
    mx = (w / L) / 2.0
    my = (h / L) / 2.0
    ux = (ux_len * math.cos(ang) + mx) - mx
    uy = (ux_len * math.sin(ang) + my) - my

    ux *= 30.0
    uy *= 30.0
    speed = t * 2.0
    v2x = ux + uy
    v2y = ux + uy

    for _ in range(5):
        s = math.sin(max(ux, uy))
        v2x += s + ux
        v2y += s + uy
        ux += 0.5 * math.cos(5.1123314 + 0.353 * v2y + speed * 0.131121)
        uy += 0.5 * math.sin(v2x - 0.113 * speed)
        d = 1.0 * math.cos(ux + uy) - 1.0 * math.sin(ux * 0.711 - uy)
        ux -= d
        uy -= d

    cm = 0.25 * contrast + 0.5 * spin_amount + 1.2
    paint = min(2.0, max(0.0, math.hypot(ux, uy) * 0.035 * cm))
    c1p = max(0.0, 1.0 - cm * abs(1.0 - paint))
    c2p = max(0.0, 1.0 - cm * abs(paint))
    c3p = 1.0 - min(1.0, c1p + c2p)

    k = 0.3 / contrast
    r = k * c1[0] + (1 - k) * (c1[0] * c1p + c2[0] * c2p + c3[0] * c3p)
    g = k * c1[1] + (1 - k) * (c1[1] * c1p + c2[1] * c2p + c3[1] * c3p)
    b = k * c1[2] + (1 - k) * (c1[2] * c1p + c2[2] * c2p + c3[2] * c3p)
    return r, g, b


def run(path, t):
    # 三套配色：菜单默认 / 小盲注绿 / 略提亮的绿
    confs = [
        (hexc('374244'), (1, 1, 0), hexc('374244'), 1.0),
        (hexc('50846e'), (1, 1, 0), hexc('374244'), 1.0),
        (hexc('2f5f52'), (0.95, 0.88, 0.35), hexc('374244'), 1.1),
    ]
    buf = bytearray(SIZE_W * SIZE_H * 3)
    for k, (c1, c2, c3, contrast) in enumerate(confs):
        for y in range(H):
            for x in range(W):
                r, g, b = background(x, y, W, H, t, 0.0, c1, c2, c3, contrast, 0.0)
                o = ((y * SIZE_W) + k * W + x) * 3
                buf[o] = int(max(0, min(255, r * 255)))
                buf[o + 1] = int(max(0, min(255, g * 255)))
                buf[o + 2] = int(max(0, min(255, b * 255)))
    write_png(path, SIZE_W, SIZE_H, buf)
    print('ok', path)


if __name__ == '__main__':
    run(sys.argv[1] if len(sys.argv) > 1 else 'shader.png', float(sys.argv[2]) if len(sys.argv) > 2 else 0.0)
