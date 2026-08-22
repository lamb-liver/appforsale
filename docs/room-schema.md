# Room DB v1 schema

Database: `stallpos.db` · version: `1` · exported schema: `app/schemas/com.lambliver.stallpos.data.StallPosDatabase/1.json`

| Table | Key / ordering | Purpose |
|---|---|---|
| `categories` | `id`, `sort_order` | 商品分類 |
| `products` | `id`, `sort_order` | 商品、價格與 nullable stock |
| `bundle_categories` | `id`, `sort_order` | 套組分類 |
| `bundles` | `id`, `sort_order` | 套組定義 |
| `bundle_components` | (`bundle_id`, `component_index`) | 成分順序；允許同商品重複多行 |
| `cart_items` | (`item_type`, `item_id`) | 進行中購物車 |
| `sales` | UUID `id`, unique `audit_order` | Append-only Sale header |
| `sale_lines` | (`sale_id`, `line_index`) | Cart / checkout line 與 nullable 名稱快照 |
| `sale_stock_deductions` | (`sale_id`, `product_id`) | Undo 所需的實際扣庫量 |
| `reversals` | UUID `id`, unique `sale_id`, `audit_order` | Append-only Undo audit |
| `last_checkout` | fixed `slot = 1`, unique `sale_id` | 目前可復原交易 |
| `app_meta` | `key` | Legacy import marker |

## Invariants

- Sale、SaleLine、stock deduction 與 Reversal runtime 沒有 delete API；audit FK 使用 `NO ACTION`。
- Reversal `sale_id` 唯一，同一 Sale 最多復原一次。
- 有效 aggregate 是 Sales 排除 Reversals，revenue 為 `SUM(total + tip_amount)`。
- 所有 list 以 `sort_order`、`component_index`、`line_index` 或 `audit_order` 明確排序。
- Room migration 禁止 destructive fallback。
