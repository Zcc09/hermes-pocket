#!/usr/bin/env python3
"""Generate the launcher art: a portrait medallion in the Hermes idiom.

The mark is a pale medallion on the app's dark tile, holding a black-bob bust —
one heavy hair mass, a straight fringe, two eyes, a small mouth, and a teal clip.
It is deliberately a sibling of the Hermes portrait logo, not a copy: different
silhouette, different accessory, and a medallion frame the logo does not have.

Every asset is derived from the same draw calls, so the sizes cannot drift:

  mipmap-*/ic_launcher.png           48..192 px, rounded tile
  mipmap-*/ic_launcher_round.png     same, circular tile
  mipmap-*/ic_launcher_foreground.png  108..432 px, portrait only, transparent,
                                       sized for the adaptive icon's safe zone
  mipmap-anydpi-v26/ic_launcher*.xml   point at the PNG foreground

Run from the repo root:  python tools/make_brand.py
Requires Pillow (dev-time only; the app itself has no such dependency).
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageChops, ImageDraw

REPO = Path(__file__).resolve().parent.parent
RES = REPO / "app" / "src" / "main" / "res"

TILE = (0x10, 0x17, 0x20)
TEAL = (0x2E, 0xC4, 0xB6)
BONE = (0xF6, 0xF3, 0xEC)
INK = (0x0B, 0x0E, 0x13)

DENSITIES = (("mdpi", 1.0), ("hdpi", 1.5), ("xhdpi", 2.0), ("xxhdpi", 3.0), ("xxxhdpi", 4.0))


def portrait(size: int, radius: float) -> Image.Image:
    """The medallion and bust, transparent outside the disc.

    *radius* is in 108-unit design units, so the adaptive foreground (which is
    cropped by the launcher's mask) passes the smaller 36 and the legacy icons
    pass the fuller 44.
    """
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    u = size / 108.0

    cx = cy = 54 * u
    r = radius * u
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=BONE)

    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse([cx - r, cy - r, cx + r, cy + r], fill=255)
    inner = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    i = ImageDraw.Draw(inner)

    # proportions relative to the disc, so both radii look the same
    k = radius / 44.0

    def y(value: float) -> float:
        return (54 + (value - 54) * k) * u

    def x(value: float) -> float:
        return (54 + (value - 54) * k) * u

    # bust: shoulders clipped by the disc, pale neck between them and the chin
    i.ellipse([x(30), y(86), x(78), y(112)], fill=INK)
    i.rectangle([x(50), y(70), x(58), y(88)], fill=BONE)

    # bob: crown and a straight fringe
    i.ellipse([x(35), y(30), x(73), y(76)], fill=INK)
    i.rectangle([x(35), y(52), x(73), y(58)], fill=INK)

    # face
    i.polygon(
        [(x(40), y(56)), (x(68), y(56)), (x(66), y(70)), (x(58), y(79)),
         (x(50), y(79)), (x(42), y(68))],
        fill=BONE,
    )

    # two eyes, one mouth, nothing else
    i.ellipse([x(44), y(60), x(53), y(66)], fill=INK)
    i.ellipse([x(56), y(60), x(65), y(66)], fill=INK)
    i.arc([x(50), y(68), x(60), y(76)], start=10, end=170, fill=INK, width=max(2, int(3.2 * u * k)))

    # medallion rim just inside the disc edge, then the clip on the fringe line
    rim = r * 0.94
    i.ellipse([cx - rim, cy - rim, cx + rim, cy + rim], outline=INK, width=max(1, int(2.0 * u)))
    i.ellipse([x(61), y(45), x(74), y(58)], fill=TEAL)
    i.ellipse([x(61), y(45), x(74), y(58)], outline=INK, width=max(1, int(1.8 * u * k)))

    inner.putalpha(ImageChops.multiply(inner.getchannel("A"), mask))
    return Image.alpha_composite(img, inner)


def tile(size: int, round_mask: bool) -> Image.Image:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if round_mask:
        d.ellipse([0, 0, size - 1, size - 1], fill=TILE)
    else:
        d.rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * 0.225), fill=TILE)
    art = portrait(size, radius=44.0)
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


def main() -> int:
    written = []
    for name, scale in DENSITIES:
        folder = RES / f"mipmap-{name}"
        folder.mkdir(parents=True, exist_ok=True)

        legacy = int(48 * scale)
        tile(legacy, round_mask=False).save(folder / "ic_launcher.png")
        tile(legacy, round_mask=True).save(folder / "ic_launcher_round.png")

        foreground = int(108 * scale)
        # adaptive foreground: the medallion fits the 66dp safe zone with margin
        portrait(foreground, radius=29.0).save(folder / "ic_launcher_foreground.png")
        written += [f"mipmap-{name}/ic_launcher.png", f"mipmap-{name}/ic_launcher_round.png",
                    f"mipmap-{name}/ic_launcher_foreground.png"]

    anydpi = RES / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    (anydpi / "ic_launcher.xml").write_text(ADAPTIVE_XML, encoding="utf-8")
    (anydpi / "ic_launcher_round.xml").write_text(ADAPTIVE_XML, encoding="utf-8")
    written += ["mipmap-anydpi-v26/ic_launcher.xml", "mipmap-anydpi-v26/ic_launcher_round.xml"]

    for item in written:
        print(f"  {item}")
    print(f"{len(written)} files written")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
