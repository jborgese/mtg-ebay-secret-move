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
- **Local storage:** Room (SQLite) for draft listings; EncryptedSharedPreferences for the eBay user token
- **Networking:** OkHttp + Retrofit + kotlinx.serialization

---

## Features

### 1. Card Scanning

- Use the device camera to capture a card image
- Identify the card via **image-hash matching against Scryfall's perceptual hashes (pHash)**
- Must support all English card variants:
  - Standard, foil, alternate art, borderless, extended art, showcase, collector's edition, etc.
- **Foil status is always confirmed by the user after scan** (single Y/N prompt — pHash cannot reliably detect foil from one photo)
- English-only cards in v1

**Manual search fallback** (if scan fails or user prefers): search by card name → pick set → pick variant. Backed by the Scryfall API.

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
- **API:** eBay **Inventory API + Offer** endpoints
- **Prerequisite:** Payment, return, and fulfillment business policies must already be configured on the seller's eBay account. App pulls policy IDs via the Account API on first run and reuses them.
- **Item location & handling time:** Pulled from the eBay seller account profile (origin ZIP + default handling time) on first run and cached locally. No manual entry in the app.
- **Listing title:** Auto-generated as `{Card Name} - {Set Name} - MTG Magic the Gathering{ - Foil}{ - Condition}`, truncated to eBay's 80-char limit. User can edit on the review screen.
- **Auction defaults:** 7-day duration, no reserve, returns not accepted
- **Shipping:** Calculated rate by buyer ZIP; package weight chosen per listing
- Listing is **not published until the user explicitly confirms** on the review screen

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
| Scryfall API | Card identification (pHash), metadata, manual search, image URLs, NM fallback pricing | Free |
| TCGTracking Open TCG API | Primary condition-specific MTG pricing (NM/LP/MP/HP, foil split) | Free, no auth |
| eBay Sell API (Inventory + Offer + Account) | Create auction listings, fetch policies | Free (requires developer account + user token) |

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

## Validation Results (pre-build verification — 2026-05-12)

Both pre-build validation checks passed; TCGTracking is confirmed as the primary pricing source.

- **Data quality check (PASSED).** 10 sample cards from Secrets of Strixhaven (mythics, rares, uncommons, commons; foil + non-foil) compared against Scryfall's TCGplayer-sourced NM USD price. All 10 matched within ±3.1%; 8 of 10 within ±1%. Foil prices matched equally well. All five conditions (NM/LP/MP/HP/DMG) are present in the SKU schema; foil/non-foil split is correct. **Caveat captured in spec:** HP/DMG SKUs often have no current listings — handled via NM × hardcoded multiplier fallback.
- **ToS / attribution check (PASSED).** No formal API ToS, no commercial-use restriction, no attribution requirement, no rate limits. Provider asks clients to cache static set data ≥7 days and pricing once daily — incorporated into the Pricing section. Provider links contain TCGplayer affiliate codes but we are not obligated to surface those links. **Risk noted:** Provider is run by a single individual (bus factor of 1); the `PriceSource` interface abstraction mitigates this — swapping providers is a one-class change.
