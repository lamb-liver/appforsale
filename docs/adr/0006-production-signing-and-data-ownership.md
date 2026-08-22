# ADR-0006：Production signing、資料所有權與 legacy retirement

**Status**：Accepted
**Date**：2026-08-22

## Context

v1.4 已以 Room 取代 DataStore business JSON，但尚無永久 release signing、可驗證 APK 或明確 Android 系統備份政策。若繼續發布未簽章產物、保留兩條還原路徑或讓 cleanup 回頭匯入 legacy，會重新形成身分與資料真相不明的狀態。

## Decision

- v1.5 建立永久 APK signing certificate；release build 缺任何簽章輸入即失敗。
- GitHub tag 經 CI 產生 signed APK 與 APK SHA-256，先完成 draft assets 再發布 Immutable Release。
- Room 是唯一 runtime business source of truth；DataStore 只持續保存 UI preferences。
- `legacy_import_version=3` 後只執行 post-migration retirement：全部 legacy payload 安全才整批清除，否則完整保留；不得重新 import 或改寫 Room。
- StallPOS JSON 是唯一正式跨安裝 business-data 搬遷格式；Android Auto Backup／D2D 不支援。

## Consequences

- v1.5 是正式 signing chain 起點；既有 debug／未知簽章安裝需以 JSON 搬遷。
- 遺失 release key 即無法對已安裝版本提供原地更新，因此 recovery rehearsal 是 release gate。
- Room schema 仍為 1，backup schema 仍為 4；產品操作與 UI 不變。
