# 小攤位 · 市集 POS

**版本：v1.5.0**（`VERSION` · `versionName`）

**離線可用的 Android 結帳 app**：快選商品／套組、現場收款、紀錄今日營收；刻意不做進銷存或複雜後台。

> **v1.5 Production Baseline**：Room 是唯一 runtime business source of truth；永久 Android signing identity、fail-closed release build、GitHub Actions signed APK／checksum 與 Immutable Release 流程已就緒。產品操作與 v1.4 相同。

> 本 repo 為 **Kotlin / Gradle** 專案（非 Node.js），依賴由 `gradle/libs.versions.toml` 管理。  
> English: [README.en.md](docs/README.en.md) · **發佈／安裝／綠界**：[docs/distribution.md](docs/distribution.md)

---

### 主要功能

| 功能 | 說明 |
|------|------|
| 快選結帳 | 商品／套組、折扣、自訂金額、現金／行動支付、小費；主畫面橘色「結帳」按鈕顯示應收與件數 |
| 庫存 | 可選追蹤庫存；套組與單品共用庫存規則 |
| 操作回饋 | 震動 + 音效（加入購物車／結帳成功／錯誤）；設定選單可獨立開關；靜音／震動模式只震不響 |
| 今日儀表 | 當日營收與筆數摘要 |
| 復原 | 結帳成功後可復原上一筆；庫存、購物車與營收立即還原 |
| CSV 匯出 | 僅列有效交易的易讀報表；頂列檔案圖示 → SAF 選路徑存檔 → 系統分享選單 |
| JSON 備份／還原 | 設定選單完整備份與還原（Room business data JSON exchange format） |
| 贊助開發者 | 自願支持（30／99／150 元）；設定選單 → 綠界付款頁（外部瀏覽器），與攤位結帳無關 |

---

### 技術棧

| Kotlin | Jetpack Compose · Material 3 | MVVM（`ViewModel` + `StateFlow`） |
|--------|------------------------------|----------------------------------|
| Room 3.0.1 + SQLite 2.7.0 `BundledSQLiteDriver` | Kotlin Coroutines · Flow | Lifecycle（`ProcessLifecycleOwner`、Compose lifecycle） |
| DataStore（UI preferences；legacy business data 僅供一次性退休） | kotlinx.collections.immutable | 無 Hilt／後端 |

> Room 選型與 legacy import 語意見 [ADR-0005](docs/adr/0005-room-local-relational-persistence.md)；DB v1 表格見 [room-schema.md](docs/room-schema.md)。

---

### 專案結構

```
stallpos/
├── .github/workflows/  # PR／main CI 與 tag signed Release pipeline
├── app/src/main/java/com/lambliver/stallpos/
│   ├── domain/          # 協調器（目錄／購物車／結帳）、對帳、網域模型、UI 契約
│   ├── data/            # PosPersistence、Room DB/DAO、legacy JSON migration、CSV／備份 I/O
│   └── ui/              # Activity、Compose、ViewModel、theme
│       ├── feedback/    # PosFeedbackManager（震動 + SoundPool 音效）
│       ├── sponsor/     # SponsorLinks、贊助 Sheet、開啟綠界付款頁
│       ├── animation/   # 快選 tile 按壓縮放
│       └── pos/         # PosAppShell、主畫面、PosCheckoutButton、結帳／儀表 sheet
├── app/src/test/        # 單元測試（Coordinator、JSON、結帳金額…）
├── docs/
│   ├── adr/             # 架構決策紀錄
│   ├── distribution.md  # 發佈、sideload 安裝、綠界贊助設定
│   ├── upgrade-qa.md    # production signing chain 與 JSON 搬遷驗收
│   ├── CONTEXT.md       # 領域名詞（商品、購物車、結帳…）
│   ├── CHANGELOG.md
│   ├── README.en.md
│   └── cursor/rule/     # Cursor 規則
├── gradle/              # Version catalog、Wrapper
├── RELEASE_CERT_SHA256  # 公開 APK signing certificate fingerprint
├── VERSION              # App 版本單一來源（同步 versionName）
├── local.properties.example
└── .cursorrules         # 減法設計、戶外高對比 UI
```

#### 分層與單一 seam

| 層 | 職責 |
|----|------|
| **ui** | `PosViewModel` 訂閱 `PosPersistence.snapshot`；購物車／目錄／結帳委派各 Coordinator；`PosAppShell` 處理 SAF 匯出與 `csvShareUriFlow` 分享；`PosFeedbackManager` 統一震動／音效；贊助在 `ui/sponsor` |
| **domain** | 純規則與協調結果（`CartResult`、`CatalogPersistPlan`、`CheckoutWriteRequest`）；`PosUiState` 衍生欄位（`cartItemCount`、`checkoutSurfaceReceivablePreview`） |
| **data** | `PosPersistence` 介面 + `RoomPosPersistence`；Room transaction 原子處理 catalog／checkout／undo／restore；DataStore 僅持續保存 UI preferences，legacy business keys 安全退休或完整保留 |

建議閱讀順序：**`README` → `docs/CONTEXT.md` → `ui/PosViewModel.kt` → `domain/PosCheckoutCoordinator.kt` → `data/PosPersistence.kt` → `data/RoomPosPersistence.kt` → `docs/room-schema.md`**。

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
| `PosUndoCoordinatorTest` | append-only 復原規則、孤兒／重複拒絕、庫存還原 fallback |
| `BackupMigrationTest` | 備份 schema 1／2／3→4、stable ID、LastCheckout 配對、名稱快照與冪等性 |
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
| **`payloadSchema`** | `payload` 物件內 | 業務資料束形狀；v4 新增交易當下商品／套組名稱快照 |

現行皆為 **4**。舊 **schemaVersion: 1／2／3** 備份會依序遷移；v2→v3 補 stable identity 與 Reversal log，v3→v4 僅以同一備份的 Catalog 回填可證明的交易名稱，無法回填時保留 `null`。

儀表測試（需模擬器／裝置）：`PosStoreInstrumentedTest`（Room 結帳／復原、rollback、關聯與大量歷史）。
`LegacyRetirementInstrumentedTest` 驗證 v1.2／v1.3／v1.4 fixtures、全有或全無 cleanup 與 malformed legacy 隔離。

v1.5 驗收基線（2026-08-22）：128 個 unit tests、17 個 API 35 instrumented tests、`lintDebug`、debug／signed release build 均通過；production-signed synthetic v1.2／v1.3／v1.4 原地升級，以及未知簽章 JSON 搬遷亦已實機驗證。

---

### v1.5 資料與發佈契約

| 項目 | 契約 |
|------|------|
| Room `stallpos.db` | 唯一 runtime business source of truth |
| DataStore `pos_store` | UI preferences；legacy business keys 僅能整批安全退休或完整保留 |
| StallPOS JSON | 唯一正式跨安裝 business-data 搬遷格式 |
| Android Auto Backup／D2D | 不支援；Manifest 與 Android 11／12+ 規則均排除 App data |
| Release APK | 永久 production certificate 簽署；certificate fingerprint 見 `RELEASE_CERT_SHA256` |

PR 與 `main` push 會執行 unit、lint、debug build 與 API 35 instrumented tests。`v*` tag 只有在版本、main ancestry、CHANGELOG、Release collision 與 Immutable Releases 驗證通過後，才會建立 signed APK、APK SHA-256、draft Release，逐 byte 核對 assets 後發布。詳見 [android.yml](.github/workflows/android.yml) 與 [distribution.md](docs/distribution.md)。

---

### 架構決策（ADR）

- [ADR-0001 — DataStore + JSON 集中狀態](docs/adr/0001-pos-state-in-datastore-json.md)
- [ADR-0002 — 結帳確認點對帳](docs/adr/0002-checkout-reconcile-at-confirm.md)
- [ADR-0003 — 購物車記憶體 + debounce 寫碟](docs/adr/0003-cart-memory-with-debounced-disk-flush.md)
- [ADR-0004 — Append-only Sales 與 Reversals](docs/adr/0004-append-only-sales-and-reversals.md)
- [ADR-0005 — Room 本機關聯式持久化](docs/adr/0005-room-local-relational-persistence.md)
- [ADR-0006 — Production signing、資料所有權與 legacy retirement](docs/adr/0006-production-signing-and-data-ownership.md)

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
   ./gradlew :app:lintDebug
   ./gradlew :app:connectedDebugAndroidTest  # 需 API 35 emulator／裝置
   ```
4. 執行 App：入口 `com.lambliver.stallpos.ui.MainActivity`，`applicationId` 同 `namespace`。

**發佈**：sideload APK，詳見 [docs/distribution.md](docs/distribution.md)（組建、安裝、綠界、Release 檢查）。

---

### 敏感資訊與建置設定

| 項目 | 存放位置 | 說明 |
|------|----------|------|
| 綠界付款 URL | `ui/sponsor/SponsorLinks.kt` | 三檔 `ECPAY_URL_TIER_*`；與後台金額 30／99／150 一致 |
| Release certificate identity | `RELEASE_CERT_SHA256` | 公開的 64 位大寫 SHA-256；不是 keystore 或 APK checksum |
| 本機 Release 簽章 | macOS Keychain／gitignored `local.properties` | Gradle 接受環境變數或範本中的四個 signing 欄位；缺任一項即 fail closed |
| CI Release 簽章 | GitHub Actions Secrets | keystore base64、store password、alias、key password；只注入 release build step |
| Keystore recovery | repo 外兩份加密備份 | 發布前須實際解密一份並核對 alias 與 certificate fingerprint |
| SDK 路徑 | `local.properties` → `sdk.dir` | 本機路徑，勿提交 |

專案內**無** Firebase `google-services.json`、AdMob、Play Billing、後端 API key。

---

### 上傳 Git 前檢查

| 項目 | 說明 |
|------|------|
| 勿提交 | `local.properties`、`*.keystore`、`keystore.properties`、`app/build/`、`.gradle/`、`.idea/`（見 `.gitignore`） |
| 應提交 | 原始碼、workflow、`RELEASE_CERT_SHA256`、`gradle/wrapper/`、`gradlew*`、`libs.versions.toml`、`local.properties.example` |
| 敏感資訊 | 簽章密碼只放在本機 Keychain、gitignored `local.properties` 或 GitHub Secrets；不得提交 repo |
| Release 建置 | `./gradlew :app:assembleRelease`；必須 signed 且 fingerprint 與 `RELEASE_CERT_SHA256` 一致 |
| 首次初始化 | `git init && git add . && git status` 確認沒有 build 產物被 staged |

```bash
# 建議：確認沒有誤加 build（應無輸出）
git status --ignored | head -20
```

---

### 授權

未於本 repo 標示授權文件前，預設為版權所有；若需開源請自行補上 `LICENSE`。
