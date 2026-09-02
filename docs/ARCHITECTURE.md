# 技術架構

## 1. 核心原則

1. **同一套占星引擎，兩條世界線**：公共天命輸入今天真實天空；私人改命輸入安全亂數選中的物理可成立平行天空。兩者都走 Astronomy Engine → Astrology Engine v1 → scores / grades / explanations。
2. **亂數只選世界線**：私人改命不得獨立亂數各行星位置；正式亂數只用來選擇完整星曆來源。
3. **全部可追溯**：公共與私人結果都保存引擎版本、天文 snapshot、成立 factors、每因素五領域貢獻與最終分數。
4. **正式中央權威**：Supabase 接入後，中央端每日生成並鎖定 12 筆公共天命；私人改命也由中央 Edge Function 決定並寫入。
5. **客戶端不冒充權威**：Android 本機運算目前用於開發／實機驗證；正式接後端後，伺服器資料為權威。
6. **免費服務優先**：第一階段不得依賴必要固定月費服務。
7. **敏感金鑰不進 Repo**：Supabase secret key、Cron secret、正式簽章金鑰禁止提交。

古籤、籤詩、`FortuneCatalog` 與私人古籤資料模型已淘汰。

## 2. Android 現況

- Kotlin + Jetpack Compose。
- Astronomy Engine 2.1.19（MIT）直接計算星曆，不需 API key。
- `AstronomyEphemeris`：分析 `Asia/Taipei` 完整曆日。
- `AstrologyEngine`：固定、版本化、可 audit 的太陽星座占星規則。
- `ParallelSkyGenerator`：Java `SecureRandom` 選 1900–2100 的另一年份，再找同季節太陽黃經最接近的完整星曆日。
- `AstrologyComparison`：依兩邊 audit 計算前後差異、增加／移除因素與改善／惡化狀態。
- `Preferences DataStore`：目前保存星座、今日私人平行來源日、改命次數與彙總統計。
- 首頁單行跑馬燈；固定十二星座循環，自己的星座第一次出現前等待 2–11 星座。
- `DailyDestinyProvider` 統一回傳：`PUBLIC_ASTROLOGY` / `PERSONAL_ASTROLOGY`。
- App 的換日已固定使用 `Asia/Taipei`，不再依手機所在時區決定公共日期。

## 3. Astrology Engine v1

完整規格：`docs/ASTROLOGY_ENGINE_V1.md`。

天文層使用 15 分鐘取樣，共 97 個端點；分析太陽、月亮、水星、金星、火星、木星、土星、天王星、海王星、冥王星的地心真黃道黃經、日內星座占比、順逆行、換座與五主要相位。

規則層包含太陽星座整宮制、行星領域權重、宮位權重、行星落宮、星座級行運對太陽星座關係、天體彼此主要相位、逆行／停滯、傳統七曜尊貴倍率、五領域分數、綜合分數與由 factor 生成的說明。

`AstrologyAudit` 保存日期、星座、天文資料、全部 factors、每 factor 五領域貢獻、五領域分數與綜合分數。任何「今天為什麼凶」都能回查實際計算。

## 4. 私人平行天象

正式 v1 流程：

1. 安全亂數選 1900–2100 的另一年份。
2. 在該年份與原日期同月同日 ±6 天內找正午太陽黃經最接近的日期。
3. 採用來源日完整 `Asia/Taipei` 00:00–24:00 星曆。
4. 同一套 Astrology Engine 針對使用者太陽星座重算。
5. 保存 `originalDate`、`sourceDate`、原／新太陽黃經、黃經差、完整 astronomy snapshot、factors、scores、grades。
6. 使用 `AstrologyComparison` 從兩套 audit 算前後差異。

這種抽法保持整個太陽系位置彼此物理一致，也近似維持今天的季節背景。來源日可能是歷史日，也可能是星曆可預測的未來日；產品應稱「物理可成立／完整星曆天空」，避免暗示未來資料已被觀測。

改命不保證改善；同一來源日可以再次被抽到。

## 5. 接 Supabase 前的 Room 層

DataStore 最終只留小型設定與同步游標。Room／SQLite 至少建立：

- `local_astronomy_snapshots`：已下載的完整天空資料。
- `local_public_destinies`：中央公共天命快取與 factors。
- `local_personal_rerolls`：每次私人平行天象改命事件。
- `local_daily_fortunes`：每日星座、目前私人覆寫、改命次數。
- `local_destiny_bindings`：綁凶運與 20 字留言。

私人待同步請求使用 UUID，後端以 `(user_id, local_id)` 去重。

## 6. Supabase 正式架構

Repo 已包含尚未部署的正式後端程式：

- `supabase/migrations/0001_initial.sql`
- `supabase/functions/_shared/astrology_v1.ts`
- `supabase/functions/daily-destiny/index.ts`
- `supabase/functions/_shared/astrology_v1_test.ts`

### Auth

首次使用匿名帳號；`auth.users.id` 為雲端使用者主體；`profiles` 保存目前星座與待生效星座。日後綁 Google 等登入時沿用同一資料主體。

### 公共每日生成

1. `Asia/Taipei` 新曆日。
2. Edge Function 以 Astronomy Engine 2.1.19 + `astrology-v1.0.0` 計算當日天空與 12 星座。
3. `commit_daily_zodiac_destinies` 在單一 PostgreSQL 交易中保存 astronomy snapshot + 12 筆公共結果。
4. `(fortune_date, zodiac_sign)` 唯一鍵保證一日一星座一筆。
5. astronomy snapshot 與公共結果有 immutable trigger；一般客戶端沒有寫入 policy。
6. 若完整 12 筆已存在，重試直接讀回；若只存在部分筆數則報錯，避免靜默修補出混合版本的一天。
7. Cron 可在台北午夜主動呼叫；第一個使用者也可觸發 `ensure-public` 作補救。

### 私人改命

正式版呼叫同一 Edge Function 的 `reroll` action：驗證使用者 → 確認今日公共資料 → 安全亂數選平行來源 → 同一引擎重算 → `commit_personal_reroll` 原子寫入。

`commit_personal_reroll` 以 UUID `local_id` 做冪等、鎖住當日使用者列後分配連續 `draw_index`，再更新 `current_personal_reroll_id`，避免兩台裝置同時改命互相覆蓋。

## 7. Supabase Schema

### `astronomy_snapshots`

以 `(sky_date, ephemeris_version)` 為主鍵，每個完整天空只存一次，避免 12 星座各自複製 97×10 天體樣本，降低免費資料庫用量。

### `daily_zodiac_destinies`

公共天命：engine / ephemeris version、overall、五領域、explanations、factors，引用當日 astronomy snapshot。

### `personal_destiny_rerolls`

私人世界線：使用者、日期、星座、draw index、平行來源日、太陽黃經差、結果與 factors，引用來源 astronomy snapshot。

### `daily_fortunes`

保存使用者每日目前私人 worldline 與改命次數。

### `destiny_bindings`

預留私人 0–20 字「綁凶運」資料。公開 UGC 尚未開啟。

## 8. RLS 與金鑰

- 公共 astronomy snapshots / daily zodiac destinies：authenticated 可讀，客戶端不可寫。
- personal rerolls / daily state：本人可讀，正式寫入由可信任 Edge Function + secret key 完成。
- bindings：本人 private CRUD。
- 手機只放 Supabase publishable key；secret key 永遠只在受控後端。
- Edge Function 優先讀 `SUPABASE_PUBLISHABLE_KEYS` / `SUPABASE_SECRET_KEYS`，並保留 legacy env fallback 方便相容；正式新專案應使用 publishable / secret keys。

## 9. 離線與同步

正式 Android：先顯示本機快取；有網路時向 Supabase 校對。沒有今日快取且後端不可用，顯示暫時無法取得。不得在正式路徑用本機日期種子、臨時亂數或本機平行天空冒充伺服器權威。

## 10. 測試與 parity

GitHub Actions 目前執行：

- Deno `check` Edge Function。
- Deno Astrology Engine 測試。
- Android / Kotlin Astrology Engine 單元測試。
- Debug APK build。

中央 TypeScript 與 Android Kotlin 必須維持相同 `astrology-v1.0.0` 規則；規則改動必須升 engine version。跨語言 golden/parity fixture 也納入 CI，防止兩套實作悄悄漂移。

## 11. 安全、授權與產品定位

- Astronomy Engine：MIT；第三方聲明見 `THIRD_PARTY_NOTICES.md`。
- JPL Horizons：只作驗證，不是 runtime dependency。
- 天體位置是天文計算；從天象推導吉凶屬占星規則，App 不宣稱具有科學預測效力。
- 任何正式 secret / release key 不進 Repo。

## 12. 接下來順序

1. 完成本輪 Android＋Supabase 後端 CI／parity 驗證。
2. Room／SQLite 正式事件與快取層。
3. 使用者建立 Supabase Free project，套 migration、部署 Edge Function、設定 Cron。
4. Android 接匿名 Auth、公共讀取、中央 reroll 與同步，停止以本機計算作正式權威。
5. 星座隔日變更 UI、公共命盤、歷史／錯誤狀態、綁凶運。
6. 帳號綁定／刪除、隱私、AAB 與 Play Internal Testing／正式上架。
