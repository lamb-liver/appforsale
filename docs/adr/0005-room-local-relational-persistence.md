# ADR-0005：Room 本機關聯式持久化

**Date**：2026-08-22
**Status**：Accepted

## Context

v1.3 已將 Sale／Reversal 改為 append-only audit history，但 DataStore 每次結帳仍需重寫整份 JSON，而移除 5,000 筆裁切後會持續放大 I/O、查詢與原子還原成本。

## Decision

- Business data 改存 Room 3 DB `stallpos.db` version 1，使用 SQLite 2.7.0 `BundledSQLiteDriver` 與 KSP。
- `PosPersistence` seam 保留；`PosViewModel` 使用 `RoomPosPersistence`，UI 不接觸 DAO／Entity。
- Checkout、Undo、Catalog full-list sync、legacy import 與 backup restore 均使用單一 Room transaction。
- Sales／Reversals 繼續 append-only；`UNIQUE(reversals.sale_id)` 在 DB 層保護復原唯一性，audit 關聯禁止 cascade delete。
- v1.2／v1.3 DataStore JSON 先完成 local schema 3 遷移，再原子匯入 Room。marker 與 rows 同 transaction 寫入；v1.5 起由 [ADR-0006](0006-production-signing-and-data-ownership.md) 在 import 完成後負責全有或全無的 legacy retirement。
- JSON 備份維持產品層 exchange format，不匯出 SQLite 檔。

## Alternatives considered

- **繼續 DataStore JSON**：改動少，但 audit history 成長時每次都需完整 decode／encode。
- **DataStore 與 Room dual-write**：看似易 rollback，實際會形成兩個真相源且無法跨系統原子提交。
- **同時改 UI／repository hierarchy／pagination**：不是本版 migration 必要條件。

## Consequences

- 有效營收與筆數可由 SQL 直接派生，並以 Kotlin `activeSales()` parity test 對帳。
- 交易當下名稱可隨 Sale line 保存，Catalog rename／delete 不會改寫歷史。
- APK 因 bundled SQLite 增加 native binary 體積，但取得設備間一致 SQLite 行為。
- DB v1 後續變更必須提供 Room migration，不使用 destructive fallback。
