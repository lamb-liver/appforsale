# Changelog

格式以 [Keep a Changelog](https://keepachangelog.com/zh-TW/1.1.0/) 為參考，版本號採 [Semantic Versioning](https://semver.org/lang/zh-TW/)。

## [1.4.0] - 2026-08-22

### Added

- Room 3 DB v1：目錄、購物車、Sales、Sale lines、庫存扣除、Reversals 與 LastCheckout 關聯表
- SQLite 2.7.0 `BundledSQLiteDriver`、KSP schema generation 與 committed Room schema JSON
- 交易當下商品／套組名稱快照；backup schema 1／2／3→4 migration

### Changed

- `PosViewModel` 正式 persistence 改為 `RoomPosPersistence`；Compose、介面文案與操作路徑不變
- Checkout、Undo、Catalog 同步與 backup restore 改為 Room atomic transaction
- Dashboard aggregate 由 SQL active Sales 派生，與 Kotlin `activeSales()` 共用 parity 測試
- v1.2／v1.3 DataStore business JSON 只在首次啟動匯入；成功後凍結、禁止 dual-write
- Backup restore 在取代現有資料前驗證 schema、必填欄位、唯一 ID 與 Reversal 關聯；不合法檔案保留原 DB

### Documentation

- [Room DB v1 schema](room-schema.md)
- [ADR-0005：Room 本機關聯式持久化](adr/0005-room-local-relational-persistence.md)

## [1.3.0] - 2026-08-22

### Added

- Sale／Reversal 永久 UUID 與 append-only transaction audit model
- Local DataStore transaction schema 2→3 custom migration；備份 schema 1／2→3 migration
- 日常 CSV 維持易讀欄位並只列有效交易；完整 audit history 保留於 JSON backup

### Changed

- Dashboard、CSV 與 reports 統一由 active Sales（Sales 排除 Reversals）派生；營收包含小費
- Undo 改為同一個 DataStore `edit` 內驗證並追加 Reversal，Sale 不再刪除
- 移除 5,000 筆 Sales 裁切；`total_sales`／`tx_count` 降為 legacy cached aggregate
- 備份 envelope 整數 parser 支援 Long，避免 `exportedAtMillis` overflow
- 修正 launcher 圖示檔案格式，確保 clean release build 可正常打包

## [1.2.0] - 2026-05-20

### Added

- 贊助開發者：三檔固定金額（30／99／150 元）經綠界付款頁，以外部瀏覽器完成（`SponsorLinks`）

### Removed

- AdMob 橫幅、UMP 同意流程
- Google Play 應用內購（贊助／去廣告）
- `ACCESS_NETWORK_STATE` 權限（POS 本機不需）

### Changed

- 套件名稱 `com.lambliver.stallpos`；無商業化 SDK，適合 sideload 發佈

### Documentation

- 發佈／安裝／綠界設定：[distribution.md](distribution.md)
- README 移除 AdMob、Play Billing 相關說明

## [1.1.0] - 2026-05-20

### Added

- 操作回饋：快選加入、結帳成功、錯誤 Toast 的震動與音效（`PosFeedbackManager` + `res/raw/*.wav`）
- 設定選單可獨立開關震動／音效（`AppUiPreferences`）；靜音／震動鈴聲模式僅震不響
- 主畫面橘色「結帳」按鈕（`PosCheckoutButton`）顯示應收金額與購物車件數
- 快選 tile 按壓縮放動畫；`PosViewModelToast` 集中 Toast 與錯誤回饋

### Changed

- `PosUiState` 新增 `cartItemCount`、`checkoutSurfaceReceivablePreview` 等衍生欄位供主畫面結帳列使用

## [1.0.0] - 2026-05-18

### Added

- 離線市集 POS：快選商品／套組、折扣、自訂金額、現金／行動支付、小費
- 可選庫存追蹤；套組與單品共用庫存規則
- 今日儀表板、結帳復原
- 銷售 CSV 匯出（SAF 存檔 + 系統分享）
- JSON 完整備份／還原
- AdMob 橫幅、UMP 同意流程
- Google Play 贊助 INAPP（去廣告）

[1.4.0]: https://github.com/lamb-liver/appforsale/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/lamb-liver/appforsale/releases/tag/v1.3.0
[1.2.0]: https://github.com/lamb-liver/appforsale/releases/tag/v1.2.0
[1.1.0]: https://github.com/lamb-liver/appforsale/releases/tag/v1.1.0
[1.0.0]: https://github.com/lamb-liver/appforsale/releases/tag/v1.0.0
