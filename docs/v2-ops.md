# StallPOS v2 Ops Runbook

本文件是 M8 的維運 gate；所有外部 gate 已於 2026-08-24 通過後，`VERSION` 才由 `1.5.0` 更新為 `2.0.0`。

## Runtime 設定

| 設定 | 位置 | 契約 |
|---|---|---|
| `GOOGLE_CLIENT_IDS`／`DASHBOARD_GOOGLE_CLIENT_ID` | Worker secret／Dashboard var | Google ID Token 允許的 Android／Dashboard client ID |
| `SENTRY_DSN` | Worker secret／Dashboard var | 空值停用；事件不送 user、request、breadcrumb 或 extra |
| `STALLPOS_SENTRY_DSN`／`sentryDsn` | Android CI／`local.properties` | 空值停用；不送 PII、request、breadcrumb、view hierarchy 或 screenshot |
| `TRANSFER_TOKEN_SECRET` | Worker secret | 至少 32 字元，用於產生可安全重試的短效換機 commit token |
| `AUTH_RATE_LIMITER` | Workers Rate Limiting binding | 每 IP＋auth route 每分鐘 20 次 |
| `API_RATE_LIMITER` | Workers Rate Limiting binding | 每 IP＋route group 每分鐘 120 次 |

Worker 不把 production 值寫入 `wrangler.jsonc`；請由 Cloudflare Dashboard 或 `wrangler secret put` 設定。`worker/.env.example` 只供型別產生與本機設定參考，不得放入真實憑證。部署會保留遠端變數，並開啟結構化 Workers Logs。

v2.0 不因帳號未活動而自動刪除資料，也不寄送未活動通知。雲端帳號與營運資料只會在使用者主動執行 Cloud Delete 或 Account Delete 後刪除；未活動帳號保存期限與事前通知留待 Post-MVP 再評估。Worker 不保存 Google email；維運 Audit 仍依既定 180 天 retention 清理。

## 告警

Sentry 以 `ops_alert` tag 分流：

- `HTTP_5XX`
- `SYNC_BLOCKED`
- `SYNC_ERROR`
- `AUTHORIZATION_FAILURE`
- `RATE_LIMIT`

Dashboard／告警查詢只能使用 request ID、狀態、錯誤碼與計數，不得加入 token、email、完整交易或商品 payload。Cron 每日刪除超過 180 天的 `audit_logs`。

## Restore Drill

本機可重複 gate：

```bash
cd worker
npm run restore-drill
```

正式演練：

1. 先停止對外寫入並記錄 `POS_DB`、`DELETION_DB` database ID 與 restore bookmark。
2. 只對 `POS_DB` 執行 Time Travel restore；不得 restore 或覆蓋 `DELETION_DB`。
3. 保持服務關閉，執行 deletion reconciliation。
4. 驗證 tombstone 之前的 Account、Cloud epoch、Session 與資料皆被移除或封鎖。
5. 驗證 `created_at_utc` 晚於 tombstone、epoch 較高的新世代帳號仍存在。
6. 完成抽樣、雙人覆核與 E2E 後才恢復服務。

`scripts/restore-drill.sh` 會建立兩個獨立資料庫、模擬 `POS_DB` 回復、保持 `DELETION_DB` 不動，並對舊 Account、舊 Cloud epoch、新世代帳號與 tombstone 數量做硬性 assert。

## Release Gate

CI 必須同時通過 Android unit／lint／instrumented、production-signed v1.5 upgrade、Worker typecheck／unit／integration、雙 D1 migration、Worker dry-run、Dashboard browser smoke 與 Restore Drill。另需人工確認：

- Android 與 Worker Sentry project 可收到去識別測試事件及上述 alerts。
- 正式雙 D1 Restore Drill 完成並留存紀錄。
- Cloudflare production deploy、Dashboard Google Login 與完整裝置 E2E 通過。

任一項缺少憑證或證據時均不可更新 `VERSION`、建立 `v2.0` tag 或發布 APK。
