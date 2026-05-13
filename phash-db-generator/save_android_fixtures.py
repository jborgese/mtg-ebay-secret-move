"""Emit Android instrumented-test fixtures derived from the existing pHash test fixtures.

For each fixture in app/src/test/resources/phash-fixtures/{NN}/:
  - read grayscale.bin (1024 little-endian float64 values, 0..255)
  - reshape to 32x32 uint8 and save as grayscale.png
  - copy expected.txt + meta.txt unchanged

Output to app/src/androidTest/assets/phash-fixtures/{NN}/ so the instrumented
BitmapPhasherTest can load the PNG via `BitmapFactory` on a connected device.

The PNG is a 32x32 grayscale image whose pixels match the float matrix exactly.
That lets the test isolate the Bitmap → Mat → grayscale → resize → DoubleArray
plumbing without any Lanczos discrepancy (resize 32→32 is a no-op).

Run inside the venv:
    .\\.venv\\Scripts\\python.exe save_android_fixtures.py
"""
from __future__ import annotations

import shutil
import struct
import sys
from pathlib import Path

import numpy
from PIL import Image


def main() -> int:
    repo_root = Path(__file__).resolve().parent.parent
    src_dir = repo_root / "app" / "src" / "test" / "resources" / "phash-fixtures"
    dst_dir = repo_root / "app" / "src" / "androidTest" / "assets" / "phash-fixtures"

    if not src_dir.exists():
        print(f"FATAL: source fixtures not found at {src_dir}", file=sys.stderr)
        return 2

    dst_dir.mkdir(parents=True, exist_ok=True)

    written = 0
    for src_fixture in sorted(src_dir.iterdir()):
        if not src_fixture.is_dir():
            continue
        name = src_fixture.name
        gray_bin = src_fixture / "grayscale.bin"
        expected = src_fixture / "expected.txt"
        meta = src_fixture / "meta.txt"
        if not (gray_bin.exists() and expected.exists()):
            print(f"  [{name}] skip: missing inputs")
            continue

        # Read 1024 float64s and pack into a 32x32 uint8 array.
        raw = gray_bin.read_bytes()
        if len(raw) != 1024 * 8:
            print(f"  [{name}] skip: grayscale.bin is {len(raw)} bytes (expected 8192)")
            continue
        floats = struct.unpack(f"<{1024}d", raw)
        arr = numpy.array(floats, dtype=numpy.float64).reshape((32, 32))
        # Floats were cast from uint8 originally — they should already be in [0, 255].
        if arr.min() < 0 or arr.max() > 255:
            print(f"  [{name}] WARN: pixel range {arr.min()}..{arr.max()} outside [0,255]")
        u8 = numpy.clip(arr, 0, 255).astype(numpy.uint8)

        out_fixture = dst_dir / name
        out_fixture.mkdir(parents=True, exist_ok=True)
        Image.fromarray(u8, mode="L").save(out_fixture / "grayscale.png", optimize=True)
        shutil.copyfile(expected, out_fixture / "expected.txt")
        if meta.exists():
            shutil.copyfile(meta, out_fixture / "meta.txt")
        written += 1
        print(f"  [{name}] wrote PNG ({out_fixture / 'grayscale.png'})")

    print(f"Wrote {written} android fixtures to {dst_dir}")
    return 0 if written else 1


if __name__ == "__main__":
    sys.exit(main())
