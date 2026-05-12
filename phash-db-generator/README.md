# phash-db-generator

Standalone tool that produces `phash.bin` — the perceptual-hash database the
Android app uses to identify cards from camera frames.

## What it does

1. Reads Scryfall's `default-cards` bulk export (all printings, English only).
2. Downloads each card's `normal` image to a local cache (~12 GB at full run).
3. Crops the art region (or hashes the whole image for full-art / borderless prints).
4. Computes a 64-bit DCT perceptual hash via `imagehash`.
5. Writes a compact binary file the on-device matcher binary-searches by hash.

## Setup (one-time, on a dev machine — not the phone)

```powershell
cd phash-db-generator
python -m venv .venv
.venv\Scripts\Activate.ps1     # PowerShell; use Scripts\activate.bat in cmd
pip install -r requirements.txt
```

## Usage

```powershell
# Smoke test: hash 50 cards (a few seconds, validates the pipeline).
python generate.py --limit 50

# Full run: hashes every English printing. First run is 3-6 hours and downloads
# ~12 GB of images. Subsequent runs reuse the cache and finish in minutes.
python generate.py

# Copy the result into the app assets directory before building:
copy out\phash.bin ..\app\src\main\assets\phash.bin
```

## Output format

Little-endian, fixed-width binary. Header followed by sorted records.

```
Header  : "MTGP" (4 B) | u16 version=1 | u32 record_count
Record  : u64 phash | 16 B scryfall_id (UUID raw) | u8 finishes_bitmask
        | 8 B set_code (UTF-8, NUL-padded) | 8 B collector_number (UTF-8, NUL-padded)
```

`finishes_bitmask`: bit0=nonfoil, bit1=foil, bit2=etched, bit3=glossy.

Records are sorted by `phash` ascending so the Android matcher can binary-search.

## Re-running

Regenerate when a new MTG set ships. The image cache makes incremental runs fast.
The output is deterministic for a given Scryfall bulk export.

## What gets skipped

- Non-English printings (`lang != "en"`).
- Tokens, double-faced tokens, emblems, art series, planar/scheme/vanguard layouts,
  reversible novelty cards (the app doesn't list those).
- Digital-only printings (`digital == true`).
