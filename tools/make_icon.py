#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把一张带灰色底/水印的方形 logo 图，转成 Android 启动器图标资源。

用法:
    python tools/make_icon.py <源图> [项目根目录]

做的事情:
  1. 自动探测 logo 主体的外接框（按饱和度 / 暗度判据），裁成正方形
  2. 从四角泛洪，把 logo 圆角外的灰色背景抠成透明（不碰内部白牌）
  3. 生成 legacy 图标 mipmap-*/ic_launcher.png (48/72/96/144/192)
  4. 生成 adaptive foreground mipmap-*/ic_launcher_foreground.png
     (108dp 画布，logo 占 72dp 居中，NEAREST 缩放保持像素硬边)

可调参数见下方常量。
"""
import os
import sys
from collections import deque

from PIL import Image, ImageFilter

# ---- 可调参数 ----
CONTENT_SIZE_DP = 76          # adaptive 前景里 logo 占多少 dp（108dp 画布，可视区 72dp）
CANVAS_DP = 108
SAT_THRESHOLD = 45            # mx-mn 大于此值视为彩色内容
DARK_THRESHOLD = 70           # 最亮通道小于此值视为暗色描边
GRAY_SAT = 40                 # 泛洪时允许的低饱和上限
GRAY_MIN_LUMA = 70            # 泛洪时允许的最低亮度（挡住黑边）
LEGACY_DP = 48                # legacy 图标基准尺寸
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def content_mask(im):
    """返回逐像素的内容判定函数结果，用于找外接框。"""
    px = im.load()
    w, h = im.size

    def is_content(p):
        mx, mn = max(p), min(p)
        return (mx - mn) > SAT_THRESHOLD or mx < DARK_THRESHOLD

    return is_content, px, w, h


def find_box(im):
    """找 logo 主体外接框。

    不能直接用 min/max：角落里的水印、噪点会撑大框。
    改成按行/列统计内容像素数量，超过 20% 才算数。
    """
    is_content, px, w, h = content_mask(im)
    colcnt = [0] * w
    rowcnt = [0] * h
    for y in range(h):
        for x in range(w):
            if is_content(px[x, y]):
                colcnt[x] += 1
                rowcnt[y] += 1
    thx = max(20, w // 5)
    thy = max(20, h // 5)
    xs = [i for i, c in enumerate(colcnt) if c >= thx]
    ys = [i for i, c in enumerate(rowcnt) if c >= thy]
    x0, x1 = xs[0], xs[-1] + 1
    y0, y1 = ys[0], ys[-1] + 1
    # 取较短边做正方形：长的那一维通常是生成时的轻微拉伸/柔光，
    # 按长边取会把外面的灰底又带进来。
    side = min(x1 - x0, y1 - y0)
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
    l = max(0, min(cx - side // 2, w - side))
    t = max(0, min(cy - side // 2, h - side))
    return l, t, l + side, t + side


def strip_gray(sq):
    """从四角泛洪，把圆角外的灰色背景抠成透明。"""
    sq = sq.convert("RGBA")
    px = sq.load()
    w, h = sq.size
    seen = [[False] * h for _ in range(w)]
    q = deque()
    for seed in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
        q.append(seed)
    while q:
        x, y = q.popleft()
        if x < 0 or y < 0 or x >= w or y >= h or seen[x][y]:
            continue
        r, g, b, a = px[x, y]
        mx, mn = max(r, g, b), min(r, g, b)
        if (mx - mn) > GRAY_SAT or mx < GRAY_MIN_LUMA:
            continue
        seen[x][y] = True
        px[x, y] = (r, g, b, 0)
        q.append((x + 1, y))
        q.append((x - 1, y))
        q.append((x, y + 1))
        q.append((x, y - 1))
    # 泛洪会在灰/黑交界留一圈半透明灰边，向外膨胀 2px 一并清掉
    clear = [row[:] for row in seen]
    for _ in range(2):
        nxt = [row[:] for row in clear]
        for x in range(w):
            for y in range(h):
                if clear[x][y]:
                    continue
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < w and 0 <= ny < h and clear[nx][ny]:
                        nxt[x][y] = True
                        break
        clear = nxt
    for x in range(w):
        for y in range(h):
            if clear[x][y]:
                r, g, b, _ = px[x, y]
                px[x, y] = (r, g, b, 0)
    return sq


def downscale(logo, size):
    """缩到目标尺寸。

    源图是 AI 生成的伪像素，画得很密，纯 NEAREST 会掉块、纯 LANCZOS 会发糊，
    实测 BOX（面积平均）再补一点锐化最清楚。
    锐化在 RGB 上做、做完把原 alpha 贴回去，避免半透明边缘被锐化出白边。
    """
    flat = Image.new("RGBA", logo.size, (0, 0, 0, 255))
    flat.alpha_composite(logo)
    small = flat.convert("RGB").resize((size, size), Image.BOX)
    small = small.filter(ImageFilter.UnsharpMask(radius=1.0, percent=140, threshold=2))
    out = small.convert("RGBA")
    out.putalpha(logo.split()[3].resize((size, size), Image.BOX))
    return out


def save_png(img, path):
    """原样保存（不做调色板量化，宁可大一点也别损失画质）。"""
    img.save(path, optimize=True)


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else None
    root = sys.argv[2] if len(sys.argv) > 2 else os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    if not src:
        print(__doc__)
        return 1

    im = Image.open(src).convert("RGB")
    l, t, r, b = find_box(im)
    print("logo 外接框:", (l, t, r, b), "边长", r - l)
    logo = strip_gray(im.crop((l, t, r, b)))
    side = logo.size[0]

    base = os.path.join(root, "res")
    for name, scale in DENSITIES.items():
        d = os.path.join(base, "mipmap-" + name)
        os.makedirs(d, exist_ok=True)

        # legacy 图标
        s = int(round(LEGACY_DP * scale))
        save_png(downscale(logo, s), os.path.join(d, "ic_launcher.png"))
        save_png(downscale(logo, s), os.path.join(d, "ic_launcher_round.png"))

        # adaptive 前景：108dp 画布，logo 居中占 CONTENT_SIZE_DP
        c = int(round(CANVAS_DP * scale))
        cs = int(round(CONTENT_SIZE_DP * scale))
        canvas = Image.new("RGBA", (c, c), (0, 0, 0, 0))
        canvas.paste(downscale(logo, cs), ((c - cs) // 2, (c - cs) // 2))
        save_png(canvas, os.path.join(d, "ic_launcher_foreground.png"))
        print("  %-8s legacy %3d  foreground %3d (logo %3d)" % (name, s, c, cs))

    # 供调试查看的中间产物
    dbg = os.path.join(root, "t")
    os.makedirs(dbg, exist_ok=True)
    logo.save(os.path.join(dbg, "icon_logo.png"))
    logo.resize((288, 288), Image.NEAREST).save(os.path.join(dbg, "icon_preview.png"))
    print("透明圆角 logo:", logo.size)
    return 0


if __name__ == "__main__":
    sys.exit(main())
