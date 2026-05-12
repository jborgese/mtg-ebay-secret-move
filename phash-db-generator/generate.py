"""Generate phash.bin: a perceptual-hash database of every English MTG printing.

Reads Scryfall's `default-cards` bulk export, downloads the normal-size image for
each card (with on-disk cache), crops the art region, computes a 64-bit pHash via
the standard imagehash library, and emits a packed binary file consumed by the
Android app.

Output format (little-endian, fixed-width):

  header  : magic "MTGP" (4 B) | u16 version=1 | u32 record_count
  records : sorted by phash ascending, each 41 bytes:
            u64  phash
            16B  scryfall_id (UUID raw bytes)
            u8   finishes_bitmask  (bit0=nonfoil, bit1=foil, bit2=etched, bit3=glossy)
            8B   set_code         (UTF-8, NUL-padded)
            8B   collector_number (UTF-8, NUL-padded; truncated if longer)

The matching code in the Android app does a binary search by pHash then a
linear scan within a Hamming radius (default 12).

Usage:
    python -m venv .venv
    .venv\\Scripts\\activate     # Windows; use `source .venv/bin/activate` elsewhere
    pip install -r requirements.txt
    python generate.py            # first run: ~hours, ~12 GB image cache
    python generate.py            # subsequent runs: a few minutes using the cache
"""
from __future__ import annotations

import argparse
import io
import json
import logging
import struct
import sys
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Iterator

import imagehash
import requests
from PIL import Image
from tqdm import tqdm

LOG = logging.getLogger("phash-gen")

SCRYFALL_BULK_URL = "https://api.scryfall.com/bulk-data"
RATE_LIMIT_SECONDS = 0.1  # Scryfall asks ~50-100ms between calls.
USER_AGENT = "mtg-ebay-phash-gen/0.1 (https://github.com/jared0108/mtg-ebay-secret-move)"

# Layouts we skip outright; not single sellable English cards.
SKIP_LAYOUTS = frozenset({
    "token", "double_faced_token", "emblem",
    "art_series", "planar", "scheme", "vanguard",
    "reversible_card",  # double-image novelty
})

# Crop rectangle for normal-frame cards (left, top, right, bottom in 0-1).
# Tuned for the Modern frame layout: art is in the upper-middle band.
ART_CROP = (0.075, 0.110, 0.925, 0.485)

# Finish flags packed into a single byte.
FINISH_BITS: dict[str, int] = {
    "nonfoil": 1 << 0,
    "foil":    1 << 1,
    "etched":  1 << 2,
    "glossy":  1 << 3,
}

RECORD_FMT = "<Q16sB8s8s"   # u64 | 16 bytes | u8 | 8 bytes | 8 bytes
RECORD_SIZE = struct.calcsize(RECORD_FMT)  # = 41
HEADER_FMT = "<4sHI"        # magic | u16 version | u32 count
HEADER_SIZE = struct.calcsize(HEADER_FMT)
MAGIC = b"MTGP"
VERSION = 1


@dataclass
class CardImage:
    """A single hashable face of a card."""
    scryfall_id: uuid.UUID
    image_url: str
    set_code: str
    collector_number: str
    finishes_mask: int
    full_frame_art: bool  # If True, skip the art-region crop and hash the whole image.


# ---------------------------------------------------------------------------
# Scryfall bulk fetching
# ---------------------------------------------------------------------------

def fetch_bulk_default_cards() -> list[dict]:
    """Resolve the `default_cards` bulk URI, then download & parse it."""
    LOG.info("Fetching Scryfall bulk-data manifest...")
    resp = requests.get(SCRYFALL_BULK_URL, headers={"User-Agent": USER_AGENT}, timeout=30)
    resp.raise_for_status()
    manifest = resp.json()["data"]
    entry = next(e for e in manifest if e["type"] == "default_cards")
    LOG.info(
        "default_cards: %s (%.1f MB)",
        entry["download_uri"],
        entry["size"] / 1_000_000,
    )

    LOG.info("Downloading default_cards JSON (this is large)...")
    resp = requests.get(entry["download_uri"], headers={"User-Agent": USER_AGENT}, timeout=300, stream=True)
    resp.raise_for_status()
    return resp.json()


def iter_card_images(cards: Iterable[dict]) -> Iterator[CardImage]:
    """Yield one CardImage per face we want to hash."""
    for card in cards:
        if card.get("lang") != "en":
            continue
        if card.get("layout") in SKIP_LAYOUTS:
            continue
        if card.get("digital"):
            continue

        try:
            card_id = uuid.UUID(card["id"])
        except (KeyError, ValueError):
            continue

        finishes_mask = 0
        for f in card.get("finishes", []):
            finishes_mask |= FINISH_BITS.get(f, 0)

        set_code = card.get("set", "")
        collector_number = card.get("collector_number", "")
        full_frame = bool(card.get("full_art")) or card.get("border_color") == "borderless"

        # Single image at the top level (normal, split, flip, adventure, saga, etc).
        if (uris := card.get("image_uris")) and uris.get("normal"):
            yield CardImage(
                scryfall_id=card_id,
                image_url=uris["normal"],
                set_code=set_code,
                collector_number=collector_number,
                finishes_mask=finishes_mask,
                full_frame_art=full_frame,
            )
            continue

        # Multi-face cards (transform, modal_dfc, meld): hash each face.
        for face in card.get("card_faces", []) or []:
            face_uris = face.get("image_uris") or {}
            if "normal" not in face_uris:
                continue
            yield CardImage(
                scryfall_id=card_id,
                image_url=face_uris["normal"],
                set_code=set_code,
                collector_number=collector_number,
                finishes_mask=finishes_mask,
                full_frame_art=full_frame,
            )


# ---------------------------------------------------------------------------
# Image fetching with on-disk cache
# ---------------------------------------------------------------------------

def cache_path(cache_dir: Path, scryfall_id: uuid.UUID, image_url: str) -> Path:
    """Cache file path. Uses scryfall_id plus a short URL hash to disambiguate DFC faces."""
    # imagehash isn't a path generator, but the URL filename keeps face_a / face_b apart.
    suffix = image_url.rsplit("/", 1)[-1].split("?", 1)[0]
    return cache_dir / str(scryfall_id) / suffix


def download_image(card: CardImage, cache_dir: Path, session: requests.Session) -> Image.Image | None:
    """Return a PIL Image. Uses on-disk cache; downloads + rate-limits if missing."""
    cache_file = cache_path(cache_dir, card.scryfall_id, card.image_url)
    if cache_file.exists() and cache_file.stat().st_size > 0:
        try:
            return Image.open(cache_file).convert("RGB")
        except Exception:
            cache_file.unlink(missing_ok=True)  # corrupt; redownload

    cache_file.parent.mkdir(parents=True, exist_ok=True)
    try:
        resp = session.get(card.image_url, headers={"User-Agent": USER_AGENT}, timeout=60)
        resp.raise_for_status()
    except Exception as e:
        LOG.warning("download failed (%s): %s", card.scryfall_id, e)
        return None
    time.sleep(RATE_LIMIT_SECONDS)

    cache_file.write_bytes(resp.content)
    try:
        return Image.open(io.BytesIO(resp.content)).convert("RGB")
    except Exception as e:
        LOG.warning("decode failed (%s): %s", card.scryfall_id, e)
        cache_file.unlink(missing_ok=True)
        return None


# ---------------------------------------------------------------------------
# Hashing + packing
# ---------------------------------------------------------------------------

def compute_phash(img: Image.Image, full_frame_art: bool) -> int:
    """Return a 64-bit pHash of the card's art region as an int."""
    if not full_frame_art:
        w, h = img.size
        l, t, r, b = ART_CROP
        img = img.crop((int(w * l), int(h * t), int(w * r), int(h * b)))
    # imagehash.phash with hash_size=8 → 64 bits.
    h = imagehash.phash(img, hash_size=8)
    # Convert the imagehash bit array to a little-endian uint64.
    bits = 0
    for i, b in enumerate(h.hash.flatten()):
        if b:
            bits |= 1 << i
    return bits


def pack_record(
    phash_bits: int,
    scryfall_id: uuid.UUID,
    finishes_mask: int,
    set_code: str,
    collector_number: str,
) -> bytes:
    set_bytes = set_code.encode("utf-8", errors="ignore")[:8].ljust(8, b"\x00")
    cn_bytes = collector_number.encode("utf-8", errors="ignore")[:8].ljust(8, b"\x00")
    return struct.pack(
        RECORD_FMT,
        phash_bits & ((1 << 64) - 1),
        scryfall_id.bytes,
        finishes_mask & 0xFF,
        set_bytes,
        cn_bytes,
    )


def write_db(records: list[bytes], out_path: Path) -> None:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    header = struct.pack(HEADER_FMT, MAGIC, VERSION, len(records))
    with out_path.open("wb") as f:
        f.write(header)
        for r in records:
            f.write(r)
    LOG.info("Wrote %s (%d records, %.2f MB)", out_path, len(records), out_path.stat().st_size / 1_000_000)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description="Generate phash.bin from Scryfall bulk data.")
    parser.add_argument("--limit", type=int, default=None, help="Only hash the first N faces (smoke test).")
    parser.add_argument("--out", type=Path, default=None, help="Output file (defaults to out/phash.bin).")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

    here = Path(__file__).resolve().parent
    cache_dir = here / ".cache" / "images"
    out_path = args.out or (here / "out" / "phash.bin")

    cards = fetch_bulk_default_cards()
    LOG.info("Loaded %d card entries from bulk JSON", len(cards))

    images = list(iter_card_images(cards))
    LOG.info("Will hash %d card faces (limit=%s)", len(images), args.limit)
    if args.limit is not None:
        images = images[: args.limit]

    session = requests.Session()
    records: list[bytes] = []
    skipped = 0

    for card in tqdm(images, desc="hash", unit="card"):
        img = download_image(card, cache_dir, session)
        if img is None:
            skipped += 1
            continue
        try:
            phash_bits = compute_phash(img, card.full_frame_art)
        except Exception as e:
            LOG.warning("phash failed (%s): %s", card.scryfall_id, e)
            skipped += 1
            continue
        records.append(
            pack_record(
                phash_bits,
                card.scryfall_id,
                card.finishes_mask,
                card.set_code,
                card.collector_number,
            )
        )

    LOG.info("Hashed %d, skipped %d", len(records), skipped)

    # Sort by pHash (first 8 bytes of each record, little-endian u64) for on-device binary search.
    records.sort(key=lambda r: struct.unpack_from("<Q", r, 0)[0])
    write_db(records, out_path)

    LOG.info("Copy %s into app/src/main/assets/phash.bin before building.", out_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
