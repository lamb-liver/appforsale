# Room schema

現行 runtime：加密 `stallpos.db` · **version 2** · `app/schemas/com.lambliver.stallpos.data.StallPosV2Database/2.json`

v1 明文 schema 僅供升級：`app/schemas/com.lambliver.stallpos.data.StallPosDatabase/1.json`。v1→v2 為驗證後原子替換，禁止 destructive fallback。

## v2 tables

| Table | Purpose |
|---|---|
| `categories` / `products` / `bundle_categories` / `bundles` / `bundle_components` | 目錄 |
| `cart_items` | 進行中購物車 |
| `sales` / `sale_lines` / `sale_stock_deductions` | Append-only 銷貨 |
| `reversals` / `last_checkout` | 復原（VOID）稽核與可復原槽 |
| `events` | 活動生命週期 |
| `inventory_levels` / `inventory_movements` | GENERAL／EVENT 庫存真相 |
| `sale_line_snapshots` / `bundle_component_allocations` | 交易當下名稱／分攤快照 |
| `*_v2_meta` | 雲端同步 metadata |
| `sync_outbox` / `cloud_state` / `device_state` | Outbox 與帳號／裝置 |
| `app_meta` | legacy import marker；不承載業務列 |

## Invariants

- Sale、庫存移動與 VOID 沒有 runtime delete；同一 Sale 最多一筆復原。
- 有效營收是 Sales 排除 VOID，`SUM(total + tip_amount)`。
- 庫存剩餘量由 `inventory_movements` 派生，不以獨立「改庫存」覆寫。
- DataStore 只存 UI 偏好；業務 JSON 跨安裝格式見 README 備份版本（現行 envelope／payload **5**）。
