# 發佈、安裝與綠界贊助

本 App **不需 Google Play、AdMob 或應用內購**。攤販以 **sideload APK** 安裝即可；POS 結帳全程本機離線。

---

## 組建 APK

```bash
./gradlew :app:assembleDebug      # 開發／自用
./gradlew :app:assembleRelease    # 對外發佈；缺任何簽章輸入會直接失敗
```

產物路徑：`app/build/outputs/apk/<buildType>/app-<buildType>.apk`

| 項目 | 說明 |
|------|------|
| `applicationId` | `com.lambliver.stallpos` |
| 版本號 | 根目錄 `VERSION` → `versionName`；`versionCode` 見 `app/build.gradle.kts` |
| Release 簽章 | 本機讀取 gitignored `local.properties`；CI 由 GitHub Secrets 注入 |
| Certificate identity | 根目錄 `RELEASE_CERT_SHA256`，64 位大寫 hexadecimal；這是 APK signing certificate fingerprint |

Release signing 必須同時提供 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`。缺少任一欄位時 `assembleRelease` fail closed，不會產出可發布 APK。

`RELEASE_CERT_SHA256` 與 Release APK 的 `.sha256` 用途不同：前者確認簽署者，後者確認下載檔案內容。

---

## 安裝到攤機（sideload）

1. 將 APK 傳到手機（Line、AirDrop、USB、`adb install` 等）。
2. 系統設定 → **允許安裝未知來源**（或僅允許該檔案管理 App）。
3. 點 APK 安裝。

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**升級注意**

- v1.5 是永久 production signing chain 起點。既有 debug／未知簽章 APK 無法直接覆蓋；必須先將 App 內 JSON 備份存到 sandbox 外，再移除舊 App、安裝 signed v1.5 並還原。
- v1.0／v1.1 的 `com.lambliver.appforsale` 與現行 `com.lambliver.stallpos` 是不同 App，同樣只支援 JSON 搬遷。
- synthetic v1.2／v1.3／v1.4 使用 production key 的測試，只證明相同 signing chain 下的 migration，不代表既有 debug APK 可直接升級。

完整矩陣見 [upgrade-qa.md](upgrade-qa.md)。

---

## Backup ownership

| 資料 | 唯一責任 |
|------|----------|
| Room `stallpos.db` | runtime business source of truth |
| DataStore `pos_store` | UI preferences；legacy business keys 僅供一次性安全退休 |
| StallPOS JSON | 唯一正式跨安裝 business-data 搬遷格式 |
| Android Auto Backup／D2D | 不支援；Manifest 與 Android 11／12+ 規則均停用 |

JSON 備份不包含震動／音效偏好；新安裝使用預設值。

---

## 綠界贊助（選用）

贊助與攤位收款無關，僅供使用者自願支持開發。

### 1. 申請綠界

依 [綠界科技](https://www.ecpay.com.tw/) 流程開立商店，並建立對應金額的付款連結（或一頁式收款頁）。

### 2. 填入 App

編輯 `app/src/main/java/com/lambliver/stallpos/ui/sponsor/SponsorLinks.kt`：

| 常數 | 金額 | 按鈕文案 |
|------|------|----------|
| `ECPAY_URL_TIER_SMALL` | 30 元 | 打道音遊（贊助開發者30元） |
| `ECPAY_URL_TIER_MEDIUM` | 99 元 | 喝杯咖啡（贊助開發者99元） |
| `ECPAY_URL_TIER_LARGE` | 150 元 | 吃個便當（贊助開發者150元） |

後台金額須與上表一致。URL 留空則該檔按鈕維持停用。

### 3. 使用者流程

設定（齒輪）→ **贊助開發者** → 選金額 → 外部瀏覽器完成綠界付款。

---

## 發佈檢查清單

| 步驟 | 說明 |
|------|------|
| 測試 | unit、lint、debug build、API 35 instrumented tests 全數通過 |
| 版本 | 更新 `VERSION`、`versionCode`、`docs/CHANGELOG.md` |
| 綠界 | 若開放贊助，確認三個 `ECPAY_URL_*` 已填且實機可開啟 |
| 簽章 | `apksigner` 通過，certificate SHA-256 等於 `RELEASE_CERT_SHA256` |
| Recovery | 實際解密其中一份 keystore 備份，alias 與 fingerprint 均正確 |
| Legacy retirement | v1.2／v1.3／v1.4、malformed 與 crash-retry gates 通過 |
| GitHub | Immutable Releases 已啟用；workflow actions 全部固定完整 commit SHA |
| Git tag | 例：`git tag -a v1.5.0 -m 'StallPOS v1.5.0' && git push origin v1.5.0` |
| Release | Actions 建立 draft、上傳 signed APK＋checksum、逐 byte 驗證後發布 immutable Release |

Repository secrets：

- `STALLPOS_RELEASE_KEYSTORE_BASE64`
- `STALLPOS_RELEASE_STORE_PASSWORD`
- `STALLPOS_RELEASE_KEY_ALIAS`
- `STALLPOS_RELEASE_KEY_PASSWORD`

發布後若需修正，建立下一個 patch version；不得移動 tag 或替換 assets。
若 tag 已正確建立、但 Release workflow 僅因 CI 基礎設施失敗，可執行 `gh workflow run android.yml --ref main -f tag=v1.5.0` 重跑同一 tag；不得重新打 tag。

---

## 驗收清單（改版後）

| 項目 | 預期 |
|------|------|
| 主畫面 | 頂部無廣告 |
| 結帳／復原／儀表 | 正常 |
| CSV／JSON 備份還原 | 正常 |
| 震動／音效 | 設定開關有效 |
| 贊助開發者 | 三檔文案正確；有 URL 時可開瀏覽器 |

---

English summary: see [README.en.md](README.en.md) · build with Gradle · install APK sideload · set ECPay URLs in `SponsorLinks.kt`.
