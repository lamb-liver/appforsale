# Stall POS · Market Checkout

**Version: v2.0.0** (`VERSION` · `versionName`)

An **offline-first Android market POS** with quick checkout, event inventory, cloud backup/device recovery, and a read-only event dashboard.

> **v2.0 Local-first**: encrypted Room v2 is the on-device source of truth. Transactions complete offline, then an Outbox synchronizes them through a Cloudflare Worker. Inactivity never deletes cloud account or business data; only explicit Cloud Delete or Account Delete does.

> This repo is a **Kotlin / Gradle** project (not Node.js). Dependencies are managed via `gradle/libs.versions.toml`.  
> 中文說明: [README.md](../README.md) · **Distribution / install / ECPay**: [distribution.md](distribution.md)

---

### Features

| Feature | Notes |
|---------|--------|
| Quick checkout | Products / bundles, discounts, custom amount, cash / digital pay, tip; orange **Collect payment** bar shows amount due and item count |
| Event inventory | GENERAL / EVENT transfers, damage, adjustments, event close returns, and bundle component allocation |
| Haptic & sound | Tap / checkout success / error feedback; independent toggles in settings; silent & vibrate ringer modes = haptic only |
| Events and reports | Event lifecycle plus a read-only web dashboard for revenue, trends, products, hours, payments, bundles, and sell-through |
| VOID | Preserves the Sale and appends one idempotent Void with inventory restoration |
| Cloud sync / transfer | Google Login, background Outbox sync, device transfer / forced retirement, atomic bootstrap, and cloud epochs |
| CSV export | A readable report of active transactions only, saved through SAF and shared with the system sheet |
| JSON backup / restore | Full Room business data in the existing JSON exchange format |
| Sponsor developer | Voluntary support (NT$30 / 99 / 150); settings → ECPay in external browser; not stall checkout |

---

### Tech stack

| Kotlin | Jetpack Compose · Material 3 | MVVM (`ViewModel` + `StateFlow`) |
|--------|------------------------------|----------------------------------|
| Room 2.8.4 + SQLCipher 4.17.0 | Kotlin Coroutines · Flow | WorkManager · Credential Manager |
| DataStore (UI preferences; one-time legacy retirement only) | Cloudflare Worker · dual D1 | Sentry · no Hilt |

> See [ADR-0005](adr/0005-room-local-relational-persistence.md) for the v1 Room and legacy-import background. The current v2 schema is `app/schemas/com.lambliver.stallpos.data.StallPosV2Database/2.json`.

---

### Project layout

```
stallpos/
├── .github/workflows/  # PR / main CI and signed tag release pipeline
├── app/src/main/java/com/lambliver/stallpos/
│   ├── domain/          # Coordinators (catalog / cart / checkout), pricing rules, models, UI contract
│   ├── data/            # PosPersistence, Room DB/DAO, legacy JSON migration, CSV / backup I/O
│   └── ui/              # Activity, Compose, ViewModel, theme
│       ├── feedback/    # PosFeedbackManager (haptics + SoundPool)
│       ├── sponsor/     # SponsorLinks, sponsor sheet, open ECPay payment page
│       ├── animation/   # Quick-tap tile press scale
│       └── pos/         # PosAppShell, main screen, PosCheckoutButton, checkout / dashboard sheets
├── app/src/test/        # Unit tests (coordinators, migrations, sync, checkout amounts…)
├── contracts/v2/       # Shared Android / Worker schemas and golden fixtures
├── worker/             # API, auth, sync, dual D1 migrations, dashboard, ops
├── docs/
│   ├── adr/             # Architecture decision records
│   ├── distribution.md  # Release, sideload install, ECPay sponsor setup
│   ├── upgrade-qa.md    # Signing-chain and JSON transfer verification
│   ├── CONTEXT.md       # Domain glossary (products, cart, checkout…)
│   ├── CHANGELOG.md
│   ├── README.en.md
│   └── cursor/rule/     # Cursor rules
├── gradle/              # Version catalog, Wrapper
├── RELEASE_CERT_SHA256  # Public APK signing certificate fingerprint
├── VERSION              # Single source for app version (synced to versionName)
├── local.properties.example
└── .cursorrules         # Subtraction design, high-contrast outdoor UI
```

#### Layers and a single persistence seam

| Layer | Responsibility |
|-------|----------------|
| **ui** | `PosViewModel` observes `PosPersistence.snapshot`; cart / catalog / checkout delegate to coordinators; `PosAppShell` handles SAF export and `csvShareUriFlow` sharing; `PosFeedbackManager` for haptics / sound; sponsor under `ui/sponsor` |
| **domain** | Pure rules and coordinator results (`CartResult`, `CatalogPersistPlan`, `CheckoutWriteRequest`); `PosUiState` derived fields (`cartItemCount`, `checkoutSurfaceReceivablePreview`) |
| **data** | `PosPersistence` + `RoomPosPersistence`; Room transactions own catalog / checkout / undo / restore; DataStore keeps UI preferences while legacy business keys are retired all-or-nothing or preserved intact |

Suggested reading order: **`README` → `CONTEXT.md` → `ui/PosViewModel.kt` → `domain/PosCartCoordinator.kt` → `domain/PosCatalogCoordinator.kt` → `domain/PosCheckoutCoordinator.kt` → `data/PosPersistence.kt` → `data/RoomPosPersistence.kt`**.

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
| `BackupMigrationTest` | Backup schema 1 / 2 / 3→4, stable IDs, LastCheckout linking, name snapshots, idempotency |
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
| **`payloadSchema`** | Inside `payload` | Business blob shape; v4 adds checkout-time product / bundle display-name snapshots |

Both are **5** today. Old **schemaVersion: 1 / 2 / 3 / 4** files migrate stepwise; costs that cannot be proven remain `null`, never zero.

Instrumented (device/emulator): `PosStoreInstrumentedTest` (Room checkout/undo, rollback, relations, and large history).
`LegacyRetirementInstrumentedTest` covers v1.2/v1.3/v1.4 fixtures, all-or-nothing cleanup, and malformed legacy isolation.

v2.0 release gate (2026-08-24): Android unit, lint, API 35 instrumented, production-signed v1.5→v2 upgrade, Worker unit/integration, both D1 migration sets, dashboard browser smoke, and the production Restore Drill passed.

### Data and distribution ownership

| Data | Contract |
|------|----------|
| Room `stallpos.db` | Only runtime business source of truth |
| DataStore `pos_store` | UI preferences; legacy business keys retire all-or-nothing |
| StallPOS JSON | Only supported cross-install business-data transfer format |
| Android Auto Backup / D2D | Unsupported and excluded by the manifest and backup rules |

---

### Architecture decisions (ADR)

- [ADR-0001 — DataStore + JSON for app state](adr/0001-pos-state-in-datastore-json.md)
- [ADR-0002 — Reconcile amounts at checkout confirm](adr/0002-checkout-reconcile-at-confirm.md)
- [ADR-0003 — In-memory cart with debounced disk flush](adr/0003-cart-memory-with-debounced-disk-flush.md)
- [ADR-0004 — Append-only Sales and Reversals](adr/0004-append-only-sales-and-reversals.md)
- [ADR-0005 — Room local relational persistence](adr/0005-room-local-relational-persistence.md)
- [ADR-0006 — Production signing, data ownership, and legacy retirement](adr/0006-production-signing-and-data-ownership.md)

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
   ./gradlew :app:lintDebug
   ./gradlew :app:connectedDebugAndroidTest  # API 35 emulator/device
   ```
4. Run the app: entry `com.lambliver.stallpos.ui.MainActivity`; `applicationId` matches `namespace`.

**Distribution**: sideload APK — [distribution.md](distribution.md) (build, install, ECPay, release checklist).

---

### Secrets and build configuration

| Item | Where | Notes |
|------|--------|--------|
| ECPay payment URLs | `ui/sponsor/SponsorLinks.kt` | `ECPAY_URL_TIER_*` for NT$30 / 99 / 150 |
| Certificate identity | `RELEASE_CERT_SHA256` | Public 64-character uppercase SHA-256; not a keystore or APK checksum |
| Local release signing | Keychain / gitignored `local.properties` | `storeFile`, store password, alias, and key password are all required |
| CI release signing | GitHub Actions Secrets | Keystore base64 and signing values are exposed only to the release build step |
| SDK path | `local.properties` → `sdk.dir` | Machine-local; do not commit |

No Firebase `google-services.json`, AdMob, Play Billing, or backend API keys in this repo.

---

### Before pushing to Git

| Item | Notes |
|------|--------|
| Do not commit | `local.properties`, `*.keystore`, `keystore.properties`, `app/build/`, `.gradle/`, `.idea/` (see `.gitignore`) |
| Do commit | Source, workflow, `RELEASE_CERT_SHA256`, `gradle/wrapper/`, `gradlew*`, `libs.versions.toml`, `local.properties.example` |
| Secrets | Signing passwords only in Keychain, gitignored `local.properties`, or GitHub Secrets |
| Release builds | `./gradlew :app:assembleRelease`; verify its certificate against `RELEASE_CERT_SHA256` |
| First-time init | `git init && git add . && git status` — confirm no build artifacts are staged |

```bash
# Optional: sanity-check ignored build dirs
git status --ignored | head -20
```

---

### License

No license file is bundled yet; all rights reserved by default. Add a `LICENSE` if you open-source this project.
