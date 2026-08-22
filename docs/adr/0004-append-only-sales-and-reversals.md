# ADR-0004：Append-only Sales 與 Reversals

**Date**：2026-08-22
**Status**：Accepted

## Context

舊版 Undo 會移除最後一筆 Sale，交易沒有永久 ID，且報表可能同時依賴 Sales log 與 `total_sales`／`tx_count`。這些語意不利於 audit、retry 與後續同步，也可能讓報表出現兩套真相。

## Decision

- 每筆 Sale 使用 client-generated UUID，建立後不修改或刪除。
- Undo 追加含 UUID 與原 `saleId` 的 Reversal；同一 Sale 只能復原一次。
- `activeSales(Sales, Reversals)` 是 dashboard、CSV 與 reports 的唯一基礎；營收為 `SaleRecord.total + tipAmount`。
- Undo 在同一個 DataStore `edit` 內讀取最新狀態、驗證資格並一次寫入所有 effects。
- `total_sales`／`tx_count` 暫留為 legacy cached aggregate；DataStore JSON 暫時不裁切 audit log。
- Local DataStore 與 backup payload 以 schema 3 補齊 legacy IDs、LastCheckout 關聯與 Reversal log。

## Alternatives considered

- **直接刪除或修改 Sale**：程式較少，但會失去 audit history，且 retry／同步難以辨識同一交易。
- **Reversal 當負數 Sale**：會混淆目前尚未實作的 refund、return 與會計分錄語意。
- **同版改用 Room**：同時改交易語意與 storage implementation，遷移風險過大；Room 延後到資料量成為實測問題時。

## Consequences

- Sales 與 Reversals 可被獨立 audit，Undo 不再破壞歷史。
- 報表不信任可能失真的 legacy cache。
- DataStore audit JSON 會持續成長；v1.4 視實測規模以 Room 接手。
