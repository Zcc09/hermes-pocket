#!/usr/bin/env python3
"""Print "x y" for the Settings control in a uiautomator dump's top strip.

The gear sits in the app's 28dp session bar. Two decoys have to be excluded:
page content that mentions Settings further down the screen, and the quick
settings shade's own "Settings" tile if the shade was still open — so a match
only counts when it belongs to the app's package and sits in the top strip.

Usage: settings_tap.py <dump.xml> [--package com.example.app] [--debug]
Exits 0 with no output when there is nothing to tap.
"""

import re
import sys

BOUNDS = re.compile(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
LABEL = re.compile(r'(content-desc|text)="Settings"')
PACKAGE = re.compile(r'package="([^"]+)"')
TOP_STRIP_PX = 200


def main() -> int:
    args = list(sys.argv[1:])
    package = None
    debug = False
    if "--package" in args:
        index = args.index("--package")
        package = args[index + 1] if len(args) > index + 1 else None
        del args[index:index + 2]
    if "--debug" in args:
        debug = True
        args.remove("--debug")

    if not args:
        return 0
    try:
        xml = open(args[0], encoding="utf-8").read()
    except OSError:
        return 0

    for node in re.finditer(r"<node[^>]*>", xml):
        chunk = node.group(0)
        box = BOUNDS.search(chunk)
        if not box:
            continue
        x1, y1, x2, y2 = (int(part) for part in box.groups())
        labelled = bool(LABEL.search(chunk))
        owner = PACKAGE.search(chunk)
        owner = owner.group(1) if owner else None
        if debug and labelled:
            print(f"candidate package={owner} bounds=[{x1},{y1}][{x2},{y2}]", file=sys.stderr)
        if not labelled:
            continue
        if package and owner not in (None, package):
            continue
        if y2 > TOP_STRIP_PX:
            continue
        print((x1 + x2) // 2, (y1 + y2) // 2)
        return 0
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
