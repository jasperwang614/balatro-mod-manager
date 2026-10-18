#!/usr/bin/env python3
"""删除 .class 里的 MethodParameters 属性。

原因：javac 21 会给带合成参数的匿名类写 MethodParameters，
其中合成参数（this$0）的 name_index = 0（null）。
d8 8.2 读到这里会抛 NullPointerException: Cannot invoke "String.length()"。
该属性对运行没有任何作用，直接去掉最省事。
"""
import os
import struct
import sys


def u1(b, o):
    return b[o], o + 1


def u2(b, o):
    return struct.unpack_from('>H', b, o)[0], o + 2


def u4(b, o):
    return struct.unpack_from('>I', b, o)[0], o + 4


def read_cp(b, o):
    count, o = u2(b, o)
    cp = [None]
    i = 1
    while i < count:
        tag, o = u1(b, o)
        if tag == 1:
            ln, o = u2(b, o)
            cp.append(b[o:o + ln].decode('utf-8', 'replace'))
            o += ln
        elif tag in (7, 8, 16, 19, 20):
            cp.append(tag)
            o += 2
        elif tag == 15:
            cp.append(tag)
            o += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            cp.append(tag)
            o += 4
        elif tag in (5, 6):
            cp.append(tag)
            cp.append(None)
            o += 8
            i += 1
        else:
            raise ValueError('unknown constant pool tag %d' % tag)
        i += 1
    return cp, o


def strip_members(b, o, mp_idx):
    cnt, o = u2(b, o)
    out = bytearray(struct.pack('>H', cnt))
    for _ in range(cnt):
        head = o
        o += 6
        alen, o = u2(b, o)
        kept = []
        for _ in range(alen):
            start = o
            name_i, o = u2(b, o)
            blen, o = u4(b, o)
            o += blen
            if name_i != mp_idx:
                kept.append(b[start:o])
        out += b[head:head + 6]
        out += struct.pack('>H', len(kept))
        for k in kept:
            out += k
    return out, o


def process(data):
    if data[:4] != b'\xca\xfe\xba\xbe':
        return data, False
    o = 8
    cp, o = read_cp(data, o)
    mp_idx = None
    for i, v in enumerate(cp):
        if isinstance(v, str) and v == 'MethodParameters':
            mp_idx = i
            break
    if mp_idx is None:
        return data, False

    out = bytearray(data[:o])
    out += data[o:o + 6]
    o += 6
    ic, o = u2(data, o)
    out += struct.pack('>H', ic) + data[o:o + 2 * ic]
    o += 2 * ic

    f, o = strip_members(data, o, mp_idx)
    out += f
    m, o = strip_members(data, o, mp_idx)
    out += m
    out += data[o:]
    return bytes(out), True


def main(root):
    n = 0
    total = 0
    for dirpath, _, files in os.walk(root):
        for fn in files:
            if not fn.endswith('.class'):
                continue
            p = os.path.join(dirpath, fn)
            total += 1
            with open(p, 'rb') as f:
                data = f.read()
            new, changed = process(data)
            if changed:
                with open(p, 'wb') as f:
                    f.write(new)
                n += 1
    print('扫描 %d 个 class，剥离 MethodParameters 的有 %d 个' % (total, n))


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else '.')
