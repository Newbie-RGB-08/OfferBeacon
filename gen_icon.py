import math, os, struct, zlib

SIZE = 512
SS = 3  # supersample factor per axis


def lerp(a, b, t):
    return a + (b - a) * t


def sd_round_rect(px, py, cx, cy, hw, hh, r):
    qx = abs(px - cx) - (hw - r)
    qy = abs(py - cy) - (hh - r)
    ax = max(qx, 0.0)
    ay = max(qy, 0.0)
    return math.hypot(ax, ay) + min(max(qx, qy), 0.0) - r


def sd_circle(px, py, cx, cy, r):
    return math.hypot(px - cx, py - cy) - r


def sd_capsule(px, py, ax_, ay_, bx_, by_, r):
    pax, pay = px - ax_, py - ay_
    bax, bay = bx_ - ax_, by_ - ay_
    denom = bax * bax + bay * bay
    h = 0.0 if denom == 0 else max(0.0, min(1.0, (pax * bax + pay * bay) / denom))
    return math.hypot(pax - bax * h, pay - bay * h) - r


def sd_annulus(px, py, cx, cy, r_in, r_out):
    dist = math.hypot(px - cx, py - cy)
    return max(r_in - dist, dist - r_out)


def coverage(fn, x, y):
    """4x4-ish supersample inside one pixel."""
    hits = 0
    for i in range(SS):
        for j in range(SS):
            sx = x + (i + 0.5) / SS
            sy = y + (j + 0.5) / SS
            if fn(sx, sy) <= 0:
                hits += 1
    return hits / float(SS * SS)


def build_master():
    W = SIZE
    px = bytearray()
    cx = cy = W / 2.0
    half = W / 2.0

    # brand gradient
    c1 = (99, 102, 241)    # indigo
    c2 = (139, 92, 246)    # violet
    c3 = (56, 189, 248)    # sky accent

    def bg(sx, sy):
        return sd_round_rect(sx, sy, cx, cy, half, half, 112.0)

    def mark(sx, sy):
        d = sd_circle(sx, sy, 256.0, 206.0, 34.0)            # beacon light
        d = min(d, sd_annulus(sx, sy, 256.0, 206.0, 58.0, 72.0))  # halo ring
        d = min(d, sd_capsule(sx, sy, 256.0, 246.0, 256.0, 390.0, 24.0))  # tower
        d = min(d, sd_capsule(sx, sy, 196.0, 416.0, 316.0, 416.0, 21.0))  # ground line
        return d

    rows = []
    for y in range(W):
        row = bytearray()
        for x in range(W):
            a_bg = coverage(bg, x, y)
            if a_bg <= 0.0:
                row += b"\x00\x00\x00\x00"
                continue
            t = (x * 0.55 + y * 0.45) / float(W)
            r = lerp(c1[0], c2[0], t)
            g = lerp(c1[1], c2[1], t)
            b = lerp(c1[2], c2[2], t)
            # subtle sky tint at bottom-right corner
            r = lerp(r, c3[0], max(0.0, (x + y - W) / float(W)) * 0.12)
            g = lerp(g, c3[1], max(0.0, (x + y - W) / float(W)) * 0.12)
            b = lerp(b, c3[2], max(0.0, (x + y - W) / float(W)) * 0.12)

            a_mark = coverage(mark, x, y)
            if a_mark > 0:
                r = lerp(r, 255.0, a_mark)
                g = lerp(g, 255.0, a_mark)
                b = lerp(b, 255.0, a_mark)
            row += bytes((int(r), int(g), int(b), int(255 * a_bg)))
        rows.append(bytes(row))
    return rows


def write_png(path, rows, w, h):
    raw = b"".join(b"\x00" + r for r in rows)
    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def resize(rows, w, h, nw, nh):
    out = []
    fx = w / float(nw)
    fy = h / float(nh)
    for y in range(nh):
        row = bytearray()
        y0 = int(y * fy)
        y1 = max(y0 + 1, int((y + 1) * fy))
        for x in range(nw):
            x0 = int(x * fx)
            x1 = max(x0 + 1, int((x + 1) * fx))
            acc = [0, 0, 0, 0]
            cnt = 0
            for yy in range(y0, min(y1, h)):
                src = rows[yy]
                for xx in range(x0, min(x1, w)):
                    i = xx * 4
                    acc[0] += src[i]; acc[1] += src[i + 1]; acc[2] += src[i + 2]; acc[3] += src[i + 3]
                    cnt += 1
            cnt = max(cnt, 1)
            row += bytes((acc[0] // cnt, acc[1] // cnt, acc[2] // cnt, acc[3] // cnt))
        out.append(bytes(row))
    return out


def main():
    base = os.path.dirname(os.path.abspath(__file__))
    rows = build_master()
    targets = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    for folder, size in targets.items():
        d = os.path.join(base, "app", "res", folder)
        os.makedirs(d, exist_ok=True)
        small = resize(rows, SIZE, SIZE, size, size)
        write_png(os.path.join(d, "ic_launcher.png"), small, size, size)
        print("wrote", folder, size)
    # 512 master for reference / store listing
    write_png(os.path.join(base, "ic_launcher-512.png"), rows, SIZE, SIZE)
    print("done")


if __name__ == "__main__":
    main()
