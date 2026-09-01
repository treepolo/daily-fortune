# 技術架構

## 1. 原則

1. **離線優先**：今日是否已抽、目前有效籤與必要統計不能依賴網路才能成立。
2. **事件可追溯**：未來完整版本保存每次實際抽籤事件；逆天改命只改變「目前有效籤」，不刪歷史事件。
3. **帳號主體先定義**：雲端資料從第一版 schema 就以 `user_id` 分區，避免日後加入跨裝置時重構所有資料。
4. **免費服務優先**：第一階段不得依賴必要固定月費服務。
5. **不把金鑰寫入 Repo**：Supabase 公開 URL／匿名金鑰仍透過本機設定或 CI secret 注入；服務角色金鑰絕不進 App。

## 2. Android

### 目前 0.1.0

- Kotlin + Jetpack Compose。
- `Preferences DataStore` 保存今日日期、目前籤、當日改命次數與彙總統計。
- 這一版用於先驗證最核心產品規則與 UI 流程。

### 接 Supabase 前

加入本機事件資料庫（預定 Room／SQLite）：

- `local_fortune_draws`：每次抽籤一筆，不因逆天改命刪除。
- `local_daily_fortunes`：每個使用日目前有效 draw id 與改命次數。
- `local_bindings`：未來綁籤事件與 20 字留言。
- 每筆待同步事件使用本機 UUID，雲端以 `(user_id, local_id)` 去重，讓離線重送具有冪等性。

DataStore 保留給小型設定與同步游標，不把完整長期歷史塞進 Preferences。

## 3. Supabase

使用 Supabase Authentication + PostgreSQL。

預定登入流程：

1. 首次使用直接建立匿名 Supabase 使用者。
2. 雲端資料全部使用 `auth.users.id` 作為 `user_id`。
3. 日後綁定 Google 或其他正式登入方式時，保留同一使用者資料主體。
4. 換裝置後登入，下載歷史與目前狀態到本機。

初始資料庫 migration 位於 `supabase/migrations/0001_initial.sql`。

### 雲端資料表

- `profiles`
- `fortune_draws`
- `daily_fortunes`
- `fortune_bindings`

統計以 `fortune_draws` 與 `daily_fortunes` 為來源重新計算或快取，避免把多份計數器當成唯一真相。

## 4. 同步模型

### 寫入

正常抽籤／逆天改命：

1. 本機交易先建立 draw event。
2. 本機更新 daily current draw。
3. UI 立即顯示。
4. 有網路時將未同步事件送到 Supabase。
5. Supabase 依 `(user_id, local_id)` 去重。

### 衝突

同一帳號在兩台裝置同日同時操作屬於真實可能情境。雲端接入時需明確制定單日權威序列，預定以伺服器交易／函式處理，不以單純「最後寫入者勝出」草率覆蓋抽籤歷史。

## 5. 籤文資料

籤文是版本化的靜態資料，與使用者資料分離：

- `corpus_id`: 例如 `guandi-100-v1`
- `fortune_number`
- 原始級別
- 原文
- 正規化級別
- 五領域級別與自行撰寫解說
- 來源頁面與校對狀態

完整 100 籤匯入前，先建立自動檢查：編號唯一、1–100 完整、每支四句、五個領域皆存在、吉凶值合法。

## 6. 安全與資料權限

Supabase 所有使用者資料表啟用 Row Level Security：

- 使用者只能讀寫自己的抽籤、每日狀態與私人綁籤。
- 第一版不允許任意讀取其他人的 20 字留言。
- 未來若開公共籤架，新增專用公開讀取介面與內容管理流程，不直接把整張私人資料表開放匿名讀取。

## 7. CI

GitHub Actions：

- 使用 JDK 17、Gradle 9.5.0、Android SDK 36。
- 執行單元測試與 Debug APK build。
- 一次性 bootstrap workflow 會產生官方 Gradle Wrapper 並提交回 Repo。

## 8. 後續順序

1. 讓 0.1.0 本機垂直切片穩定建置與實機測試。
2. 匯入、校對完整 100 籤資料與解說。
3. 將歷史事件改存 Room／SQLite。
4. 建立 Supabase 專案並套用 migration。
5. 接匿名登入與離線同步。
6. 接正式帳號綁定、資料復原與帳號刪除。
7. 再做綁凶籤私人版本。
8. 公開留言、廣告等功能另案處理。
