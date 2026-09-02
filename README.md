# 小攤位 · 市集 POS

**版本：v2.1.3**（`VERSION` · `versionName`）

安裝說明：https://lambliver.dev/projects/offline-pos-android

**離線優先的 Android 市集 POS**：快選結帳、活動庫存、雲端備份／換機恢復，以及唯讀活動分析。

> **v2 Local-first**：加密 Room v2 是裝置端真相源；離線交易先完成，再由 Outbox 同步至 Cloudflare Worker。雲端帳號與營運資料不因未活動自動刪除，只由使用者主動 Cloud Delete／Account Delete 移除。

> 本 repo 為 **Kotlin / Gradle** 專案（非 Node.js），依賴由 `gradle/libs.versions.toml` 管理。  
> English: [README.en.md](docs/README.en.md) · **發佈／安裝／綠界**：[docs/distribution.md](docs/distribution.md)

---

### 主要功能

| 功能 | 說明 |
|------|------|
| 快選結帳 | 商品／套組、商品名稱搜尋、折扣、自訂金額、現金／LINE Pay／街口支付／其他、小費；主畫面橘色「結帳」按鈕顯示應收與件數 |
| 活動庫存 | GENERAL／EVENT 庫存調撥、損壞、調整、活動結束退回；套組與單品共用 component allocation |
| 操作回饋 | 震動 + 音效（加入購物車／結帳成功／錯誤）；設定選單可獨立開關；靜音／震動模式只震不響 |
| 活動與報表 | Event 建立／開始／結束；唯讀 Web Dashboard 顯示營收、趨勢、商品、時段、付款、Bundle、庫存去化，以及可篩選／分頁的交易列表與 snapshot 明細 |
| VOID | 保留原 Sale 並 append Void／庫存回補；重送只生效一次 |
| 雲端同步／換機 | Google Login、Outbox 背景同步、裝置 transfer／forced retire、atomic bootstrap 與 Cloud epoch |
| CSV 匯出 | Android 經 SAF 匯出有效交易；Web 依目前活動、付款與 ACTIVE／VOIDED 篩選匯出同一批交易 |
| JSON 備份／還原 | 設定選單完整備份與還原（Room business data JSON exchange format） |
| 診斷資訊 | 複製／分享版本、匿名裝置 ID、同步計數、最近 request ID 與錯誤碼；不含 token 或交易內容 |
| 贊助開發者 | 自願支持（30／99／150 元）；設定選單 → 綠界付款頁（外部瀏覽器），與攤位結帳無關 |

---

### 技術棧

| Kotlin | Jetpack Compose · Material 3 | MVVM（`ViewModel` + `StateFlow`） |
|--------|------------------------------|----------------------------------|
| Room 2.8.4 + SQLCipher 4.17.0 | Kotlin Coroutines · Flow | WorkManager · Credential Manager |
| DataStore（UI preferences；legacy business data 僅供一次性退休） | Cloudflare Worker · 雙 D1 | Sentry · 無 Hilt |

> v1 Room 與 legacy import 背景見 [ADR-0005](docs/adr/0005-room-local-relational-persistence.md)；目前 v2 schema 以 `app/schemas/com.lambliver.stallpos.data.StallPosV2Database/2.json` 為準。

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
├── app/src/test/        # 單元測試（Coordinator、migration、sync、結帳金額…）
├── contracts/v2/       # Android／Worker 共用 schemas 與 golden fixtures
├── worker/             # API、Auth、Sync、雙 D1 migrations、Dashboard、Ops
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
├── AGENTS.md            # 預設寫碼原則（Ponytail）與本 repo 約束
└── .cursorrules         # 減法設計、戶外高對比 UI（精簡；完整梯子見 AGENTS.md）
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

歷史資料遷移後必須通過 **IntegrityAudit**（結構 + 跨資料對帳）。CI 與 `./gradlew :app:verifyMigrationFixtures` 跑同一組規則。

### 備份版本（兩層欄位）

| 欄位 | 層級 | 職責 |
|------|------|------|
| **`schemaVersion`** | Envelope（備份檔根物件） | `parseBackupEnvelope` 驗證與遷移步驟編排（見 `BackupMigration`） |
| **`payloadSchema`** | `payload` 物件內 | 業務資料束形狀；v4 新增交易當下商品／套組名稱快照 |

現行皆為 **5**。舊 **schemaVersion: 1／2／3／4** 備份會依序遷移；無法可靠回填的成本保持 `null`，不得視為 0。

儀表測試（需模擬器／裝置）：`PosStoreInstrumentedTest`（Room 結帳／復原、rollback、關聯與大量歷史）。
`LegacyRetirementInstrumentedTest` 驗證 v1.2／v1.3／v1.4 fixtures、全有或全無 cleanup 與 malformed legacy 隔離。

v2.1.1 發布 gate（2026-08-26）：Android unit／lint／API 35 instrumented、production-signed v1.5→v2 upgrade、Worker unit／integration、雙 D1 migration、Dashboard browser smoke 與 production Restore Drill 均納入同一條 CI／release gate。

---

### v2 資料與發佈契約

| 項目 | 契約 |
|------|------|
| Room `stallpos.db` | 唯一 runtime business source of truth |
| DataStore `pos_store` | UI preferences；legacy business keys 僅能整批安全退休或完整保留 |
| StallPOS JSON | 唯一正式跨安裝 business-data 搬遷格式 |
| Android Auto Backup／D2D | 不支援；Manifest 與 Android 11／12+ 規則均排除 App data |
| Release APK | 永久 production certificate 簽署；certificate fingerprint 見 `RELEASE_CERT_SHA256` |

**正式版本**：[下載 StallPOS v2.1.3 APK](https://github.com/lamb-liver/appforsale/releases/download/v2.1.3/StallPOS-2.1.3.apk) · [開啟唯讀 Web Dashboard](https://stallpos-v2.shiro02160420.workers.dev/dashboard/)

PR 與 `main` push 會執行 unit、lint、debug build 與 API 35 instrumented tests。Repository 必須先由 maintainer 啟用 Immutable Releases；`v*` tag 通過版本、main ancestry、CHANGELOG 與 Release collision 驗證後，才會建立 signed APK、APK SHA-256、draft Release，逐 byte 核對 assets，並在發布後驗證 `immutable=true`。既有 tag 若只因 pipeline 基礎設施失敗，可由手動入口重跑同一 tag，不得移動 tag。詳見 [android.yml](.github/workflows/android.yml) 與 [distribution.md](docs/distribution.md)。

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

[MIT](LICENSE)。Copyright (c) 2026 羊肝。
