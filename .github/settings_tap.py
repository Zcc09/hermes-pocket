#!/usr/bin/env python3
"""Print "x y" for the Settings control in a uiautomator dump's top strip.

The gear lives in the app's 28dp session bar, so anything below y=200 is page
content that happens to mention Settings, not the control we want to tap.
Exits 0 with no output when the control is not there, so the shell can tell the
difference between "no position" and "no control".
"""

import re
import sys

BOUNDS = re.compile(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
LABEL = re.compile(r'(content-desc|text)="Settings"')


def main() -> int:
    if len(sys.argv) < 2:
        return 0
    try:
        xml = open(sys.argv[1], encoding="utf-8").read()
    except OSError:
        return 0

    for node in re.finditer(r"<node[^>]*>", xml):
        chunk = node.group(0)
        if not LABEL.search(chunk):
            continue
        box = BOUNDS.search(chunk)
        if not box:
            continue
        x1, y1, x2, y2 = (int(part) for part in box.groups())
        if y2 <= 200:
            print((x1 + x2) // 2, (y1 + y2) // 2)
            return 0
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
