import pngpeek, zlib, struct, sys
def crop(src, x0, y0, x1, y1, scale, out):
    w, h, ch, buf = pngpeek.read_png(src)
    x1 = min(x1, w); y1 = min(y1, h)
    ow = (x1 - x0) * scale; oh = (y1 - y0) * scale
    rows = []
    for oy in range(oh):
        sy = y0 + oy // scale
        base = sy * w * ch
        row = bytearray()
        for ox in range(ow):
            i = base + (x0 + ox // scale) * ch
            row += bytes((buf[i], buf[i+1], buf[i+2]))
        rows.append(bytes(row))
    raw = b''.join(b'\x00' + r for r in rows)
    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    png = (b'\x89PNG\r\n\x1a\n'
           + chunk(b'IHDR', struct.pack('>IIBBBBB', ow, oh, 8, 2, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))
    open(out, 'wb').write(png)
    print(out, ow, oh)
if __name__ == '__main__':
    crop(sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5]), int(sys.argv[6]), sys.argv[7])
