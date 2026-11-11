#!/usr/bin/env python3
"""Print "x y" for a labelled control in a uiautomator dump.

Used by the smoke suite to tap a control the way a person would, without
guessing coordinates. Two decoys are excluded: content further down the screen
that happens to share the label, and system surfaces (the quick-settings shade)
that carry their own copy of it — so a match only counts when it belongs to the
app's package and sits in the top strip.

Usage: ui_tap.py <dump.xml> <label> [--package com.example.app] [--max-y N] [--debug]
Exits 0 with no output when there is nothing to tap.
"""

import re
import sys

BOUNDS = re.compile(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
PACKAGE = re.compile(r'package="([^"]+)"')
FALLBACK_HEIGHT = 2400
TOP_STRIP_FRACTION = 0.2


def main() -> int:
    args = list(sys.argv[1:])
    package = None
    max_y = None
    debug = False
    for flag in ("--package", "--max-y"):
        if flag in args:
            index = args.index(flag)
            value = args[index + 1] if len(args) > index + 1 else None
            if flag == "--package":
                package = value
            else:
                max_y = int(value) if value and value.isdigit() else None
            del args[index:index + 2]
    if "--debug" in args:
        debug = True
        args.remove("--debug")
    if len(args) < 2:
        return 0

    dump, label = args[0], args[1]
    try:
        xml = open(dump, encoding="utf-8").read()
    except OSError:
        return 0

    wanted = re.compile(r'(content-desc|text)="%s"' % re.escape(label))
    nodes = list(re.finditer(r"<node[^>]*>", xml))

    if max_y is None:
        screen_height = FALLBACK_HEIGHT
        for node in nodes:
            box = BOUNDS.search(node.group(0))
            if box:
                screen_height = max(screen_height, int(box.group(4)))
                break
        max_y = int(screen_height * TOP_STRIP_FRACTION)

    for node in nodes:
        chunk = node.group(0)
        box = BOUNDS.search(chunk)
        if not box:
            continue
        x1, y1, x2, y2 = (int(part) for part in box.groups())
        labelled = bool(wanted.search(chunk))
        owner = PACKAGE.search(chunk)
        owner = owner.group(1) if owner else None
        if debug and labelled:
            print(f"candidate {label!r} package={owner} bounds=[{x1},{y1}][{x2},{y2}]", file=sys.stderr)
        if not labelled:
            continue
        if package and owner not in (None, package):
            continue
        if max_y and y2 > max_y:
            continue
        print((x1 + x2) // 2, (y1 + y2) // 2)
        return 0
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
