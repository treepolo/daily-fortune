# 技術架構

## 1. 核心原則

1. **公共天命由真實天文資料決定**：正式公共十二星座結果不含亂數；Astronomy Engine → Astrology Engine versioned rules → scores/grades/explanations。
2. **公共世界線與私人改命分離**：公共結果是占星；私人「逆天改命」是 `SecureRandom` 古籤抽取。
3. **全部可追溯**：公共結果保存天文 snapshot、成立因素、每項分數與引擎版本；私人抽籤保存每次 draw event。
4. **正式中央權威**：Supabase 接入後，中央端每日生成一次並鎖定 12 筆公共天命；客戶端只讀與快取。
5. **離線優先顯示**：已下載公共天命與私人目前結果斷網仍可看；沒有今日快取時不得自行用種子補算正式結果。
6. **免費服務優先**：第一階段不得依賴必要固定月費服務。
7. **敏感金鑰不進 Repo**：服務角色金鑰、正式簽章金鑰禁止提交。

## 2. Android 現況

- Kotlin + Jetpack Compose。
- Astronomy Engine 2.1.19（MIT）直接在程式內計算星曆，不需 API key。
- `AstrologyEngine` 已用實際星曆建立公共十二星座原型。
- `Preferences DataStore` 暫存星座、今日私人改命、改命次數與彙總統計。
- 首頁單行跑馬燈；固定十二星座循環，自己的星座第一次出現前等待 2–11 星座。
- `DailyDestinyProvider` 將兩種來源統一成 `ResolvedDestiny`：
  - `PUBLIC_ASTROLOGY`
  - `PERSONAL_FORTUNE`

舊「日期＋星座固定種子」公共天命路徑已淘汰，不得重新引入。

## 3. Astrology Engine v1

完整規格：`docs/ASTROLOGY_ENGINE_V1.md`。

### 天文層

`AstronomyEphemeris`：

- `Asia/Taipei` 完整曆日。
- 15 分鐘取樣，97 個端點樣本。
- 10 個主要天體地心真黃道黃經。
- 日內星座占比。
- 六小時尺度順／逆行判斷。
- 換座。
- 合、六合、刑、拱、對分五主要相位的當日最近樣本與實際容許度。

### 規則層

`AstrologyEngine`：

- 太陽星座整宮制。
- 行星領域權重。
- 宮位領域權重。
- 行星落宮。
- 星座級行運對太陽星座關係。
- 天體彼此主要相位。
- 逆行／接近停滯。
- 傳統七曜尊貴倍率。
- 五領域分數 → 吉凶。
- 五領域算術平均 → 綜合。
- 詳情只能從實際 `AstrologyFactor` 生成。

### Audit

`AstrologyAudit` 保存：

- `engineVersion`
- 日期、星座
- `AstronomyDayData`
- 全部 `AstrologyFactor`
- 每個 factor 對五領域的實際貢獻
- 五領域分數
- 綜合分數

所以任何一個「今天為何是凶」都能往回查到實際天體位置與規則。

## 4. 私人逆天改命

私人改命仍使用公有領域古籤：

1. `SecureRandom` 抽綜合古籤。
2. 五個領域各自以 70% 沿用綜合籤來源、30% 再用 `SecureRandom` 從完整籤池抽來源。
3. 所有抽取採放回。
4. 完整 100 籤前，目前只以少量已校對籤驗證流程。

這個 70/30 是「私人古籤命運」模型，不參與公共占星。

## 5. 接 Supabase 前的 Room 層

DataStore 最終只留小型設定／同步游標。Room／SQLite 至少建立：

- `local_public_destinies`：中央公共天命快取，含 engine version 與必要 audit。
- `local_fortune_draws`：私人每次改命 event。
- `local_daily_fortunes`：每日星座、目前私人覆寫、改命次數。
- `local_bindings`：綁籤與 20 字留言。

每個私人待同步事件使用 UUID；後端以 `(user_id, local_id)` 去重。

## 6. Supabase 正式架構

使用 Supabase Authentication + PostgreSQL。

### Auth

- 首次使用匿名帳號。
- `auth.users.id` 為雲端使用者主體。
- `profiles` 保存目前星座、待生效星座、待生效日期。
- 日後綁 Google 等正式登入時保留同一資料主體。

### 每日公共生成

正式流程：

1. `Asia/Taipei` 新曆日。
2. 可信任中央函式使用與 `astrology-v1.0.0` 等價的規則取得當日真實星曆並計算 12 星座。
3. 以單一交易／具唯一鍵保護的流程寫入 12 筆。
4. 寫入後當日不可被一般客戶端修改。
5. 重試必須讀回既有資料，不得讓同一天重新生成不同公共天命。
6. Android 下載 12 筆並快取。

若中央端使用 TypeScript／Deno，必須以 golden fixtures 驗證與 Kotlin v1 規則等價；任何規則改動都升 engine version。

### 公共資料表

`daily_zodiac_destinies` 不再保存古籤號。它保存：

- engine version
- overall grade/score
- 五領域 scores/grades
- explanations
- astronomy snapshot
- astrology factors
- generated_at

### 私人資料

`fortune_draws` 只保存古籤私人改命事件；`daily_fortunes.current_personal_draw_id` 指向當日目前私人分支。公共天命永不被私人 draw 覆寫。

## 7. 同步與衝突

### 公共讀取

1. 先顯示本機今日快取。
2. 有網路時校對 Supabase `(date, 12 zodiac)`。
3. 伺服器資料是權威。
4. 無快取＋伺服器不可用：顯示暫時無法取得。

### 私人改命

正式版由後端交易／RPC 決定 draw index、結果與目前 draw，避免兩台裝置同時改命造成競爭。客戶端不得自行宣布一筆私人 draw 為伺服器權威結果。

## 8. Supabase Schema

尚未部署的初始 migration：`supabase/migrations/0001_initial.sql`。

主要表：

- `profiles`
- `daily_zodiac_destinies`
- `fortune_draws`
- `daily_fortunes`
- `fortune_bindings`

RLS：

- 公共天命：authenticated 可讀，客戶端無 write policy。
- profiles／draws／daily state／私人 bindings：限本人。

## 9. 完整 100 籤

古籤只服務私人改命。版本化靜態資料至少含：

- `corpus_id`
- `fortune_number`
- 原始級別與原文
- 正規化級別
- 五領域級別與自行撰寫解說
- 來源頁面與校對狀態

完成前自動檢查 1–100 完整、編號唯一、每支四句、五領域齊全、吉凶合法。

## 10. 安全與授權

- Astronomy Engine：MIT；第三方聲明見 `THIRD_PARTY_NOTICES.md`。
- JPL Horizons：只用於驗證，不是 runtime dependency。
- 公共運勢應在商店／適當位置明示娛樂用途，占星結論不宣稱科學預測效力。
- 正式 Release key 與 Supabase service role key 絕不進 Repo。

## 11. CI

GitHub Actions：JDK 17、Android SDK 36、Gradle wrapper，執行 unit tests + Debug APK build；成功後上傳 Debug APK artifact。Debug build 使用固定的非正式測試簽章，方便實機覆蓋更新。

## 12. 接下來順序

1. Astrology Engine v1 實機／CI 驗證完成。
2. Room／SQLite 正式事件與快取層。
3. 建立 Supabase Free 專案並套 migration。
4. 建立中央 Astrology Engine v1 等價生成器、每日 12 筆鎖定與 audit 保存。
5. 匿名登入、同步、星座隔日變更。
6. 完整 100 籤與私人改命內容。
7. 公共命盤、私人綁凶籤。
8. 帳號綁定／刪除、隱私、AAB 與 Play Internal Testing／正式上架。
