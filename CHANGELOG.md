# Changelog

格式以 [Keep a Changelog](https://keepachangelog.com/zh-TW/1.1.0/) 為參考，版本號採 [Semantic Versioning](https://semver.org/lang/zh-TW/)。

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

- 發佈／安裝／綠界設定：[docs/distribution.md](docs/distribution.md)
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

[1.2.0]: https://github.com/lamb-liver/appforsale/releases/tag/v1.2.0
[1.1.0]: https://github.com/lamb-liver/appforsale/releases/tag/v1.1.0
[1.0.0]: https://github.com/lamb-liver/appforsale/releases/tag/v1.0.0
