# MTG Card Listing App — Project Specification

## Overview

A personal-use Android application that streamlines selling Magic: The Gathering cards on eBay. The user scans a physical card with their phone camera, the app identifies it, fetches current market pricing, and walks the user through reviewing and publishing an auction listing to eBay — all without manual data entry.

---

## Problem Statement

Creating individual eBay listings for MTG cards is slow and repetitive. The seller must manually enter the card name, set, rarity, and price for every listing. This app eliminates that friction by automating data lookup and listing pre-population, reducing the workflow to: scan → review → publish.

---

## Goals

- Minimize time from physical card in hand to live eBay listing
- Auto-populate all standard listing fields from scan data
- Support the seller's specific auction pricing strategy
- Keep the user in control with a review step before publishing

---

## Non-Goals (v1)

- Multi-user or team support
- Post-sale tracking (handled by the eBay app)
- Collection or inventory management
- Non-English cards
- Bulk/batch scanning workflows
- Listing history (deferred to a future version)

---

## User

Single user — the card owner and seller. The app is not designed for distribution or public release in v1.

---

## Core User Flow

```
1. Open app
2. Tap "Scan Card"
3. Point camera at card → app identifies it via image-hash match
4. App prompts: "Is this foil?" → user confirms
5. App fetches pricing and suggests a starting bid (70% of NM market, scaled by condition)
6. User reviews pre-populated listing details (name, set, rarity, condition, price, shipping)
7. User captures front and back photos of the card
8. User taps "Publish to eBay"
9. Listing goes live on eBay as a 7-day auction
```

---

## Tech Stack

- **Language:** Kotlin (native Android)
- **Minimum SDK:** API 29 (Android 10)
- **Camera:** CameraX
- **Computer vision:** OpenCV for Android (card-edge detection, perspective correction) + custom DCT-based pHash in Kotlin (~64-bit)
- **Local storage:** Room (SQLite) for draft listings; EncryptedSharedPreferences for the eBay user token
- **Networking:** OkHttp + Retrofit + kotlinx.serialization
- **Build flavors:** `sandbox` (default for dev) and `prod` — different eBay base URLs and which token slot is read

### Sub-projects

- **`phash-db-generator/`** — standalone script (Python or Kotlin), run on a dev machine, that downloads Scryfall bulk data + card images and produces `phash.bin` (~1.5 MB) bundled as an Android asset. Regenerated per set release.

---

## Features

### 1. Card Scanning

- Use the device camera to capture a card image
- Identify the card via **perceptual-hash (pHash) matching against a locally-bundled hash database**
- Must support all English card variants:
  - Standard, foil, alternate art, borderless, extended art, showcase, collector's edition, etc.
- **Foil status is always confirmed by the user after scan** (single Y/N prompt — pHash cannot reliably detect foil from one photo)
- English-only cards in v1

**Why we build our own pHash DB:** Scryfall publishes card images but does **not** publish perceptual hashes. The two well-known open-source MTG scanners (tmikonen/magic_card_detector, ForOhForError/YamCR) either cover only a small set of cards (Alpha, Old School) or compute hashes on-demand at first run from Scryfall — neither ships a maintained all-English-cards pHash DB suitable for Android. So we build a small one ourselves.

#### pHash DB generator (separate sub-project)

A small standalone tool (Python or Kotlin script, run on a dev machine — not on the phone) that:

1. Downloads Scryfall's `default-cards` bulk data (all English printings, JSON)
2. For each card, fetches the `normal`-size image URL
3. Detects/crops the card art region, normalizes orientation, computes a 64-bit DCT-based pHash
4. Outputs a compact binary file: `{phash: u64, scryfall_id: 16 bytes, finishes: u8, set_code: 8 bytes, collector_number: 8 bytes}` ≈ ~40 bytes/card × ~35k English printings ≈ **~1.5 MB total**
5. Bundled as an Android asset; regenerated and re-released when a new set ships

#### On-device matching

1. CameraX captures a frame, runs lightweight card-edge detection
2. Perspective-correct to canonical size, compute the same 64-bit pHash
3. Find nearest neighbors in the embedded DB by Hamming distance; pick the closest match below a threshold
4. Surface the top match (and 2–3 runners-up so the user can correct mis-identification of similar art)

**Manual search fallback** (if scan fails or user prefers): search by card name → pick set → pick variant. Backed by the Scryfall API.

**References (algorithm only — not used as dependencies):** [tmikonen/magic_card_detector](https://github.com/tmikonen/magic_card_detector) (MIT), [ForOhForError/YamCR](https://github.com/ForOhForError/Yet-Another-Magic-Card-Recognizer) (Apache-2.0).

---

### 2. Pricing

- **Basis:** TCGplayer Market Price for the selected condition
- **Primary source:** **TCGTracking Open TCG API** (`tcgtracking.com/tcgapi/v1/`) — free, no auth, condition-aware SKU-level pricing (NM/LP/MP/HP/DMG, foil split), daily refresh. Aggregates TCGplayer + Manapool + CardTrader.
- **Per-condition fallback:** When a SKU has no current listings for the selected condition (common for HP/DMG, occasional for MP), scale the card's NM `mkt` price by hardcoded multipliers: `LP=0.85, MP=0.70, HP=0.50, DMG=0.30`. NM is always used directly.
- **Provider fallback:** If TCGTracking is unreachable, fall back to Scryfall NM (`prices.usd` / `prices.usd_foil`) and apply the same condition multipliers.
- **Abstraction:** Pricing lives behind a `PriceSource` interface so the provider can be swapped (e.g. to JustTCG's paid tier) with a one-class change.
- **SKU disambiguation:** The TCGTracking SKU file may contain multiple SKU entries per (condition, variant, language) tuple (multi-vendor splits). Pick the entry with the highest `cnt` (listing count) as canonical.
- **Caching (per provider best practices):**
  - Static set product data (`/sets/{set_id}`): cache locally for ≥7 days
  - SKU pricing (`/sets/{set_id}/skus`): cache for 24 hours; refresh on first scan of a new day
  - Honor `ETag` / `Last-Modified` / `Cache-Control` headers
- App calculates and suggests a **starting bid at 70% of the condition-adjusted market value**
  - Example: NM card worth $8.00 → suggested starting bid of $5.60
- User can override the suggested price with manual entry
- **Constraint:** Only free pricing APIs in v1 — no paid subscriptions

---

### 3. Listing Review Screen

Before publishing, the user reviews a pre-populated listing summary containing:

| Field | Source | Default |
|---|---|---|
| Card Name | Scryfall | — |
| Set | Scryfall | — |
| Rarity | Scryfall | — |
| Foil | User confirmation | non-foil |
| Condition | User selection (NM/LP/MP/HP) | NM |
| Starting Bid | Calculated (70% × condition-adjusted market) | — |
| Listing Format | Auction | 7 days, no reserve |
| Returns | Fixed | Not accepted |
| Shipping | Calculated by buyer ZIP | Package weight selected per listing |
| Photos | Captured in-app | Front + back |

All fields are editable before publishing.

---

### 4. Photos

- User captures **two photos per listing** within the app:
  - One front-facing photo of the card
  - One back-facing photo of the card
- **Raw camera capture only** — no in-app cropping, auto-rotation, or glare detection in v1. Photos are sent to eBay as-is.
- Photos are stored on disk in the app's private files directory; the draft row references them by path
- Photos are attached to the eBay listing on publish
- No stock images — seller's actual card photos only

---

### 5. eBay Listing Creation

- **Authentication:** Manual eBay user token (long-lived, generated from eBay developer portal), stored in EncryptedSharedPreferences. No backend required.
- **Token expiry warning:** Tokens last ~18 months. App shows an in-app banner on the home screen starting 30 days before expiry, prompting the user to refresh the token.
- **Environment:** Development targets the **eBay Sandbox** (separate sandbox user token, sandbox base URL). Production is a build-flavor flip — same code path, different base URL + token. No live listings are created during development.
- **API:** eBay **Inventory API + Offer** endpoints
- **Category:** MTG single cards use eBay US category **38292** ("Magic: The Gathering > MTG Individual Cards"). Verified at first run via the Taxonomy API (`/category_tree/0/get_category_suggestions`) in case eBay reorganizes the tree; the discovered ID is cached locally.
- **Prerequisite:** Payment, return, and fulfillment business policies must already be configured on the seller's eBay account. App pulls policy IDs via the Account API on first run and reuses them.
- **Item location & handling time:** Pulled from the eBay seller account profile (origin ZIP + default handling time) on first run and cached locally. No manual entry in the app.
- **Listing title:** Auto-generated as `{Card Name} - {Set Name} - MTG Magic the Gathering{ - Foil}{ - Condition}`, truncated to eBay's 80-char limit. **Truncation order when over 80 chars:** drop Condition → drop the literal "MTG" → truncate Set Name (preserving leading words) → finally truncate Card Name (last resort; never drop entirely). User can edit on the review screen.
- **Auction defaults:** 7-day duration, no reserve, returns not accepted
- **Shipping:** Calculated rate by buyer ZIP; package weight chosen per listing
- Listing is **not published until the user explicitly confirms** on the review screen

#### Item-specifics mapping (Scryfall → eBay)

A small, mechanical mapping module converts Scryfall card data into eBay's required trading-card item specifics. Documented as a single source-of-truth table:

| eBay item specific | Source | Example |
|---|---|---|
| Game | Hardcoded | `Magic: The Gathering` |
| Set | Scryfall `set_name` | `Secrets of Strixhaven` |
| Card Name | Scryfall `name` | `Erode` |
| Card Number | Scryfall `collector_number` | `123` |
| Rarity | Scryfall `rarity` (mapped: `common`→`Common`, etc.) | `Rare` |
| Color | Scryfall `colors` (joined; empty → `Colorless`) | `Blue` |
| Finish | User foil confirmation | `Foil` / `Regular` |
| Language | Hardcoded (English-only in v1) | `English` |
| Card Condition | User selection | `Near Mint or Better` (mapped from NM/LP/MP/HP) |
| Features | Derived from Scryfall `frame_effects`, `promo_types` (best-effort) | `Extended Art`, `Borderless`, `Showcase` |
| Card Type | Scryfall `type_line` (first token before `—`) | `Creature` |

Treat anything missing from Scryfall as omitted rather than guessed.

#### Publish failure handling — local drafts

If a publish call fails (network, validation, rate limit), the listing is saved to a local Room (SQLite) drafts table. The user retries from a "Drafts" list. A successful publish deletes the draft and its photo files.

**Draft row fields:**

| Field | Purpose |
|---|---|
| `id` | Primary key |
| `created_at` / `updated_at` | Timestamps |
| `last_error` | eBay API error message + code |
| `retry_count` | Number of publish attempts |
| `scryfall_id` | Card identity (set/printing) |
| `card_name`, `set_name`, `rarity` | Cached display data for the drafts list |
| `is_foil`, `condition` | User-confirmed values |
| `starting_bid_cents` | Final price after any user override |
| `package_weight` | Per-listing shipping weight |
| `front_photo_path`, `back_photo_path` | File paths to JPEGs in app private storage |
| `title` | Generated/edited listing title |

**Retention:**
- Deleted immediately on successful publish (row + photo files)
- Deleted immediately on user-initiated delete
- **Auto-deleted after 30 days** of inactivity (prices go stale; old drafts are usually worthless for an auction-pricing app)

**Soft cap:** 50 drafts. When exceeded, the oldest draft is evicted on next save and the user sees a one-time warning. Workflow is never blocked.

---

## API Integrations

| Service | Purpose | Cost |
|---|---|---|
| Scryfall API + bulk-data | Card metadata, manual search, image URLs, NM fallback pricing; bulk `default-cards` JSON consumed offline by `phash-db-generator` | Free |
| TCGTracking Open TCG API | Primary condition-specific MTG pricing (NM/LP/MP/HP, foil split) | Free, no auth |
| eBay Sell API (Inventory + Offer + Account + Taxonomy) | Create auction listings, fetch business policies + account profile, verify category ID | Free (requires developer account + user token) |

---

## eBay Listing Requirements

Per eBay's current API requirements (as of May 2026):

- **Condition is required** on all listings — user picks NM/LP/MP/HP on the review screen before publishing (defaults to NM)
- Listing payload includes standardized condition fields and trading-card item specifics (Game, Set, Rarity, Card Name, Card Number, Finish, Language, Card Condition)
- Inventory API requires payment, return, and fulfillment business policies to exist on the seller account

---

## Platform & Distribution

- **Platform:** Android only, native Kotlin
- **Minimum SDK:** API 29 (Android 10)
- **Distribution:** Personal use / sideloaded APK in v1; Google Play release optional in a future version
- **Offline support:** Not required — internet connection assumed during use

---

## Out of Scope for v1

| Feature | Notes |
|---|---|
| iOS support | Android only |
| Bulk scanning | Individual listings only |
| Non-English cards | English only |
| Sale tracking | Handled externally via eBay app |
| Collection management | No inventory tracking |
| Listing history | Nice-to-have, deferred |
| Multiple eBay accounts | Single seller account only |
| Backend server | All client-side; manual token auth |

---

## Setup Prerequisites (before first run)

Documented in `README.md` as a one-time setup runbook for the seller:

1. **eBay developer account.** Register at developer.ebay.com; create an application; record App ID, Dev ID, Cert ID.
2. **eBay sandbox + production user tokens.** From the developer portal, run "Get a User Token" for both environments. Paste each into the app's settings screen on first run.
3. **eBay business policies (production only).** Confirm payment, return, and fulfillment business policies exist on the seller account. App discovers policy IDs at first run via the Account API.
4. **Android dev environment.** Physical Android device (confirmed available) with USB debugging enabled — camera + image recognition are not testable in emulator.
5. **`phash-db-generator` first run.** Generate the initial `phash.bin` asset before the first APK build; re-run when a new MTG set ships.

---

## Validation Results (pre-build verification — 2026-05-12)

Both pre-build validation checks passed; TCGTracking is confirmed as the primary pricing source.

- **Data quality check (PASSED).** 10 sample cards from Secrets of Strixhaven (mythics, rares, uncommons, commons; foil + non-foil) compared against Scryfall's TCGplayer-sourced NM USD price. All 10 matched within ±3.1%; 8 of 10 within ±1%. Foil prices matched equally well. All five conditions (NM/LP/MP/HP/DMG) are present in the SKU schema; foil/non-foil split is correct. **Caveat captured in spec:** HP/DMG SKUs often have no current listings — handled via NM × hardcoded multiplier fallback.
- **ToS / attribution check (PASSED).** No formal API ToS, no commercial-use restriction, no attribution requirement, no rate limits. Provider asks clients to cache static set data ≥7 days and pricing once daily — incorporated into the Pricing section. Provider links contain TCGplayer affiliate codes but we are not obligated to surface those links. **Risk noted:** Provider is run by a single individual (bus factor of 1); the `PriceSource` interface abstraction mitigates this — swapping providers is a one-class change.
