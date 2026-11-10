#!/usr/bin/env python3
"""Every component the manifest names must exist in the APK.

A manifest can declare a class that no artifact ships - a provider from a
library that was not added, a renamed activity, a service in the wrong package.
Android instantiates ContentProviders and the Application before the first
frame, so one missing class means the app dies at launch with a black screen and
(if logging is unhelpful) no clue why. This check turns that into a red build.

Usage: python tools/check_components.py path/to/app.apk
Needs aapt2, from $ANDROID_HOME/build-tools/*/aapt2 (or --aapt2 PATH).
"""
from __future__ import annotations

import argparse
import glob
import os
import re
import subprocess
import sys
import zipfile

COMPONENT_TAG = re.compile(r"E: (activity|activity-alias|service|provider|receiver|application)\b")
NAME_ATTR = re.compile(r'android:name\(0x[0-9a-f]+\)="([^"]+)"')


def find_aapt2(explicit: str | None) -> str:
    if explicit:
        return explicit
    for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")):
        if not root:
            continue
        candidates = sorted(glob.glob(os.path.join(root, "build-tools", "*", "aapt2*")))
        for candidate in reversed(candidates):
            if os.path.isfile(candidate) and (candidate.endswith("aapt2") or candidate.endswith("aapt2.exe")):
                return candidate
    raise SystemExit("aapt2 not found: pass --aapt2 or set ANDROID_HOME")


def declared_components(apk: str, aapt2: str, package: str) -> list[tuple[str, str]]:
    out = subprocess.run(
        [aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk],
        capture_output=True, text=True, check=True,
    ).stdout
    components: list[tuple[str, str]] = []
    current: str | None = None
    for line in out.splitlines():
        match = COMPONENT_TAG.search(line)
        if match:
            current = match.group(1)
            continue
        if current is None:
            continue
        attr = NAME_ATTR.search(line)
        if not attr:
            continue
        name = attr.group(1)
        if name.startswith("."):
            name = package + name
        elif "." not in name:
            name = f"{package}.{name}"
        components.append((current, name))
        current = None
    return components


def dex_bytes(apk: str) -> bytes:
    with zipfile.ZipFile(apk) as archive:
        return b"".join(
            archive.read(name) for name in archive.namelist()
            if name.startswith("classes") and name.endswith(".dex")
        )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    parser.add_argument("--aapt2")
    parser.add_argument("--package", default="com.zcc09.hermespocket")
    args = parser.parse_args()

    aapt2 = find_aapt2(args.aapt2)
    components = declared_components(args.apk, aapt2, args.package)
    blob = dex_bytes(args.apk)
    print(f"{len(components)} components declared, {len(blob)} dex bytes")
    missing = []
    for kind, name in components:
        # The dex string pool holds the descriptor form with slashes.
        descriptor = name.replace(".", "/").encode()
        present = descriptor in blob
        print(f"  {'ok  ' if present else 'MISS'} {kind:<14} {name}")
        if not present:
            missing.append(f"{kind} {name}")
    if missing:
        print("\nFAIL: declared but absent from the dex (the app would crash at launch):")
        for item in missing:
            print(f"  - {item}")
        return 1
    print("OK: every declared component is in the APK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
