# StallPOS v1.5 Upgrade QA

## Automated fixtures

`LegacyRetirementInstrumentedTest` 必須通過：

- v1.2 fixture：Catalog、Bundle、Stock、Cart、Sale、LastCheckout、UI preferences；原 schema 無 Reversal，升級後維持空集合。
- v1.3 fixture：上述資料加 Sale UUID 與 Reversal。
- v1.4 fixture：Room 已有較新資料、DataStore 保留較舊 frozen payload；retirement 不得覆寫 Room。
- malformed Sales／Catalog／Cart：Room 不變、十個 legacy keys 全部保留、state 為 `preserved_invalid_v1`，重啟與核心 POS 流程正常。

每個正常 fixture 需完成：Room 核對 → legacy keys 清除 → 重啟 → 結帳 → Undo → CSV → Backup → 空資料 restore → 原 Backup restore → 重啟。

## Synthetic signing-chain upgrade

Synthetic APK 只驗證「相同 production certificate」下的 Android update 與 migration，不代表既有 debug APK 可直接升級；舊 APK 不得發布為 Release asset。

1. 使用 production key 簽署 synthetic v1.2、v1.3、v1.4 APK。
2. 各版建立與 automated fixture 等價的資料。
3. 以 `adb install -r` 安裝 signed v1.5。
4. 核對 Products、Categories、Bundles、Stock、Cart、Sales、Reversals、LastCheckout 與 UI preferences。
5. 執行結帳、Undo、CSV、Backup／Restore 與重啟。

## Debug／未知 signing identity 搬遷

1. 在舊 App 匯出 StallPOS JSON。
2. 確認檔案位於 App sandbox 外、大小非零且可重新選取。
3. 移除舊 App；不可用 `adb install -r` 繞過 certificate mismatch。
4. 安裝 GitHub Release 的 signed v1.5 APK。
5. 還原 JSON，核對 Products、Stock、Sales、Reversals 與有效營收。
6. 飛航模式完成結帳、Undo、Dashboard、CSV、Backup／Restore與重啟。

v1.0／v1.1 使用 `com.lambliver.appforsale`，同樣只支援 JSON 搬遷；v1.2 起使用 `com.lambliver.stallpos`。
