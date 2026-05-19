# 發佈、安裝與綠界贊助

本 App **不需 Google Play、AdMob 或應用內購**。攤販以 **sideload APK** 安裝即可；POS 結帳全程本機離線。

---

## 組建 APK

```bash
./gradlew :app:assembleDebug      # 開發／自用
./gradlew :app:assembleRelease    # 對外發佈（需自行設定簽章）
```

產物路徑：`app/build/outputs/apk/<buildType>/app-<buildType>.apk`

| 項目 | 說明 |
|------|------|
| `applicationId` | `com.lambliver.stallpos` |
| 版本號 | 根目錄 `VERSION` → `versionName`；`versionCode` 見 `app/build.gradle.kts` |
| Release 簽章 | 範本見 `local.properties.example`；**尚未**接入 `signingConfigs`，上架前請自行 wiring |

---

## 安裝到攤機（sideload）

1. 將 APK 傳到手機（Line、AirDrop、USB、`adb install` 等）。
2. 系統設定 → **允許安裝未知來源**（或僅允許該檔案管理 App）。
3. 點 APK 安裝。

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**升級注意**

- 套件名為 `com.lambliver.stallpos`；舊版 `com.lambliver.appforsale` 視為不同 App，資料不會自動帶過。
- 升級前建議用 App 內 **JSON 備份**，安裝新版後 **還原**。

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
| 測試 | `./gradlew :app:testDebugUnitTest` |
| 版本 | 更新 `VERSION`、`versionCode`、`CHANGELOG.md` |
| 綠界 | 若開放贊助，確認三個 `ECPAY_URL_*` 已填且實機可開啟 |
| 簽章 | Release APK 已簽章 |
| Git tag | 例：`git tag v1.2.0` |
| GitHub Release | 上傳 `app-release.apk` 與 Release 說明（可貼 CHANGELOG 1.2.0 段落） |

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

English summary: see [README.en.md](../README.en.md) · build with Gradle · install APK sideload · set ECPay URLs in `SponsorLinks.kt`.
