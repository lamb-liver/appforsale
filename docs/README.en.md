# Stall POS · Market Checkout

**Version: v1.3.0** (`VERSION` · `versionName`)

An **offline-first Android checkout app** for market stalls and small booths: quick-tap products and bundles, take payment on site, and track today’s revenue—without inventory ERP or a heavy back office.

> This repo is a **Kotlin / Gradle** project (not Node.js). Dependencies are managed via `gradle/libs.versions.toml`.  
> 中文說明: [README.md](../README.md) · **Distribution / install / ECPay**: [distribution.md](distribution.md)

---

### Features

| Feature | Notes |
|---------|--------|
| Quick checkout | Products / bundles, discounts, custom amount, cash / digital pay, tip; orange **Collect payment** bar shows amount due and item count |
| Stock | Optional per-product stock; bundles share stock with singles |
| Haptic & sound | Tap / checkout success / error feedback; independent toggles in settings; silent & vibrate ringer modes = haptic only |
| Today dashboard | Same-day revenue and transaction count |
| Undo | Undo the last checkout and immediately restore stock, cart, and revenue |
| CSV export | A readable report of active transactions only, saved through SAF and shared with the system sheet |
| JSON backup / restore | Full `PosStore` state from the settings menu |
| Sponsor developer | Voluntary support (NT$30 / 99 / 150); settings → ECPay in external browser; not stall checkout |

---

### Tech stack

| Kotlin | Jetpack Compose · Material 3 | MVVM (`ViewModel` + `StateFlow`) |
|--------|------------------------------|----------------------------------|
| Jetpack DataStore (Preferences + JSON) | Kotlin Coroutines · Flow | Lifecycle (`ProcessLifecycleOwner`, Compose lifecycle) |
| ECPay sponsor (external browser) | kotlinx.collections.immutable | No Hilt / Room |

> **No Hilt / Room**: persistence and checkout semantics are documented in [ADR-0001](adr/0001-pos-state-in-datastore-json.md).

---

### Project layout

```
stallpos/
├── app/src/main/java/com/lambliver/stallpos/
│   ├── domain/          # Coordinators (catalog / cart / checkout), pricing rules, models, UI contract
│   ├── data/            # PosPersistence, PosStore, JSON codecs, CSV / backup I/O, AppUiPreferences
│   └── ui/              # Activity, Compose, ViewModel, theme
│       ├── feedback/    # PosFeedbackManager (haptics + SoundPool)
│       ├── sponsor/     # SponsorLinks, sponsor sheet, open ECPay payment page
│       ├── animation/   # Quick-tap tile press scale
│       └── pos/         # PosAppShell, main screen, PosCheckoutButton, checkout / dashboard sheets
├── app/src/test/        # Unit tests (coordinators, JSON, checkout amounts…)
├── docs/
│   ├── adr/             # Architecture decision records
│   ├── distribution.md  # Release, sideload install, ECPay sponsor setup
│   ├── CONTEXT.md       # Domain glossary (products, cart, checkout…)
│   ├── CHANGELOG.md
│   ├── README.en.md
│   └── cursor/rule/     # Cursor rules
├── gradle/              # Version catalog, Wrapper
├── VERSION              # Single source for app version (synced to versionName)
├── local.properties.example
└── .cursorrules         # Subtraction design, high-contrast outdoor UI
```

#### Layers and a single persistence seam

| Layer | Responsibility |
|-------|----------------|
| **ui** | `PosViewModel` observes `PosPersistence.snapshot`; cart / catalog / checkout delegate to coordinators; `PosAppShell` handles SAF export and `csvShareUriFlow` sharing; `PosFeedbackManager` for haptics / sound; sponsor under `ui/sponsor` |
| **domain** | Pure rules and coordinator results (`CartResult`, `CatalogPersistPlan`, `CheckoutWriteRequest`); `PosUiState` derived fields (`cartItemCount`, `checkoutSurfaceReceivablePreview`) |
| **data** | `PosPersistence` interface + `PosStore` (DataStore); `AppUiPreferences` (`haptic_enabled` / `sound_enabled`); atomic catalog writes via `applyCatalog`; `checkout` as an internal extension |

Suggested reading order: **`README` → `CONTEXT.md` → `ui/PosViewModel.kt` → `domain/PosCartCoordinator.kt` → `domain/PosCatalogCoordinator.kt` → `domain/PosCheckoutCoordinator.kt` → `data/PosPersistence.kt` → `data/PosStore.kt`**.

---

### Tests

| Test class | Coverage |
|------------|----------|
| `CheckoutAmountsTest` | Checkout amount formulas, clamp, all `reconcile` branches |
| `CheckoutSheetPricingSnapshotTest` | Snapshot conversion and round-trip |
| `BeginCheckoutSheetSnapshotTest` | Snapshot write/read equivalence |
| `PosCatalogCoordinatorTest` | Catalog delete rules, bundle reference checks |
| `PosCheckoutCoordinatorTest` | Checkout reconcile, subtotal race, insufficient stock |
| `PosCartCoordinatorTest` | Cart add/remove, stock caps, clamp |
| `PosViewModelCheckoutTest` | VM checkout success / failure restore / reconcile reject (Robolectric + `FakePosPersistence`) |
| `PosUndoCoordinatorTest` | Append-only undo, orphan / duplicate rejection, stock restore fallback |
| `BackupMigrationTest` | Backup schema 1 / 2→3, stable IDs, LastCheckout linking, idempotency |
| `PosBackupPayloadTest` | Backup envelope `parseBackupEnvelope` (plain JVM) |
| `PosFeedbackManagerTest` | Sound gating (`shouldPlaySound`: NORMAL / VIBRATE / SILENT) |
| `CheckoutBottomSheetComposeTest` | Checkout sheet interactions (Robolectric Compose) |
| `SponsorLinksTest` | Sponsor button copy and tier amounts |
| `SponsorPaymentTest` | Blank payment URL does not launch browser (Robolectric) |

Coordinator and pricing unit tests can use **`FakePosPersistence`**—no device required. See also `PosCartJsonTest`, `SalesRecordsJsonTest`, `PosCsvExportTest`, etc.

### Backup versioning (two fields)

| Field | Layer | Role |
|-------|-------|------|
| **`schemaVersion`** | Envelope (backup file root) | `parseBackupEnvelope` validation and migration steps (`BackupMigration`) |
| **`payloadSchema`** | Inside `payload` | Business blob shape; v3 adds Sale IDs, `reversal_log_json`, and LastCheckout linkage |

Both are **3** today. Old **schemaVersion: 1 / 2** files migrate stepwise in `parseValidatedBackupPayload`. The v2→v3 step generates deterministic legacy Sale IDs, links `LastCheckout.saleId` only on a unique match, and initializes an empty Reversal log. See `CONTEXT.md` and `data/BackupMigration.kt`.

Instrumented (device/emulator): `PosStoreInstrumentedTest` (DataStore checkout/undo E2E).

---

### Architecture decisions (ADR)

- [ADR-0001 — DataStore + JSON for app state](adr/0001-pos-state-in-datastore-json.md)
- [ADR-0002 — Reconcile amounts at checkout confirm](adr/0002-checkout-reconcile-at-confirm.md)
- [ADR-0003 — In-memory cart with debounced disk flush](adr/0003-cart-memory-with-debounced-disk-flush.md)
- [ADR-0004 — Append-only Sales and Reversals](adr/0004-append-only-sales-and-reversals.md)

Checkout money semantics: `phase-a-checkout-money-flow.md`.

#### CSV export and share (UI flow)

1. `PosMainScreen` → `PosUiEvent.RequestExportCsv`
2. `PosAppShell` uses `ActivityResultContracts.CreateDocument("text/csv")` for the save location
3. `PosEvent.ExportCsv` → `PosViewModel.exportCsv` → `PosCsvExportAdapter` writes to the SAF URI
4. On success, `csvShareUriFlow` emits the URI; `PosAppShell` opens the system share sheet via `ACTION_SEND` + `FLAG_GRANT_READ_URI_PERMISSION` (no FileProvider)

#### Haptic & sound feedback

| Trigger | Feedback | Notes |
|---------|----------|--------|
| Quick-tap tile press | Short vibrate + click | `ProductTile` → `PosFeedbackManager.lightTap()` |
| Checkout success | 2× pulse + chime | `checkoutSuccessFlow` → `checkoutSuccess()` |
| Error toast / insufficient cash | Long vibrate + bump | `PosToastSeverity.Error` → `error()` |

- **Settings**: gear menu toggles **Haptic feedback** and **Sound feedback** independently (DataStore `AppUiPreferences`).
- **Silent policy**: sound only when `RINGER_MODE_NORMAL`; VIBRATE / SILENT keep haptics only (if haptic toggle is on).
- **Audio**: `SoundPool` + `res/raw/*.wav`, `USAGE_MEDIA` at fixed volume; created via `rememberPosFeedback()` in `PosApp`, released on `Activity` destroy.
- **No feedback** for navigation-only actions (open checkout sheet, collapse numpad, tap Collect payment).

#### Sponsor developer (ECPay)

1. Settings → **贊助開發者** (Sponsor developer)
2. Pick a tier (e.g. 打道音遊（贊助開發者30元）)
3. Complete payment in the external browser (ECPay)

See **[distribution.md](distribution.md)** for ECPay URLs, sideload install, and release checklist.

---

### Local setup

1. **Android Studio** (latest stable recommended), **JDK 17+**, Android SDK **API 35** (matches `compileSdk` / `targetSdk`).
2. Copy local config:
   ```bash
   cp local.properties.example local.properties
   ```
   Set `sdk.dir` to your Android SDK. ECPay URLs: `ui/sponsor/SponsorLinks.kt`.
3. Build and test:
   ```bash
   ./gradlew :app:assembleDebug
   ./gradlew :app:testDebugUnitTest
   ```
4. Run the app: entry `com.lambliver.stallpos.ui.MainActivity`; `applicationId` matches `namespace`.

**Distribution**: sideload APK — [distribution.md](distribution.md) (build, install, ECPay, release checklist).

---

### Secrets and build configuration

| Item | Where | Notes |
|------|--------|--------|
| ECPay payment URLs | `ui/sponsor/SponsorLinks.kt` | `ECPAY_URL_TIER_*` for NT$30 / 99 / 150 |
| Release signing | `local.properties` (see example) | `storeFile` / passwords; **not** wired in `signingConfigs` yet |
| SDK path | `local.properties` → `sdk.dir` | Machine-local; do not commit |

No Firebase `google-services.json`, AdMob, Play Billing, or backend API keys in this repo.

---

### Before pushing to Git

| Item | Notes |
|------|--------|
| Do not commit | `local.properties`, `*.keystore`, `keystore.properties`, `app/build/`, `.gradle/`, `.idea/` (see `.gitignore`) |
| Do commit | Source, `gradle/wrapper/`, `gradlew*`, `libs.versions.toml`, `local.properties.example` |
| Secrets | Signing passwords only in **gitignored** `local.properties` or local keystores |
| Release builds | `./gradlew :app:assembleRelease`; set ECPay URLs in `SponsorLinks` if sponsor is enabled |
| First-time init | `git init && git add . && git status` — confirm no build artifacts are staged |

```bash
# Optional: sanity-check ignored build dirs
git status --ignored | head -20
```

---

### License

No license file is bundled yet; all rights reserved by default. Add a `LICENSE` if you open-source this project.
