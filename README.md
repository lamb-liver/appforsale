# 小攤位 · 市集 POS

**版本：v1.0.0**（`VERSION` · `versionName` · [Releases](https://github.com/lamb-liver/appforsale/releases/tag/v1.0.0)）

**離線可用的 Android 結帳 app**：快選商品／套組、現場收款、紀錄今日營收；刻意不做進銷存或複雜後台。

> 本 repo 為 **Kotlin / Gradle** 專案（非 Node.js），依賴由 `gradle/libs.versions.toml` 管理。  
> English: [README.en.md](README.en.md)

---

### 主要功能

| 功能 | 說明 |
|------|------|
| 快選結帳 | 商品／套組、折扣、自訂金額、現金／行動支付、小費 |
| 庫存 | 可選追蹤庫存；套組與單品共用庫存規則 |
| 今日儀表 | 當日營收與筆數摘要 |
| 復原 | 結帳成功後短時間內可復原上一筆 |
| CSV 匯出 | 頂列檔案圖示 → SAF 選路徑存檔 → 成功後系統分享選單（Line、Drive、Email 等） |
| JSON 備份／還原 | 設定選單完整備份與還原（`PosStore` 全狀態） |
| 贊助去廣告 | Google Play 一次性 INAPP（`SponsorProductIds`） |

---

### 技術棧

| Kotlin | Jetpack Compose · Material 3 | MVVM（`ViewModel` + `StateFlow`） |
|--------|------------------------------|----------------------------------|
| Jetpack DataStore（Preferences + JSON） | Kotlin Coroutines · Flow | Lifecycle（`ProcessLifecycleOwner`、Compose lifecycle） |
| Google Play Billing（贊助去廣告） | AdMob · UMP 同意流程 | kotlinx.collections.immutable |

> 未使用 **Hilt／Room**：持久化與交易語意見 [ADR-0001](docs/adr/0001-pos-state-in-datastore-json.md)。

---

### 專案結構

```
appforsale/
├── app/src/main/java/com/lambliver/appforsale/
│   ├── domain/          # 協調器（目錄／購物車／結帳）、對帳、網域模型、UI 契約
│   ├── data/            # PosPersistence、PosStore、JSON codec、CSV／備份 I/O
│   └── ui/              # Activity、Compose、ViewModel、theme、billing／廣告
│       └── pos/         # PosAppShell、主畫面、結帳／儀表 sheet、匯出 launcher
├── app/src/test/        # 單元測試（Coordinator、JSON、結帳金額…）
├── docs/adr/            # 架構決策紀錄
├── CONTEXT.md           # 領域名詞（商品、購物車、結帳…）
├── gradle/              # Version catalog、Wrapper
├── VERSION              # App 版本單一來源（同步 versionName）
├── CHANGELOG.md
├── local.properties.example
└── .cursorrules         # 減法設計、戶外高對比 UI
```

#### 分層與單一 seam

| 層 | 職責 |
|----|------|
| **ui** | `PosViewModel` 訂閱 `PosPersistence.snapshot`；購物車／目錄／結帳委派各 Coordinator；`PosAppShell` 處理 SAF 匯出與 `csvShareUriFlow` 分享；廣告／Billing 在 `ui/ads`、`ui/billing` |
| **domain** | 純規則與協調結果（`CartResult`、`CatalogPersistPlan`、`CheckoutWriteRequest`） |
| **data** | `PosPersistence` 介面 + `PosStore`（DataStore）；`applyCatalog` 原子寫入；`checkout` 為 internal extension |

建議閱讀順序：**`README` → `CONTEXT.md` → `ui/PosViewModel.kt` → `domain/PosCartCoordinator.kt` → `domain/PosCatalogCoordinator.kt` → `domain/PosCheckoutCoordinator.kt` → `data/PosPersistence.kt` → `data/PosStore.kt`**。

---

### 測試

| 測試檔 | 涵蓋範圍 |
|--------|----------|
| `CheckoutAmountsTest` | 結帳金額公式、clamp、reconcile 全分支 |
| `CheckoutSheetPricingSnapshotTest` | 快照轉換與 round-trip |
| `BeginCheckoutSheetSnapshotTest` | 快照寫入讀出等價 |
| `PosCatalogCoordinatorTest` | 目錄刪除規則、套組引用檢查 |
| `PosCheckoutCoordinatorTest` | 結帳對帳、小計競態、庫存不足 |
| `PosCartCoordinatorTest` | 購物車加減品、庫存上限、clamp |
| `PosViewModelCheckoutTest` | VM 結帳成功／失敗還原／對帳拒絕（Robolectric + [FakePosPersistence]） |
| `PosUndoCoordinatorTest` | 上一筆結帳復原規則、庫存還原 fallback |
| `BackupMigrationTest` | 備份 schema 1→2 遷移、冪等性、還原 |
| `PosBackupPayloadTest` | 備份 envelope `parseBackupEnvelope`（純 JVM） |

協調器與金額邏輯的單元測試可用 **`FakePosPersistence`** mock 持久化層，不需裝置。其餘 JSON／CSV 測試見 `PosCartJsonTest`、`SalesRecordsJsonTest`、`PosCsvExportTest` 等。

### 備份版本（兩層欄位）

| 欄位 | 層級 | 職責 |
|------|------|------|
| **`schemaVersion`** | Envelope（備份檔根物件） | `parseBackupEnvelope` 驗證與遷移步驟編排（見 `BackupMigration`） |
| **`payloadSchema`** | `payload` 物件內 | 業務資料束（`products_json` 等）形狀；`migrateV1ToV2` 僅補此標記 |

現行皆為 **2**。舊 **schemaVersion: 1** 備份還原時由 `parseValidatedBackupPayload` 自動遷移；`migrateV1ToV2` **冪等**（已有 `payloadSchema` 不再寫入）。語意詳見 `CONTEXT.md` 與 `data/BackupMigration.kt`。

儀表測試（需模擬器／裝置）：`PosStoreInstrumentedTest`（DataStore 結帳／復原端到端）。

---

### 架構決策（ADR）

- [ADR-0001 — DataStore + JSON 集中狀態](docs/adr/0001-pos-state-in-datastore-json.md)
- [ADR-0002 — 結帳確認點對帳](docs/adr/0002-checkout-reconcile-at-confirm.md)
- [ADR-0003 — 購物車記憶體 + debounce 寫碟](docs/adr/0003-cart-memory-with-debounced-disk-flush.md)

結帳金額語意：`docs/phase-a-checkout-money-flow.md`。

#### CSV 匯出與分享（UI 流程）

1. `PosMainScreen` → `PosUiEvent.RequestExportCsv`
2. `PosAppShell` 以 `ActivityResultContracts.CreateDocument("text/csv")` 讓使用者選儲存位置
3. `PosEvent.ExportCsv` → `PosViewModel.exportCsv` → `PosCsvExportAdapter` 寫入 SAF URI
4. 成功後 `csvShareUriFlow` 送出 URI；`PosAppShell` 以 `ACTION_SEND` + `FLAG_GRANT_READ_URI_PERMISSION` 開啟系統分享（不需 FileProvider）

---

### 本機環境

1. **Android Studio**（建議最新穩定版）、**JDK 17+**、Android SDK **API 35**（與 `compileSdk` / `targetSdk` 一致）。
2. 複製本機設定：
   ```bash
   cp local.properties.example local.properties
   ```
   編輯 `sdk.dir` 指向你的 Android SDK。若要正式版 AdMob，再填入 `admob.application.id` / `admob.banner.unit.id`（見範本註解）。
3. 組建與測試：
   ```bash
   ./gradlew :app:assembleDebug
   ./gradlew :app:testDebugUnitTest
   ```
4. 執行 App：入口 `com.lambliver.appforsale.ui.MainActivity`，`applicationId` 同 `namespace`。

**Debug** 一律使用 Google 官方測試廣告 ID（hardcode 於 `app/build.gradle.kts`）；**Release** 從 `local.properties` 讀正式 ID，未設定時仍 fallback 測試 ID（方便 CI 組建，**上架前請補齊正式 ID**）。

Release 上架另需自行處理簽章、`Play Console`（INAPP 商品 ID 須與 `SponsorProductIds.kt` 一致）與 AdMob 後台。

---

### 敏感資訊與建置設定

| 項目 | 存放位置 | 說明 |
|------|----------|------|
| AdMob **正式** App ID | `local.properties` → `admob.application.id` | 編譯注入 `AndroidManifest` 的 `com.google.android.gms.ads.APPLICATION_ID` |
| AdMob **正式** 橫幅單元 | `local.properties` → `admob.banner.unit.id` | 編譯注入 `BuildConfig.ADMOB_BANNER_AD_UNIT_ID` |
| AdMob **測試** ID | `app/build.gradle.kts` | Google 公開測試 ID，可留在 repo |
| Play Billing 商品 ID | `ui/billing/SponsorProductIds.kt` | `sponsor_tier_small` 等，須與 Play Console 一致；通常不需放 `local.properties` |
| Release 簽章 | `local.properties`（範本見 example） | `storeFile`／密碼等；**尚未**接入 `signingConfigs`，上架時自行 wiring |
| SDK 路徑 | `local.properties` → `sdk.dir` | 本機路徑，勿提交 |

專案內**無** Firebase `google-services.json`、後端 API key 或 OAuth secret。

---

### 上傳 Git 前檢查

| 項目 | 說明 |
|------|------|
| 勿提交 | `local.properties`、`*.keystore`、`keystore.properties`、`app/build/`、`.gradle/`、`.idea/`（見 `.gitignore`） |
| 應提交 | 原始碼、`gradle/wrapper/`、`gradlew*`、`libs.versions.toml`、`local.properties.example` |
| 敏感資訊 | AdMob 正式 ID、簽章密碼只放在 **gitignore 的** `local.properties` 或本機 keystore |
| Release 建置 | 確認合併 Manifest／`BuildConfig` 非測試 AdMob publisher `3940256099942544` |
| 首次初始化 | `git init && git add . && git status` 確認沒有 build 產物被 staged |

```bash
# 建議：確認沒有誤加 build（應無輸出）
git status --ignored | head -20
```

---

### 授權

未於本 repo 標示授權文件前，預設為版權所有；若需開源請自行補上 `LICENSE`。
