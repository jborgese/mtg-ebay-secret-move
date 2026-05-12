"""Emit golden-fixture test data for the Kotlin Phasher unit tests.

For each of 10 sampled cards from the local image cache, produces:

  app/src/test/resources/phash-fixtures/{NN}/
      grayscale.bin   1024 little-endian float64 (32x32 row-major, uint8 values cast to f64)
      expected.txt    "0x" + 16 hex chars (the imagehash.phash u64 packed as in generate.py)
      meta.txt        scryfall_id, name, set, collector_number, full_art flag (debug only)

Internally we replay the same crop/grayscale/resize/DCT pipeline as generate.py
and cross-check that our hand-computed pHash matches imagehash.phash() called on
the cropped color image. If those ever disagree, abort — the fixture would be
inconsistent.

Run from the phash-db-generator directory inside the venv:
    .\\.venv\\Scripts\\python.exe save_fixtures.py
"""
from __future__ import annotations

import random
import struct
import sys
import time
from pathlib import Path

import imagehash
import numpy
import requests
import scipy.fftpack
from PIL import Image

# Reuse constants + crop rect from the main generator script.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate import ART_CROP, USER_AGENT, FINISH_BITS  # noqa: E402

NUM_FIXTURES = 10
SEED = 42
RATE_LIMIT_S = 0.1


def pack_to_u64(hash_obj: imagehash.ImageHash) -> int:
    """Pack the 8x8 boolean ImageHash into a u64 the same way generate.py does."""
    bits = 0
    for i, b in enumerate(hash_obj.hash.flatten()):
        if b:
            bits |= 1 << i
    return bits & ((1 << 64) - 1)


def manual_phash(pixels_u8: numpy.ndarray) -> int:
    """Replicate imagehash.phash internals on a pre-resized 32x32 uint8 array."""
    assert pixels_u8.shape == (32, 32) and pixels_u8.dtype == numpy.uint8
    dct = scipy.fftpack.dct(scipy.fftpack.dct(pixels_u8.astype(numpy.float64), axis=0), axis=1)
    dctlowfreq = dct[:8, :8]
    med = numpy.median(dctlowfreq)
    diff = dctlowfreq > med
    return pack_to_u64(imagehash.ImageHash(diff))


def fetch_card(scryfall_id: str, session: requests.Session) -> dict:
    r = session.get(
        f"https://api.scryfall.com/cards/{scryfall_id}",
        headers={"User-Agent": USER_AGENT},
        timeout=15,
    )
    r.raise_for_status()
    return r.json()


def main() -> int:
    here = Path(__file__).resolve().parent
    cache_dir = here / ".cache" / "images"
    if not cache_dir.exists():
        print(f"FATAL: image cache not found at {cache_dir}", file=sys.stderr)
        return 2

    out_dir = here.parent / "app" / "src" / "test" / "resources" / "phash-fixtures"
    out_dir.mkdir(parents=True, exist_ok=True)

    all_ids = sorted(d.name for d in cache_dir.iterdir() if d.is_dir())
    print(f"Cache has {len(all_ids)} cards. Sampling {NUM_FIXTURES} (seed={SEED}).")
    rng = random.Random(SEED)
    chosen = rng.sample(all_ids, NUM_FIXTURES)

    session = requests.Session()
    written = 0

    for idx, sid in enumerate(chosen):
        img_dir = cache_dir / sid
        candidates = sorted(p for p in img_dir.iterdir() if p.is_file())
        if not candidates:
            print(f"  [{idx:02d}] skip {sid}: no cached image")
            continue
        img_path = candidates[0]

        try:
            card = fetch_card(sid, session)
        except Exception as e:
            print(f"  [{idx:02d}] skip {sid}: scryfall fetch failed: {e}")
            continue
        time.sleep(RATE_LIMIT_S)

        try:
            img = Image.open(img_path).convert("RGB")
        except Exception as e:
            print(f"  [{idx:02d}] skip {sid}: image load failed: {e}")
            continue

        full_frame = bool(card.get("full_art")) or card.get("border_color") == "borderless"
        if not full_frame:
            w, h = img.size
            l, t, r, b = ART_CROP
            img = img.crop((int(w * l), int(h * t), int(w * r), int(h * b)))

        # Replicate imagehash.phash's grayscale + resize step.
        resized = img.convert("L").resize((32, 32), Image.Resampling.LANCZOS)
        pixels = numpy.asarray(resized, dtype=numpy.uint8)
        assert pixels.shape == (32, 32)

        # Hand-computed hash, then sanity-check against imagehash.phash on the cropped image.
        manual = manual_phash(pixels)
        ground_truth = pack_to_u64(imagehash.phash(img, hash_size=8))
        if manual != ground_truth:
            print(
                f"  [{idx:02d}] FATAL {sid}: manual=0x{manual:016x} != "
                f"imagehash.phash=0x{ground_truth:016x}",
                file=sys.stderr,
            )
            return 3

        # Pack into fixture: grayscale.bin (float64 LE, row-major), expected.txt, meta.txt.
        fixture_dir = out_dir / f"{idx:02d}"
        fixture_dir.mkdir(parents=True, exist_ok=True)

        floats = pixels.astype(numpy.float64).flatten()  # 1024 entries, row-major
        assert floats.shape == (1024,)
        gray_bytes = struct.pack(f"<{len(floats)}d", *floats)
        (fixture_dir / "grayscale.bin").write_bytes(gray_bytes)

        (fixture_dir / "expected.txt").write_text(f"0x{manual:016x}\n", encoding="utf-8")

        finishes_str = ",".join(card.get("finishes", []))
        (fixture_dir / "meta.txt").write_text(
            f"scryfall_id: {sid}\n"
            f"name: {card.get('name')}\n"
            f"set: {card.get('set')}\n"
            f"collector_number: {card.get('collector_number')}\n"
            f"layout: {card.get('layout')}\n"
            f"full_art_or_borderless: {full_frame}\n"
            f"finishes: {finishes_str}\n"
            f"source_image: {img_path.name}\n"
            f"expected_phash: 0x{manual:016x}\n",
            encoding="utf-8",
        )

        print(f"  [{idx:02d}] {sid[:8]} {card.get('set'):>5} {card.get('collector_number'):>4}  0x{manual:016x}  {card.get('name')}")
        written += 1

    print(f"Wrote {written}/{NUM_FIXTURES} fixtures to {out_dir}")
    return 0 if written == NUM_FIXTURES else 1


if __name__ == "__main__":
    sys.exit(main())
