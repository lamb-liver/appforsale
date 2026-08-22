# Context: 市集攤販 POS

> 為實體市集或小型同人展位設計的本機結帳體驗：**快選商品、當場收款、紀錄當日營收**，刻意不做完整進銷存或複雜後台流程。

## Glossary

### 商品
可由攤主打進目錄的單項售品，有名稱、售價與選填分類。可選擇是否追蹤庫存；若追蹤，結帳成功後會依照扣庫量減少剩餘庫存。

### 商品分類
將商品分組的標籤，僅為目錄與點選服務；不外加獨立的「類別會計」流程。

### 套組（Bundle）
以固定售價售出的組合項目，成份指向既有商品數量。**套組**與**單獨選購的同一商品**會共同消耗庫存，需有一致的「尚可賣出」規則（含夾縫調整）。

### 購物車
進行中交易之暫選內容：同時存放一般商品數量與套組數量。未結帳前可來回增減。**購物車**不是會計紀錄；結帳成功後視設計清空或對應到銷貨紀錄。

### 目錄
攤面上可點選之**商品**、**套組**、分類資料之總覽。**目錄**變更不必然代表已發生收款。

### 應收款（此筆交易客人應付）
結帳當下，折讓／加價與購物車目錄小計合成後的金額，作為確認收款動作的依據。程式內可能存在多種 `total` 命名——彼此語意不全相同；對帳時以對照文件為準。

**`PosUiState.total` 備註（不做 rename）**：歷史上恒等於**目錄小計**（`subtotal`），與 `SaleRecord.total`（**應收款**，無小費）及 `LastCheckout.total`（應收＋小費）不同義。為避免大範圍改動，欄位名維持 `total`，語意以本檔與 `phase-a-checkout-money-flow.md` 為準。

### 結帳金額型別（`CheckoutAmounts` 模組）

| 領域詞 | 型別／屬性 | 禁止混用 |
|--------|------------|----------|
| 目錄小計 | `CatalogSubtotal`（`.cents`） | 勿當成應收或 `SaleRecord.total` |
| 加價／折讓淨調整 | `NetPaymentAdjustment`（`.cents`） | 勿寫入 `SaleRecord.discount`（恒 0） |
| 應收款 | `AmountDue`（`.cents`，≥0） | 不含小費；≠ `LastCheckout.total`（含小費） |

組合事實：`CheckoutAmounts.Breakdown`；對帳：`CheckoutAmounts.reconcile`；持久化欄位仍名 `subtotal`／`total`（見 `SaleRecordAmountFields`）。

### 加價／折讓淨調整
相對於**購物車**目錄小計的調整項（例如自訂加價、折扣折算後）。最終仍以**應收款**對外。

### 結帳
收款動作確認的時間點：以使用者確認時鎖定的記憶體購物車快照為準，並在同一個 Room write transaction 內讀取最新 Catalog／庫存、寫入**銷貨紀錄**與名稱快照、扣庫存、清除 Room 購物車並更新 LastCheckout。任一步失敗即全部 rollback；不得改讀可能仍在 debounce 的 Room 購物車取代確認快照。

### 銷貨紀錄（Sale）
每次**結帳**成功追加的一則不可變紀錄，使用永久 UUID 識別。`SaleRecord.total` 是不含小費的應收款；有效營收需計入 `total + tipAmount`。Sales audit log 不修改、不刪除。

### 復原紀錄（Reversal）
復原上一筆結帳時追加的不可變紀錄，使用自己的 UUID 並以 `saleId` 指向原 Sale。復原不移除或改寫 Sale；同一 Sale 最多一筆 Reversal。

### 報表真相源
`Sales + Reversals` 是 dashboard、CSV 與 reports 的唯一真相源。`activeSales` 排除已被 Reversal 指向的 Sale；有效筆數為其筆數，有效營收為 `sum(total + tipAmount)`。Room 以 SQL 產生 aggregate，並與 Kotlin 明細結果對帳；舊 DataStore `total_sales`／`tx_count` 僅是 frozen migration artifact。

### 上一筆結帳（可用於復原）
Room `last_checkout` 僅保存 slot 1 與 `sale_id`。復原資格驗證、庫存／購物車恢復、Reversal append 與 LastCheckout clear 必須在同一個 write transaction 內完成；孤兒或已復原 saleId 一律 no-op。

### 本機持久化
Business data 以 Room DB `stallpos.db` version 1 儲存，使用 SQLite 2.7.0 `BundledSQLiteDriver`。`PosPersistence` 仍是 ViewModel 唯一 seam；Compose 不接觸 Entity／DAO。DataStore 只繼續寫 UI preferences，v1.2／v1.3 business JSON 成功匯入後保留原文但永不再讀寫。

### 營運摘要（今日）
聚合當日與總和的營運數字，僅為攤販現場自省用，非雲端報表。

### 備份檔版本（`schemaVersion` vs `payloadSchema`）

完整備份為 JSON **Envelope** + **`payload`** 物件（見 `PosStore.exportFullBackupJson`）。

| 欄位 | 層級 | 職責 |
|------|------|------|
| **`schemaVersion`** | Envelope（根物件） | 控制 `parseBackupEnvelope` 是否接受、以及還原時執行哪些 **遷移步驟**（`BackupMigration.migrateV1ToV2` …）。App 支援上限為 `PosStore.BACKUP_SCHEMA_VERSION`。 |
| **`payloadSchema`** | Payload 內 | 標記業務資料束形狀；v4 新增 checkout line `displayName` 快照。 |

匯出時兩者現行同為 `4`。舊版 `schemaVersion: 1／2／3` 會依序遷移。Local DataStore 先以 custom `DataMigration<Preferences>` 完成 transaction schema 2→3，再於單一 Room transaction 匯入並寫入 `legacy_import_version=3`；失敗會 rollback 且下次啟動重試。v3→v4 只用同一 backup Catalog 回填可證明的名稱，不猜測遺失資料。

## Relationships

- **目錄**由多個 **商品**、多個 **套組**（與各自的 **商品分類**／套組分類）組成。
- **購物車**引用既有的 **商品** 與 **套組**；調整數量時必須尊重庫存與套組相互排擠規則。
- 一次 **結帳** 產生一筆 **Sale**，並維護 **上一筆結帳（可用於復原）**；復原時保留 Sale 並追加 **Reversal**。
- **應收款**由 **購物車**之目錄小計再加上 **加價／折讓淨調整**派生並加以驗證。
