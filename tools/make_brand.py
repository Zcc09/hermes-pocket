#!/usr/bin/env python3
"""Generate the launcher art: a white H on black, the same mark as the shade.

The app's notification uses `drawable/ic_notify.xml` — a white H built from two
rounded bars and a connector. The launcher icon is that same glyph, drawn from
the same 24-unit geometry, on a black tile, so the icon in the launcher, the
notification and the status bar all read as one identity.

Every asset comes from the same draw calls, so the sizes cannot drift:

  mipmap-*/ic_launcher.png             48..192 px, black rounded tile + H
  mipmap-*/ic_launcher_round.png       same, circular tile
  mipmap-*/ic_launcher_foreground.png  108..432 px, H only, transparent,
                                       sized for the adaptive icon's safe zone
  mipmap-anydpi-v26/ic_launcher*.xml   black background + PNG foreground
  values/ic_launcher_background.xml    the tile colour

Run from the repo root:  python tools/make_brand.py
Requires Pillow (dev-time only; the app itself has no such dependency).
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageChops, ImageDraw

REPO = Path(__file__).resolve().parent.parent
RES = REPO / "app" / "src" / "main" / "res"

TILE = (0x00, 0x00, 0x00)
GLYPH = (0xFF, 0xFF, 0xFF)
GRID = 108.0

DENSITIES = (("mdpi", 1.0), ("hdpi", 1.5), ("xhdpi", 2.0), ("xxhdpi", 3.0), ("xxxhdpi", 4.0))

# The H in the notification icon's own 24-unit space: two bars and a connector.
BARS = ((5.0, 3.0, 8.2, 21.0), (15.8, 3.0, 19.0, 21.0))
CONNECTOR = (8.2, 10.9, 15.8, 13.1)   # touches both bars: a real H, not three pieces
MARK_W = 14.0   # 19.0 - 5.0
MARK_H = 18.0   # 21.0 - 3.0


def monogram(size: int, span: float) -> Image.Image:
    """The white H on transparent, centred.

    *span* is the height of the mark as a fraction of the canvas, so the adaptive
    foreground can stay inside the 66dp safe zone while the legacy tile carries a
    bolder stroke of the same shape.
    """
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    u = size / GRID
    scale = (span * GRID) / MARK_H * u   # px per notification unit

    def point(x: float, y: float) -> tuple[float, float]:
        # centre the mark's bounding box on the canvas centre
        return (54 * u + (x - 12.0) * scale, 54 * u + (y - 12.0) * scale)

    for x1, y1, x2, y2 in BARS:
        left, top = point(x1, y1)
        right, bottom = point(x2, y2)
        d.rounded_rectangle([left, top, right, bottom], radius=(right - left) * 0.34, fill=GLYPH)

    left, top = point(CONNECTOR[0], CONNECTOR[1])
    right, bottom = point(CONNECTOR[2], CONNECTOR[3])
    d.rounded_rectangle([left, top, right, bottom], radius=(bottom - top) / 2, fill=GLYPH)
    return img


def tile(size: int, round_mask: bool) -> Image.Image:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if round_mask:
        d.ellipse([0, 0, size - 1, size - 1], fill=TILE)
    else:
        d.rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * 0.225), fill=TILE)

    art = monogram(size, span=0.62)
    if round_mask:
        clip = Image.new("L", (size, size), 0)
        ImageDraw.Draw(clip).ellipse([0, 0, size - 1, size - 1], fill=255)
        art.putalpha(ImageChops.multiply(art.getchannel("A"), clip))
    return Image.alpha_composite(img, art)


ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background_color" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""

COLOUR_XML = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background_color">#FF000000</color>
</resources>
"""


def main() -> int:
    written = []
    for name, scale in DENSITIES:
        folder = RES / f"mipmap-{name}"
        folder.mkdir(parents=True, exist_ok=True)

        legacy = int(48 * scale)
        tile(legacy, round_mask=False).save(folder / "ic_launcher.png")
        tile(legacy, round_mask=True).save(folder / "ic_launcher_round.png")

        # adaptive foreground: the H stays inside the 66dp safe zone
        monogram(int(108 * scale), span=0.52).save(folder / "ic_launcher_foreground.png")
        written += [f"mipmap-{name}/ic_launcher.png", f"mipmap-{name}/ic_launcher_round.png",
                    f"mipmap-{name}/ic_launcher_foreground.png"]

    anydpi = RES / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    (anydpi / "ic_launcher.xml").write_text(ADAPTIVE_XML, encoding="utf-8")
    (anydpi / "ic_launcher_round.xml").write_text(ADAPTIVE_XML, encoding="utf-8")
    written += ["mipmap-anydpi-v26/ic_launcher.xml", "mipmap-anydpi-v26/ic_launcher_round.xml"]

    colour = RES / "values" / "ic_launcher_background.xml"
    colour.parent.mkdir(parents=True, exist_ok=True)
    colour.write_text(COLOUR_XML, encoding="utf-8")
    written.append("values/ic_launcher_background.xml")

    for item in written:
        print(f"  {item}")
    print(f"{len(written)} files written")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
