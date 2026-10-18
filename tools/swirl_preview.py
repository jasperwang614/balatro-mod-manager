import math, zlib, struct, sys

W = 256
TILE = 2          # 2x2 拼图
SIZE = W * TILE


def write_png(path, w, h, buf):
    raw = b''.join(b'\x00' + buf[y * w * 3:(y + 1) * w * 3] for y in range(h))

    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(raw, 6))
    png += chunk(b'IEND', b'')
    open(path, 'wb').write(png)


def lerp(a, b, t):
    return a + (b - a) * t


def ramp(stops, t):
    t = max(0.0, min(1.0, t))
    for i in range(len(stops) - 1):
        p0, c0 = stops[i]
        p1, c1 = stops[i + 1]
        if t <= p1:
            k = (t - p0) / max(1e-6, p1 - p0)
            return (lerp(c0[0], c1[0], k), lerp(c0[1], c1[1], k), lerp(c0[2], c1[2], k))
    return stops[-1][1]


# 小丑牌式暗绿主色
GREEN = [
    (0.00, (6, 14, 17)),
    (0.22, (10, 32, 36)),
    (0.45, (16, 58, 57)),
    (0.62, (28, 92, 78)),
    (0.78, (62, 128, 96)),
    (0.90, (110, 160, 110)),
    (1.00, (150, 184, 130)),
]
RED = [
    (0.00, (8, 12, 18)),
    (0.30, (40, 18, 34)),
    (0.55, (86, 28, 40)),
    (0.75, (140, 44, 46)),
    (0.90, (186, 78, 58)),
    (1.00, (214, 128, 74)),
]


def variant(idx, x, y, t=0.0):
    r = math.hypot(x, y)
    a = math.atan2(y, x)
    if idx == 0:
        # 螺旋带 + 域扭曲
        w = math.sin(a * 3.0 + r * 2.6) * 0.85 + math.sin(a * 5.0 - r * 4.2) * 0.35
        q = r * 3.0 + w * 1.5
        n = 0.5 + 0.5 * math.sin(q * 3.2)
        n = n ** 1.7
        c = 0.5 + 0.5 * math.sin(a * 2.0 - r * 3.0 + 1.4)
    elif idx == 1:
        # 双层大理石
        w1 = math.sin(a * 2.0 + r * 3.4) * 1.1
        w2 = math.sin(a * 4.0 - r * 2.0 + w1) * 0.6
        q = r * 2.4 + w1 * 0.9 + w2
        n = 0.5 + 0.5 * math.sin(q * 2.6)
        n = n ** 1.5
        c = 0.5 + 0.5 * math.sin(a * 3.0 + r * 1.5 + w2 * 1.2)
    elif idx == 2:
        # 对数螺旋（更接近漩涡）
        q = a * 1.5 + math.log(r + 0.06) * 2.6
        n = 0.5 + 0.5 * math.sin(q * 2.2 + math.sin(r * 6.0) * 0.5)
        n = n ** 1.8
        c = 0.5 + 0.5 * math.sin(a * 2.0 + r * 2.0)
    else:
        # 流体噪声感
        s = 0.0
        f = 1.0
        amp = 0.6
        for k in range(4):
            s += amp * math.sin(x * 3.1 * f + math.sin(y * 2.7 * f + a) * 1.4 + k * 1.7)
            f *= 2.0
            amp *= 0.55
        n = 0.5 + 0.5 * math.sin(s * 2.4 + r * 2.0)
        n = n ** 1.6
        c = 0.5 + 0.5 * math.sin(s * 1.3 + a * 2.0 + 0.8)
    return n, c


def render(t):
    buf = bytearray(W * TILE * W * TILE * 3)
    for ty in range(TILE):
        for tx in range(TILE):
            idx = ty * TILE + tx
            for py in range(W):
                ny = (py / (W - 1)) * 2.0 - 1.0
                for px in range(W):
                    nx = (px / (W - 1)) * 2.0 - 1.0
                    n, c = variant(idx, nx, ny, t)
                    col = ramp(GREEN, n)
                    if c > 0.62:
                        col2 = ramp(RED, n)
                        k = (c - 0.62) / 0.38
                        col = (lerp(col[0], col2[0], k * 0.75),
                               lerp(col[1], col2[1], k * 0.75),
                               lerp(col[2], col2[2], k * 0.75))
                    # 暗角
                    v = 1.0 - 0.42 * min(1.0, (nx * nx + ny * ny) * 0.55)
                    X = tx * W + px
                    Y = ty * W + py
                    o = (Y * W * TILE + X) * 3
                    buf[o] = int(max(0, min(255, col[0] * v)))
                    buf[o + 1] = int(max(0, min(255, col[1] * v)))
                    buf[o + 2] = int(max(0, min(255, col[2] * v)))
    return buf


if __name__ == '__main__':
    write_png(sys.argv[1] if len(sys.argv) > 1 else 'swirl.png', W * TILE, W * TILE, render(0.0))
    print('ok')
