# Supabase 部署計畫

目前 Repo 內的 migrations、Edge Function 與測試已準備好，但尚未對任何正式 Supabase project 執行。本文件供真正建立 Free project 時一次完成部署。

## 1. 建立專案

建立 Supabase Free project。新專案使用 Dashboard / Connect 中的 **publishable key** 與 **secret key**；手機只能使用 publishable key，secret key 絕不能進 App、GitHub Repo 或公開 CI log。

Supabase Edge Functions 目前會自動提供 `SUPABASE_URL`、`SUPABASE_PUBLISHABLE_KEYS`、`SUPABASE_SECRET_KEYS` 等環境變數；程式亦保留 legacy `ANON/SERVICE_ROLE` fallback 方便相容，但新專案不以 legacy key 作主要方案。

## 2. 套資料庫 migrations

將專案 link 後套用：

```bash
supabase link --project-ref <PROJECT_REF>
supabase db push
```

目前包含：

- `0001_initial.sql`：profiles、astronomy snapshots、公共天命、私人平行世界線、每日狀態、綁定資料、RLS、immutable triggers 與可信任 commit RPC。
- `0002_concurrency_and_profile.sql`：午夜公共生成 advisory lock、私人 reroll 競態／冪等強化、來源年份限制、待生效星座套用 RPC。

兩份都尚未部署，所以現在仍屬第一版 schema。第一次正式 `db push` 後，不再回頭改寫已套用 migration；之後一律新增 migration。

## 3. 部署 Edge Function

Repo 的 `supabase/config.toml` 對 `daily-destiny` 設定 `verify_jwt = false`。原因是同一 endpoint 同時接受「有使用者 JWT 的 App 呼叫」與「沒有使用者 JWT 的 Cron 呼叫」。平台層不先擋掉 Cron，實際授權由 handler 自己完成：

- App action：handler 以 Supabase Auth 驗證 `Authorization: Bearer <user JWT>`。
- Cron action：必須通過 `x-cron-secret` 的 constant-time 比對。
- 只有 publishable key、沒有 user JWT 或正確 Cron secret，不能執行受保護 action。

部署：

```bash
supabase functions deploy daily-destiny
```

Edge Function 支援：

- `{"action":"ensure-public"}`：建立／讀回今日 12 星座公共天命。
- `{"action":"reroll","local_id":"<UUID>"}`：驗證使用者後，以安全亂數建立私人平行天象並原子寫入。

## 4. 設定 Cron secret

產生高熵隨機字串，例如使用密碼管理器或作業系統安全亂數。將相同值設定為 Edge Function secret：

```bash
supabase secrets set DAILY_DESTINY_CRON_SECRET='<RANDOM_SECRET>'
```

不要把實際值提交到 Repo。

## 5. 每日台北午夜排程

Supabase hosted platform 可用 Cron (`pg_cron`) + `pg_net` 呼叫 Edge Function。啟用相關模組後，將 project URL、publishable key、Cron secret 放進 Supabase Vault。

`Asia/Taipei` 固定為 UTC+8，因此台北 00:00 對應 UTC 16:00。排程：

```text
0 16 * * *
```

HTTP POST 目標：

```text
https://<PROJECT_REF>.supabase.co/functions/v1/daily-destiny
```

Headers：

```text
Content-Type: application/json
apikey: <publishable key from Vault>
x-cron-secret: <cron secret from Vault>
```

Body：

```json
{"action":"ensure-public"}
```

Cron 只負責主動生成；若它偶發失敗，第一個已登入使用者呼叫同一 `ensure-public` 仍可補生成。資料庫 RPC 以 date-scoped advisory transaction lock 序列化午夜競態；完整 12 筆已存在時只讀回，不會重算出另一套當日公共命運。

## 6. Android 接線

正式接後端時：

1. App 只內嵌 project URL + publishable key。
2. 首次啟動建立 Supabase anonymous user。
3. 啟動時呼叫 `apply_due_zodiac()`，處理已到期的隔日星座變更。
4. 啟動／換日抓 `daily_zodiac_destinies` + 必要 astronomy snapshot 並寫入 Room cache。
5. `逆天改命!!` 呼叫 `daily-destiny` 的 `reroll`，由伺服器決定來源天空與結果。
6. Android 不再把本機 `ParallelSkyGenerator` 產出的結果視為正式權威；本機實作保留作測試／離線驗算。
7. 恢復連線後以伺服器 draw index / UUID 資料校正本機。

## 7. 驗收

部署後至少驗證：

- 同一天任意兩個帳號讀到完全相同的 12 星座公共資料。
- 兩個請求同時在午夜生成公共天命仍只留下同一套 12 筆。
- 重複呼叫 `ensure-public` 不會改寫當天結果。
- 公共資料的 UPDATE / DELETE 被 immutable trigger 阻擋。
- 兩台裝置同時 reroll 不會產生重複 draw index。
- 同一 `local_id` 在並行／重試情境只得到同一私人 event。
- 私人來源日保存的整套天文資料可重新跑相同 engine version 還原同一分數。
- 未登入使用者不能讀私人資料；A 帳號不能讀 B 帳號 rerolls。
- Android publishable key 外洩不會取得 secret-level 權限。
- Cron secret 錯誤或缺少時 `ensure-public` 的 Cron 路徑被拒絕。

## 8. 免費額度策略

完整 astronomy snapshot 以來源日 + ephemeris version 去重；一天公共十二星座共用同一份 snapshot，私人 reroll 若碰到相同來源日也重用。這可大幅減少 PostgreSQL 儲存量。

目前只有文字／JSON 與少量列，不存圖片、影片或大型二進位資料；前期以 Supabase Free 為目標。
