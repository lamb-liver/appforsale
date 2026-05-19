# 小攤位 · 市集 POS

**版本：v1.2.0**（`VERSION` · `versionName` · [Releases](https://github.com/lamb-liver/appforsale/releases/tag/v1.2.0)）

**離線可用的 Android 結帳 app**：快選商品／套組、現場收款、紀錄今日營收；刻意不做進銷存或複雜後台。

> 本 repo 為 **Kotlin / Gradle** 專案（非 Node.js），依賴由 `gradle/libs.versions.toml` 管理。  
> English: [README.en.md](README.en.md) · **發佈／安裝／綠界**：[docs/distribution.md](docs/distribution.md)

---

### 主要功能

| 功能 | 說明 |
|------|------|
| 快選結帳 | 商品／套組、折扣、自訂金額、現金／行動支付、小費；主畫面橘色「結帳」按鈕顯示應收與件數 |
| 庫存 | 可選追蹤庫存；套組與單品共用庫存規則 |
| 操作回饋 | 震動 + 音效（加入購物車／結帳成功／錯誤）；設定選單可獨立開關；靜音／震動模式只震不響 |
| 今日儀表 | 當日營收與筆數摘要 |
| 復原 | 結帳成功後短時間內可復原上一筆 |
| CSV 匯出 | 頂列檔案圖示 → SAF 選路徑存檔 → 成功後系統分享選單（Line、Drive、Email 等） |
| JSON 備份／還原 | 設定選單完整備份與還原（`PosStore` 全狀態） |
| 贊助開發者 | 自願支持（30／99／150 元）；設定選單 → 綠界付款頁（外部瀏覽器），與攤位結帳無關 |

---

### 技術棧

| Kotlin | Jetpack Compose · Material 3 | MVVM（`ViewModel` + `StateFlow`） |
|--------|------------------------------|----------------------------------|
| Jetpack DataStore（Preferences + JSON） | Kotlin Coroutines · Flow | Lifecycle（`ProcessLifecycleOwner`、Compose lifecycle） |
| 綠界贊助（外部瀏覽器） | kotlinx.collections.immutable | 無 Hilt／Room |

> 未使用 **Hilt／Room**：持久化與交易語意見 [ADR-0001](docs/adr/0001-pos-state-in-datastore-json.md)。

---

### 專案結構

```
stallpos/
├── app/src/main/java/com/lambliver/stallpos/
│   ├── domain/          # 協調器（目錄／購物車／結帳）、對帳、網域模型、UI 契約
│   ├── data/            # PosPersistence、PosStore、JSON codec、CSV／備份 I/O、AppUiPreferences
│   └── ui/              # Activity、Compose、ViewModel、theme
│       ├── feedback/    # PosFeedbackManager（震動 + SoundPool 音效）
│       ├── sponsor/     # SponsorLinks、贊助 Sheet、開啟綠界付款頁
│       ├── animation/   # 快選 tile 按壓縮放
│       └── pos/         # PosAppShell、主畫面、PosCheckoutButton、結帳／儀表 sheet
├── app/src/test/        # 單元測試（Coordinator、JSON、結帳金額…）
├── docs/adr/            # 架構決策紀錄
├── docs/distribution.md # 發佈、sideload 安裝、綠界贊助設定
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
| **ui** | `PosViewModel` 訂閱 `PosPersistence.snapshot`；購物車／目錄／結帳委派各 Coordinator；`PosAppShell` 處理 SAF 匯出與 `csvShareUriFlow` 分享；`PosFeedbackManager` 統一震動／音效；贊助在 `ui/sponsor` |
| **domain** | 純規則與協調結果（`CartResult`、`CatalogPersistPlan`、`CheckoutWriteRequest`）；`PosUiState` 衍生欄位（`cartItemCount`、`checkoutSurfaceReceivablePreview`） |
| **data** | `PosPersistence` 介面 + `PosStore`（DataStore）；`AppUiPreferences`（`haptic_enabled`／`sound_enabled`）；`applyCatalog` 原子寫入；`checkout` 為 internal extension |

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
| `PosFeedbackManagerTest` | 音效播放條件（`shouldPlaySound`：NORMAL / VIBRATE / SILENT） |
| `CheckoutBottomSheetComposeTest` | 結帳 sheet 互動（Robolectric Compose） |
| `SponsorLinksTest` | 贊助按鈕文案與金額檔位 |
| `SponsorPaymentTest` | 空白 URL 不啟動付款頁（Robolectric） |

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

#### 操作回饋（震動 + 音效）

| 觸發 | 回饋 | 說明 |
|------|------|------|
| 快選 tile 按下 | 短震 + click | `ProductTile` → `PosFeedbackManager.lightTap()` |
| 結帳成功 | 2×pulse + chime | `checkoutSuccessFlow` → `checkoutSuccess()` |
| 錯誤 Toast／現金不足 | 長震 + bump | `PosToastSeverity.Error` → `error()` |

- **設定**：齒輪選單「震動回饋」「音效回饋」各自 Checkmark 切換（DataStore `AppUiPreferences`）。
- **靜音策略**：僅 `RINGER_MODE_NORMAL` 播音；VIBRATE／SILENT 只保留震動（若震動開關開啟）。
- **音效**：`SoundPool` + `res/raw/*.wav`，`USAGE_MEDIA` 固定音量；`PosApp` 以 `rememberPosFeedback()` 建立，`Activity` 銷毀時 `release()`。
- **不觸發**：開結帳 sheet、收鍵盤、按「結帳」導航——只有資料變更或結果確認才回饋。

#### 贊助開發者（綠界）

1. 設定選單 → **贊助開發者**
2. 選擇金額檔（例：打道音遊（贊助開發者30元））
3. 以外部瀏覽器開啟綠界付款頁完成付款

綠界 URL 與 sideload 安裝步驟見 **[docs/distribution.md](docs/distribution.md)**。

---

### 本機環境

1. **Android Studio**（建議最新穩定版）、**JDK 17+**、Android SDK **API 35**（與 `compileSdk` / `targetSdk` 一致）。
2. 複製本機設定：
   ```bash
   cp local.properties.example local.properties
   ```
   編輯 `sdk.dir` 指向你的 Android SDK。綠界付款 URL 見 `ui/sponsor/SponsorLinks.kt`。
3. 組建與測試：
   ```bash
   ./gradlew :app:assembleDebug
   ./gradlew :app:testDebugUnitTest
   ```
4. 執行 App：入口 `com.lambliver.stallpos.ui.MainActivity`，`applicationId` 同 `namespace`。

**發佈**：sideload APK，詳見 [docs/distribution.md](docs/distribution.md)（組建、安裝、綠界、Release 檢查）。

---

### 敏感資訊與建置設定

| 項目 | 存放位置 | 說明 |
|------|----------|------|
| 綠界付款 URL | `ui/sponsor/SponsorLinks.kt` | 三檔 `ECPAY_URL_TIER_*`；與後台金額 30／99／150 一致 |
| Release 簽章 | `local.properties`（範本見 example） | `storeFile`／密碼等；**尚未**接入 `signingConfigs` |
| SDK 路徑 | `local.properties` → `sdk.dir` | 本機路徑，勿提交 |

專案內**無** Firebase `google-services.json`、AdMob、Play Billing、後端 API key。

---

### 上傳 Git 前檢查

| 項目 | 說明 |
|------|------|
| 勿提交 | `local.properties`、`*.keystore`、`keystore.properties`、`app/build/`、`.gradle/`、`.idea/`（見 `.gitignore`） |
| 應提交 | 原始碼、`gradle/wrapper/`、`gradlew*`、`libs.versions.toml`、`local.properties.example` |
| 敏感資訊 | 簽章密碼只放在 **gitignore 的** `local.properties` 或本機 keystore |
| Release 建置 | `./gradlew :app:assembleRelease`；確認 `SponsorLinks` 已填綠界 URL（若需開放贊助） |
| 首次初始化 | `git init && git add . && git status` 確認沒有 build 產物被 staged |

```bash
# 建議：確認沒有誤加 build（應無輸出）
git status --ignored | head -20
```

---

### 授權

未於本 repo 標示授權文件前，預設為版權所有；若需開源請自行補上 `LICENSE`。
