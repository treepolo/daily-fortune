# 技術架構

## 1. 核心原則

1. **公共天命由真實天文資料決定**：正式公共十二星座結果不含亂數；Astronomy Engine → Astrology Engine versioned rules → scores/grades/explanations。
2. **公共世界線與私人改命分離**：公共結果是今日真實天空；私人「逆天改命」是安全亂數抽取另一個物理上真實存在的完整天空，再用完全相同的占星規則重算。
3. **全部可追溯**：公共與私人結果都保存天文 snapshot、成立因素、每項分數與引擎版本。
4. **Room 是 Android 本機命運資料的 source of truth**：運勢快取、天文 snapshot、factor audit、每日狀態、改命事件、歷史樣本與 bindings 都進 SQLite；DataStore 只留小型使用者設定。
5. **正式中央權威**：Supabase 接入後，中央端每日生成一次並鎖定 12 筆公共天命；私人改命亦由中央決定。客戶端只讀、快取與保存待同步事件。
6. **沒有正式 fallback 假命運**：有中央快取就可離線讀；無今日快取且中央不可用時顯示 unavailable，不以日期 seed 或本機重算冒充中央資料。
7. **免費服務優先**，敏感金鑰不進 Repo。

## 2. Android 資料層

- Kotlin + Jetpack Compose。
- Astronomy Engine 2.1.19（MIT）。
- Room 3.0.2 + bundled SQLite driver。
- `UserSettingsStore`：保留 `selected_zodiac` 等小型偏好。
- `DailyFortuneDatabase`：正式本機資料庫。
- `LocalFortuneRepository`：UI 唯一面向的資料協調層；UI 不直接知道資料來自 embedded debug engine、Room cache 或未來 Supabase。
- `DestinyAuthority`：抽象中央／開發資料來源。

完整 Room schema 與離線規則見 `docs/LOCAL_DATA.md`。

### Room tables

- `local_astronomy_samples`
- `local_destinies`
- `local_astrology_factors`
- `local_daily_fortunes`
- `local_reroll_events`
- `local_fate_sample_events`
- `local_bindings`

統計由 immutable event history 重新聚合，不再把可失真的 aggregate counters 存在 Preferences。

## 3. Astrology Engine v1

完整規格：`docs/ASTROLOGY_ENGINE_V1.md`。

天文層以 `Asia/Taipei` 完整曆日、15 分鐘 97 個端點樣本、10 個主要天體、順逆行、換座與五大相位建立 `AstronomyDayData`。`AstronomyEphemeris.rebuild()` 可從 Room 保存的 97-sample snapshot 重建衍生天文特徵，因此離線 audit 不需要重新向外部服務抓資料。

規則層採太陽星座整宮制、行星／宮位領域權重、主要相位、逆行、七曜尊貴與固定分數門檻。任何詳情都只能從實際 `AstrologyFactor` 生成。

## 4. 私人逆天改命

`ParallelSkyGenerator` 不會獨立亂數改每顆行星。它安全亂數選另一年份，再尋找正午太陽黃經與原日期最接近的真實日期，整包使用該日的真實 24 小時天空。

Room 每次保存：

- 新私人 destiny 與完整 factor audit；
- 新 source-day astronomy snapshot；
- immutable reroll event；
- `beforeDestinyId` / `afterDestinyId`；
- draw index、sync state 與時間。

因此第二次以後的改命比較永遠是「按下按鈕前的目前世界線 → 新世界線」，不是固定拿最初公共天命比較。

## 5. Debug 與正式 authority

Debug APK 在 Supabase 尚未部署前使用 `EmbeddedDevelopmentDestinyAuthority`，讓實機可以完整測試真實星曆、Room 快取、歷史與改命。這些 rows 會標成 development authority。

Release build 不會使用 embedded fallback。未接 Supabase 前，沒有 cache 時會直接 unavailable。接後端時加入 `SupabaseDestinyAuthority` 即可，不需重寫 UI 或 Room schema。

## 6. Supabase 正式架構

Repo 已包含：

- `supabase/migrations/0001_initial.sql`
- `supabase/functions/_shared/astrology_v1.ts`
- `supabase/functions/daily-destiny/index.ts`
- `supabase/DEPLOYMENT.md`

正式流程仍為中央 `Asia/Taipei` 每日生成／鎖定十二星座；Android 下載後寫入 Room。私人 reroll 由後端原子建立並回傳，Room 保存 server ID／sync state。中央資料永遠優先於 development/local provisional rows。

## 7. DataStore → Room rollout

舊 `fortune_state` DataStore 的 `selected_zodiac` 保留。舊的 today state、aggregate stats、私人 sky pointer 與更早古籤 prototype keys 只屬開發資料，而且無法無損還原 immutable event history，因此新 Room 版本第一次啟動時清掉一次，不偽造歷史事件。

之後：

- DataStore：設定。
- Room：命運與歷史。
- Supabase：正式中央權威與跨裝置同步。

## 8. 安全與授權

- Astronomy Engine：MIT；第三方聲明見 `THIRD_PARTY_NOTICES.md`。
- JPL Horizons：只用於驗證，不是 runtime dependency。
- 占星結論定位為娛樂用途，不宣稱科學預測效力。
- Release key 與 Supabase secret/service-role key 絕不進 Repo。

## 9. CI

GitHub Actions 驗證：

- Deno/Supabase Astrology Engine；
- Kotlin unit tests；
- Room/KSP schema 與 DAO SQL 編譯；
- Kotlin ↔ Deno Astrology Engine parity；
- Debug APK。

## 10. 接下來

1. Room/Repository 實機驗收。
2. 建立 Supabase Free project，套 migration、部署 Edge Function/Cron。
3. Android 加 `SupabaseDestinyAuthority`、anonymous auth、download/sync/reconcile。
4. 星座隔日變更 UI、設定頁。
5. 開機「真人看盤」敘事演出與跑馬燈呈現調整。
6. 帳號綁定／刪除、隱私、AAB 與 Play 測試／上架。
