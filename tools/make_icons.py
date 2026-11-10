#!/usr/bin/env python3
"""Generate every brand asset for the Hermes Pocket Android app.

Stdlib only (zlib + struct power a minimal built-in PNG writer; no PIL,
no third-party deps).  Running

    python tools/make_icons.py

from the repo root (or anywhere) (re)generates all assets below, byte for
byte identical on every run (deterministic writer, no timestamps):

  app/src/main/res/mipmap-mdpi..xxxhdpi/ic_launcher.png        48..192 px
  app/src/main/res/mipmap-mdpi..xxxhdpi/ic_launcher_round.png  48..192 px, circular mask
  app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml           adaptive icon
  app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml     adaptive icon (round)
  app/src/main/res/drawable/ic_launcher_background.xml         full-bleed tile + panels
  app/src/main/res/drawable/ic_launcher_foreground.xml         white "H" mark (vector)
  app/src/main/res/values/ic_launcher_background.xml           tile colour resource
  docs/index.html                                              project / privacy page

The raster icons are drawn as filled shapes (rounded square, triangles,
rounded rectangles) with 4x supersampling for anti-aliasing, composited as:
  tile #101720  ->  panels #2D7FF9 / #2EC4B6 at ~70% alpha  ->  glyph #FFFFFF
"""
from __future__ import annotations

import math
import os
import struct
import sys
import zlib

# ---------------------------------------------------------------------------
# design constants (108-unit grid -- the same grid as the adaptive icon)
# ---------------------------------------------------------------------------
DESIGN = 108.0

TILE = (0x10, 0x17, 0x20)   # #101720 base tile
BLUE = (0x2D, 0x7F, 0xF9)   # #2D7FF9 top-left panel
TEAL = (0x2E, 0xC4, 0xB6)   # #2EC4B6 bottom-right panel
WHITE = (255, 255, 255)

PANEL_ALPHA = 0.70          # panels sit at ~70% alpha over the tile
TILE_RADIUS = 0.225         # rounded-square corner radius (fraction of canvas)

# Anti-diagonal split with a tile-colour gap between the two panels:
BLUE_CUT = 100.0            # blue = { x + y <= 100 }                     (top-left)
TEAL_CUT = 116.0            # teal = { x + y >= 116 }                     (bottom-right)
TEAL_INSET = 8.0            #         { x >= 8, y >= 8 }

# The white "H" mark: two rounded vertical panels + a rounded connector.
# Extent 30..78 keeps it inside the 66dp-of-108 adaptive safe zone
# (21..87) and inside 80% of the legacy canvas.
GLYPH_RECTS = (
    (32.0, 30.0, 44.0, 78.0, 3.0),   # left panel
    (64.0, 30.0, 76.0, 78.0, 3.0),   # right panel
    (44.0, 51.0, 64.0, 57.0, 3.0),   # connector (capsule)
)

SS = 4                        # supersampling factor
LEGACY_SIZES = (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                ("xxhdpi", 144), ("xxxhdpi", 192))

# sanity: the glyph must respect the safe zones
_EDGE = (DESIGN - 66.0) / 2.0                        # 21 (66-of-108 safe zone)
assert min(r[0] for r in GLYPH_RECTS) >= _EDGE and min(r[1] for r in GLYPH_RECTS) >= _EDGE
assert max(r[2] for r in GLYPH_RECTS) <= DESIGN - _EDGE and max(r[3] for r in GLYPH_RECTS) <= DESIGN - _EDGE
assert min(r[0] for r in GLYPH_RECTS) >= 0.1 * DESIGN   # 80% legacy canvas rule
assert max(r[2] for r in GLYPH_RECTS) <= 0.9 * DESIGN


# ---------------------------------------------------------------------------
# minimal PNG writer (zlib + struct)
# ---------------------------------------------------------------------------
_PNG_SIG = b"\x89PNG\r\n\x1a\n"


def _chunk(tag: bytes, payload: bytes) -> bytes:
    return (struct.pack(">I", len(payload)) + tag + payload +
            struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF))


def write_png(path: str, size: int, rgba: bytes) -> None:
    """Write straight RGBA bytes as an 8-bit RGBA PNG (filter 0 per row)."""
    raw = bytearray()
    extend = raw.extend
    row = size * 4
    for y in range(size):
        extend(b"\x00")
        extend(rgba[y * row:(y + 1) * row])
    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    data = (_PNG_SIG
            + _chunk(b"IHDR", ihdr)
            + _chunk(b"IDAT", zlib.compress(bytes(raw), 9))
            + _chunk(b"IEND", b""))
    d = os.path.dirname(path)
    if d:
        os.makedirs(d, exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)


def read_ihdr(path: str):
    with open(path, "rb") as f:
        assert f.read(8) == _PNG_SIG, "not a PNG"
        length, tag = struct.unpack(">I4s", f.read(8))
        assert tag == b"IHDR" and length == 13
        width, height, depth, ctype = struct.unpack(">IIBB", f.read(10))
    return width, height, depth, ctype


# ---------------------------------------------------------------------------
# rasteriser
# ---------------------------------------------------------------------------
def _blend(src, dst, a: float):
    """src over opaque dst, both 0-255 ints."""
    return tuple(int(round(s * a + d * (1.0 - a))) for s, d in zip(src, dst))


def _downsample(buf: bytearray, n: int, size: int) -> bytes:
    """Average 4x4 supersampled blocks back down to the output size."""
    out = bytearray(size * size * 4)
    idx = 0
    for j in range(size):
        y0 = j * SS
        for i in range(size):
            x0 = i * SS
            cnt = sr = sg = sb = 0
            base = (y0 * n + x0) * 4
            for dy in range(SS):
                o = base + dy * n * 4
                for dx in range(SS):
                    p = o + dx * 4
                    if buf[p + 3]:
                        cnt += 1
                        sr += buf[p]
                        sg += buf[p + 1]
                        sb += buf[p + 2]
            if cnt:
                h = cnt // 2
                out[idx] = (sr + h) // cnt
                out[idx + 1] = (sg + h) // cnt
                out[idx + 2] = (sb + h) // cnt
            out[idx + 3] = (cnt * 255 + (SS * SS) // 2) // (SS * SS)
            idx += 4
    return bytes(out)


def render_icon(size: int, circular: bool) -> bytes:
    """Render one launcher icon at `size` px -> size*size*4 straight RGBA.

    circular=False: full-bleed rounded-square tile (AA corners, transparent surround)
    circular=True : same art, circular mask (inscribed circle)
    """
    n = size * SS
    k = n / DESIGN
    tr = TILE_RADIUS * n
    tr2 = tr * tr
    blue_cut = BLUE_CUT * k
    teal_cut = TEAL_CUT * k
    inset = TEAL_INSET * k
    center = n / 2.0
    c2 = center * center

    tile_col = TILE
    blue_col = _blend(BLUE, TILE, PANEL_ALPHA)
    teal_col = _blend(TEAL, TILE, PANEL_ALPHA)
    rects = [(a * k, b * k, c * k, d * k, e * k) for (a, b, c, d, e) in GLYPH_RECTS]

    buf = bytearray(n * n * 4)
    for y in range(n):
        py = y + 0.5
        if circular:
            dyy = py - center
            dy2 = dyy * dyy
            span = c2 - dy2
            if span < 0:
                continue
            rspan = math.sqrt(span)
            x_lo = max(0, int(center - rspan) - 1)
            x_hi = min(n - 1, int(center + rspan + 0.999) + 1)
            full_row = False
        else:
            if py < tr:
                dyy = py - tr
            elif py > n - tr:
                dyy = py - (n - tr)
            else:
                dyy = 0.0
            dy2 = dyy * dyy
            if dy2 > tr2:
                continue
            if dyy == 0.0:
                x_lo, x_hi, full_row = 0, n - 1, True
            else:
                rspan = math.sqrt(tr2 - dy2)
                x_lo = max(0, int(tr - rspan) - 1)
                x_hi = min(n - 1, int(n - tr + rspan) + 1)
                full_row = False

        has_blue = py < blue_cut
        has_teal = py >= inset and py + n - 0.5 >= teal_cut

        # glyph sub-rectangles that can hit this row.
        # rounded rect = core rect [x0+r..x1-r] x [y0+r..y1-r] grown by r:
        # a point is inside iff its distance to the (shrunk) core is <= r
        active = []
        for (x0, y0, x1, y1, rr) in rects:
            if py < y0 - rr or py > y1 + rr:
                continue
            cy = y0 + rr if py < y0 + rr else (y1 - rr if py > y1 - rr else py)
            cy2 = (py - cy) ** 2
            if cy2 > rr * rr:
                continue
            active.append((x0 + rr, x1 - rr, rr * rr, cy2))

        row = y * n
        for x in range(x_lo, x_hi + 1):
            px = x + 0.5
            if not full_row:
                if circular:
                    dxx = px - center
                else:
                    cxx = tr if px < tr else (n - tr if px > n - tr else px)
                    dxx = px - cxx
                if dxx * dxx + dy2 > (c2 if circular else tr2):
                    continue
            col = tile_col
            s = px + py
            if has_blue and s <= blue_cut:
                col = blue_col
            elif has_teal and px >= inset and s >= teal_cut:
                col = teal_col
            for (x0, x1, rr2, cy2) in active:  # x0/x1 already the core edges
                if px < x0:
                    dx = px - x0
                elif px > x1:
                    dx = px - x1
                else:
                    dx = 0.0
                if dx * dx + cy2 <= rr2:
                    col = WHITE
                    break
            o = (row + x) * 4
            buf[o] = col[0]
            buf[o + 1] = col[1]
            buf[o + 2] = col[2]
            buf[o + 3] = 255
    return _downsample(buf, n, size)


# ---------------------------------------------------------------------------
# XML assets
# ---------------------------------------------------------------------------
_ADAPTIVE_ICON = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
"""

_BG_VECTOR = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <!-- full-bleed base tile -->
    <path android:fillColor="@color/ic_launcher_background_color"
        android:pathData="M0,0H108V108H0Z" />
    <!-- top-left panel at ~70% over the tile (0xB3 == 179/255) -->
    <path android:fillColor="#B32D7FF9"
        android:pathData="M0,0H100L0,100Z" />
    <!-- bottom-right panel at ~70% over the tile -->
    <path android:fillColor="#B32EC4B6"
        android:pathData="M108,8V108H8Z" />
</vector>
"""

_FG_VECTOR = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <!-- "H" mark: two rounded vertical panels + rounded connector,
         extent 30..78 -> inside the 66dp-of-108 adaptive safe zone -->
    <path android:fillColor="#FFFFFF"
        android:pathData="M35,30h6a3,3 0,0,1 3,3v42a3,3 0,0,1 -3,3h-6a3,3 0,0,1 -3,-3v-42a3,3 0,0,1 3,-3z" />
    <path android:fillColor="#FFFFFF"
        android:pathData="M67,30h6a3,3 0,0,1 3,3v42a3,3 0,0,1 -3,3h-6a3,3 0,0,1 -3,-3v-42a3,3 0,0,1 3,-3z" />
    <path android:fillColor="#FFFFFF"
        android:pathData="M47,51h14a3,3 0,0,1 3,3a3,3 0,0,1 -3,3h-14a3,3 0,0,1 -3,-3a3,3 0,0,1 3,-3z" />
</vector>
"""

_ERROR_VECTOR = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="40dp"
    android:height="40dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <!-- line-art rounded square with a single break on the top edge -->
    <path
        android:fillColor="#00000000"
        android:strokeColor="#98A2B3"
        android:strokeWidth="1.5"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:pathData="M13,3H16A5,5 0 0,1 21,8V16A5,5 0 0,1 16,21H8A5,5 0 0,1 3,16V8A5,5 0 0,1 8,3" />
</vector>
"""

_COLOR_VALUES = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background_color">#FF101720</color>
</resources>
"""

# ---------------------------------------------------------------------------
# docs/index.html (project page / privacy statement)
# ---------------------------------------------------------------------------
_INDEX_HTML = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>Hermes Pocket</title>
<meta name="description" content="Hermes Pocket -- Hermes Agent, in your pocket." />
<style>
  :root {
    --bg: #0E1116;
    --tile: #101720;
    --blue: #2D7FF9;
    --teal: #2EC4B6;
    --text: #F4F6F8;
    --muted: #98A2B3;
    --line: #232C38;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0;
    background: var(--bg);
    color: var(--text);
    font-family: system-ui, -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    font-size: 16px;
    line-height: 1.5;
  }
  main { max-width: 560px; margin: 0 auto; padding: 72px 24px 48px; }
  header { display: flex; align-items: center; gap: 16px; margin-bottom: 32px; }
  .mark { width: 48px; height: 48px; flex: none; }
  h1 { margin: 0; font-size: 28px; line-height: 1.25; }
  h2 { margin: 0 0 12px; font-size: 18px; }
  p { margin: 0 0 16px; }
  .muted { color: var(--muted); margin-bottom: 0; }
  section { margin-top: 40px; padding-top: 32px; border-top: 1px solid var(--line); }
  ol { margin: 0; padding-left: 20px; }
  li { margin-bottom: 8px; }
  li:last-child { margin-bottom: 0; }
  a.btn {
    display: inline-block;
    margin-top: 8px;
    background: var(--blue);
    color: #FFFFFF;
    font-weight: 600;
    text-decoration: none;
    padding: 12px 24px;
    border-radius: 8px;
  }
  a.btn:hover { background: #468DF9; }
  footer {
    margin-top: 48px;
    padding-top: 16px;
    border-top: 1px solid var(--line);
    color: var(--muted);
    font-size: 13px;
  }
</style>
</head>
<body>
<main>
  <header>
    <svg class="mark" viewBox="0 0 108 108" role="img" aria-label="Hermes Pocket mark"
         xmlns="http://www.w3.org/2000/svg">
      <defs>
        <clipPath id="tile"><rect width="108" height="108" rx="24.3"/></clipPath>
      </defs>
      <g clip-path="url(#tile)">
        <rect width="108" height="108" fill="#101720"/>
        <path d="M0,0H100L0,100Z" fill="#2D7FF9" opacity="0.7"/>
        <path d="M108,8V108H8Z" fill="#2EC4B6" opacity="0.7"/>
        <path d="M35,30h6a3,3 0,0,1 3,3v42a3,3 0,0,1 -3,3h-6a3,3 0,0,1 -3,-3v-42a3,3 0,0,1 3,-3z" fill="#FFFFFF"/>
        <path d="M67,30h6a3,3 0,0,1 3,3v42a3,3 0,0,1 -3,3h-6a3,3 0,0,1 -3,-3v-42a3,3 0,0,1 3,-3z" fill="#FFFFFF"/>
        <path d="M47,51h14a3,3 0,0,1 3,3a3,3 0,0,1 -3,3h-14a3,3 0,0,1 -3,-3a3,3 0,0,1 3,-3z" fill="#FFFFFF"/>
      </g>
    </svg>
    <div>
      <h1>Hermes Pocket</h1>
      <p class="muted">Hermes Agent, in your pocket.</p>
    </div>
  </header>

  <p>Hermes Pocket is a small Android app that hosts your own Hermes Agent web
  app. On the first launch it asks for your address &mdash; an IP and port on your
  network, or the public URL you use &mdash; and remembers it. Then you can talk to
  your agent, follow what it is doing, and answer approvals from your phone.</p>

  <a class="btn" href="https://github.com/Zcc09/hermes-pocket/releases/latest">Download the APK</a>
  <p class="muted" style="margin-top:12px;font-size:13px">Direct APK &middot; Android 8 or newer &middot; about 2 MB</p>

  <section>
    <h2>How sign-in works</h2>
    <ol>
      <li>Enter your server: an IP and port, or your public URL.</li>
      <li>Sign in with the account that server already uses.</li>
      <li>Only that server is contacted &mdash; nothing is proxied or relayed.</li>
    </ol>
  </section>

  <section>
    <h2>Data</h2>
    <p>Hermes Pocket keeps one thing: the address you typed. No accounts, no
    logs, no analytics and no third-party calls &mdash; the app only renders the
    server you point it at, so the only data involved is the data that server
    already holds.</p>
  </section>

  <footer>
    Hermes Pocket &middot;
    <a href="https://github.com/Zcc09/hermes-pocket">source</a> &middot;
    <a href="https://github.com/Zcc09/hermes-pocket/blob/main/LICENSE">licence</a> &middot;
    <a href="https://github.com/Zcc09/hermes-pocket/releases">releases</a>
  </footer>
</main>
</body>
</html>
"""


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
def _write(path: str, data) -> None:
    d = os.path.dirname(path)
    if d:
        os.makedirs(d, exist_ok=True)
    if isinstance(data, str):
        data = data.encode("utf-8")
    with open(path, "wb") as f:
        f.write(data)


def main() -> int:
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    res = os.path.join(root, "app", "src", "main", "res")
    files = []  # (abs path, width or None, height or None)

    # 1) legacy launcher PNGs
    for density, size in LEGACY_SIZES:
        for name, circ in (("ic_launcher.png", False),
                           ("ic_launcher_round.png", True)):
            p = os.path.join(res, "mipmap-" + density, name)
            write_png(p, size, render_icon(size, circ))
            files.append((p, size, size))
            print("  wrote %s  (%dx%d, %d bytes)" %
                  (os.path.relpath(p, root), size, size, os.path.getsize(p)))

    # 2) adaptive icon + drawables + colour resource
    xml_assets = (
        (os.path.join(res, "mipmap-anydpi-v26", "ic_launcher.xml"), _ADAPTIVE_ICON),
        (os.path.join(res, "mipmap-anydpi-v26", "ic_launcher_round.xml"), _ADAPTIVE_ICON),
        (os.path.join(res, "drawable", "ic_launcher_background.xml"), _BG_VECTOR),
        (os.path.join(res, "drawable", "ic_launcher_foreground.xml"), _FG_VECTOR),
        (os.path.join(res, "values", "ic_launcher_background.xml"), _COLOR_VALUES),
    )
    for p, content in xml_assets:
        _write(p, content)
        files.append((p, None, None))
        print("  wrote %s  (%d bytes)" % (os.path.relpath(p, root), os.path.getsize(p)))

    # 3) project / privacy page
    p = os.path.join(root, "docs", "index.html")
    _write(p, _INDEX_HTML)
    files.append((p, None, None))
    print("  wrote %s  (%d bytes)" % (os.path.relpath(p, root), os.path.getsize(p)))

    # 4) verify: parse IHDR of every PNG, then print the full asset table
    rows = []
    for p, w, h in files:
        if w is not None:
            iw, ih, depth, ctype = read_ihdr(p)
            assert (iw, ih) == (w, h), "dimension mismatch: %s" % p
            assert depth == 8 and ctype == 6, "unexpected IHDR in %s" % p
        rows.append((os.path.relpath(p, root), w, h, os.path.getsize(p)))

    name_w = max(len(r[0]) for r in rows)
    print()
    print("ASSETS")
    fmt = "  {:<%d}  {:>5}  {:>6}  {:>8}" % name_w
    print(fmt.format("asset", "width", "height", "bytes"))
    for rel, w, h, size in rows:
        print(fmt.format(rel, str(w) if w else "-", str(h) if h else "-", str(size)))
    print()
    print("OK: %d assets generated (PNGs verified via IHDR; output is "
          "deterministic, re-running is idempotent)." % len(rows))
    return 0


if __name__ == "__main__":
    sys.exit(main())
