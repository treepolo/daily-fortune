# 本機資料模型 v2

## 1. 目標

本機資料只負責：

- 保存每日最後一籤
- 保存每次實際抽籤歷史
- 離線時保存待上傳研究事件
- 保存匿名 installation ID 與最後一份有效 Remote Config cache

前端不顯示個人統計，也不依賴歷史統計表。

## 2. `fortune_draws_v2`

每次初抽與每次「逆天改命!!」都新增一列，不覆寫舊列。

欄位：

- `id`: UUID primary key
- `fortuneDate`: 裝置當地日期 `YYYY-MM-DD`
- `drawIndex`: 當日從 1 開始遞增；1 為初抽
- `drawType`: `INITIAL` / `REROLL`
- `wealthScore`: 1～7
- `loveScore`: 1～7
- `workStudyScore`: 1～7
- `relationshipsScore`: 1～7
- `healthScore`: 1～7
- `rawAverage`: 五項算術平均
- `overallScore`: 1～7
- `configId`: 實際使用的 resolved config
- `assignmentsJson`: experiment/variant assignments snapshot
- `distributionId`: 本次 draw 使用的 distribution
- `samplingProfileId`: 本次 sampling/correlation profile
- `overallRuleId`: 本次 overall mapping rule
- `createdAtEpochMillis`: UTC epoch millis

唯一索引：`fortuneDate + drawIndex`。

## 3. `daily_fortune_state_v2`

每個 local date 一列，作為目前 UI 指標。

欄位：

- `fortuneDate`: primary key
- `currentDrawId`: 指向 `fortune_draws_v2.id`
- `drawCount`: 當日總抽籤次數
- `firstDrawAtEpochMillis`
- `updatedAtEpochMillis`

App 重開後只需讀當日 state，再讀 current draw。

## 4. `analytics_events_v2`

離線優先的研究事件 queue。

欄位：

- `eventId`: UUID primary key
- `installationId`
- `sessionId`
- `eventName`
- `eventEpochMillis`
- `localDateTime`
- `timezoneId`
- `appVersion`
- `configId`
- `assignmentsJson`
- `payloadJson`
- `uploadState`: `PENDING` / `UPLOADING` / `FAILED`
- `attemptCount`

成功送達後可以刪除 queue row；研究後端保存完整 canonical event。

## 5. Shared preferences

少量不適合 Room 的安裝層狀態可存在 private SharedPreferences：

- `installation_id`
- `cached_remote_config_json`
- `cached_remote_config_saved_at`

這些不是使用者可見設定。

## 6. v1 → v2 migration

舊 v1 Room schema 包含 astronomy、destiny、astrology factors、zodiac daily state、reroll、sample、binding 等表。

v2 migration 採取保守策略：

- 建立新的 `*_v2` 表。
- Runtime 完全停止查詢／寫入舊 astrology tables。
- 不嘗試把舊占星結果轉換成新抽籤結果，因為兩種產品語意不同。
- 不在同一次 migration 中破壞性刪除舊表，避免升級時因意外 schema 差異造成資料庫開啟失敗。
- 未來確認 v2 穩定後，可另做 migration 清理 legacy tables。

因此舊安裝更新後，今天的新產品狀態從第一次新制抽籤開始建立。

## 7. 一致性規則

一次 draw 寫入應在單一 Room transaction 中完成：

1. insert `fortune_draws_v2`
2. upsert `daily_fortune_state_v2`
3. insert 對應 `initial_draw` / `reroll` analytics event

這可避免 UI 已顯示新籤但事件或每日 pointer 沒有落盤。

研究上傳與 draw transaction 分離；網路失敗不能回滾使用者已完成的抽籤。