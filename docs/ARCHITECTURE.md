# 今日運勢：技術架構

## 1. 架構目標

目前架構只服務三件事：

1. Android 端可靠地完成每日抽籤與「逆天改命!!」。
2. 透過 Remote Config 在不更新 APK 的情況下改變既有實驗參數。
3. 匿名記錄使用行為，供獨立研究／分析系統使用。

星座、天文、占星、公共命運與平行天空不再是產品架構的一部分。

## 2. 系統邊界

```text
Android App
  ├─ Fortune Engine
  ├─ Room local state/history
  ├─ Experiment Config client/cache
  └─ Analytics event queue/uploader
          │
          ▼
Remote backend
  ├─ Resolved Remote Config API
  ├─ A/B/n stable assignment
  └─ Analytics event ingestion
          │
          ▼
Database / research layer
  ├─ experiment definitions + variants
  ├─ raw anonymous events
  └─ SQL / Python / Notebook / Dashboard
```

Android App 不包含研究 Dashboard 或群體統計分析。

## 3. Fortune Engine

### 3.1 Input

Fortune Engine 接收一份已解析的 `ResolvedExperimentConfig`：

- `configId`
- `assignments[]`
- initial distribution
- reroll distribution
- sampling mode
- correlation profile / matrix
- overall grade rule
- optional visual variant IDs

### 3.2 Output

每次抽籤產生：

- 五細項 1～7 分數
- raw arithmetic average
- overall 1～7 grade
- 實際使用的 config/experiment metadata

### 3.3 Sampling strategy

支援兩種基礎模式：

1. `INDEPENDENT`：五細項獨立抽樣。
2. `GAUSSIAN_COPULA`：利用五維相關矩陣產生具相關性的 uniform samples，再依 cumulative distribution 映射到 1～7。

目前預設使用 `INDEPENDENT`。相關模式讓未來可以直接以 Remote Config 測「無相關、整體正相關、特定領域相關」等 treatment，而無需發布新版 App。

相關矩陣必須驗證：

- 5×5
- 對角線為 1
- 對稱
- 元素位於 [-1, 1]
- 可進行數值穩定的 Cholesky decomposition

無效的遠端設定不可使 App 無法抽籤；必須退回最後一份有效 config，若仍不存在則使用內建 default config。

## 4. Overall Grade Rule

總體分數永遠先計算五項算術平均，再由可配置規則映射到 1～7。

支援：

- `FLOOR`
- `CEIL`
- `ROUND`
- `PIECEWISE`

`PIECEWISE` 由多個互斥、完整覆蓋 [1,7] 的 segment 構成，每個 segment 指定 min inclusive、max exclusive（最後一段可包含 7）及 rounding method。

任何計算結果最後 clamp 到 1～7。

## 5. Local persistence

新版 Room 使用獨立資料庫檔案 `daily-fortune-v2.db`，只保存三類資料：

1. `fortune_draws_v2`：append-only 抽籤歷史。
2. `daily_fortune_state_v2`：每個 local date 目前顯示的 draw pointer。
3. `analytics_events_v2`：尚未成功送達研究後端的匿名事件 queue。

舊版占星資料庫 `daily-fortune.db` 原封不動保留，但新版 runtime 完全不查詢、不寫入或依賴它。兩種產品資料語意不同，因此本輪不把舊占星結果轉換成新抽籤結果，也不冒險做破壞性 schema migration。

詳見 `docs/LOCAL_DATA.md`。

## 6. Device date and timezone

每日日期使用裝置當下 `ZoneId.systemDefault()`。

每個 analytics event 另外保存：

- UTC epoch timestamp
- timezone ID
- local date/time

因此旅行、手動改時區或 DST 不會讓研究端無法重建事件當下的本地時間語境。

## 7. Remote Config

### 7.1 Client behavior

App 啟動／回到前景時：

1. 先讀最後一份有效 cached config；沒有則使用 embedded default。
2. 向 backend 取得 resolved config；網路工作在背景執行緒，不阻塞抽籤核心。
3. 驗證 schema、distribution、rounding rule、correlation matrix。
4. 驗證成功才覆寫 cache。
5. 本次 draw 使用當下已解析且有效的 config，並把 config ID 寫進 draw/event。

網路失敗不得阻止抽籤。

### 7.2 Server response

後端不下發可執行程式碼，只下發資料化設定，例如：

```json
{
  "config_id": "resolved-2026-09-03-abc",
  "assignments": [
    {"experiment_id": "fortune_probability_v3", "variant_id": "C"}
  ],
  "fortune": {
    "initial_distribution": {
      "id": "uniform_v1",
      "probabilities": [0.142857,0.142857,0.142857,0.142857,0.142857,0.142857,0.142857]
    },
    "reroll_distribution": {
      "id": "uniform_v1",
      "probabilities": [0.142857,0.142857,0.142857,0.142857,0.142857,0.142857,0.142857]
    },
    "sampling": {
      "mode": "INDEPENDENT",
      "profile_id": "independent_v1"
    },
    "overall_rule": {
      "id": "floor_v1",
      "type": "FLOOR"
    }
  },
  "visual": {
    "static_variant_id": "baseline",
    "reveal_variant_id": "none"
  }
}
```

## 8. A/B/n assignment

後端負責實驗分組，App 不自行決定 variant。

每個 experiment 包含：

- experiment ID/version
- active state
- allocation/rollout
- stable salt
- variants[]
- 每個 variant 的 weight
- 每個 variant 的 treatment JSON
- priority / conflict resolution metadata

穩定分組使用 `installation_id + experiment_id + salt` 的 deterministic hash，映射到 [0,1)，再依 variant cumulative weights 分配。

同一 installation 在 experiment/salt 不變時必須穩定落在同一 variant。

後端可將多個不衝突實驗的 treatment 疊加，最後回傳一份 resolved config 與所有 contributing assignments；若設定衝突，以後端明確 priority 規則處理，不讓 App 自行猜測 precedence。

## 9. Analytics

### 9.1 Identity

App 首次安裝後建立隨機 UUID `installation_id` 並保存在本機。每次新的 foreground session 建立新的 `session_id`。

### 9.2 Event queue

事件先 append 到 Room，再批次上傳。只有後端明確成功接收後才從 queue 移除。

因此短暫離線、程序被殺或網路錯誤不應造成已落盤事件直接遺失。

### 9.3 Events

基礎事件：

- `app_open`
- `session_start`
- `session_end`
- `experiment_exposure`
- `initial_draw`
- `reroll`

目前 fortune treatment 的實際 exposure 可由 `initial_draw` / `reroll` 事件攜帶的 assignments 判定；未來視覺 treatment 在真正呈現時再新增顯式 `experiment_exposure` 與視覺事件。

### 9.4 Draw event payload

至少記錄：

- fortune date
- draw index
- draw type
- wealth/love/workStudy/relationships/health scores
- raw average
- overall score
- distribution ID
- sampling profile ID
- overall rule ID
- config ID
- assignments

## 10. Backend

現有 Supabase 占星 backend 從未正式部署，已從產品主線移除，不需要維持 runtime 相容。

新的 backend 只包含：

- base app config
- experiments
- experiment variants
- analytics events
- resolved config Edge Function
- analytics event ingestion Edge Function

App 不直接取得 service-role 權限。匿名 client 只呼叫受控 Edge Function；資料表保持 server-side 管理。

## 11. Build / CI

保留：

- Android application ID
- Compose
- Room + bundled SQLite
- 穩定 sideload signing config
- GitHub Actions run number versionCode
- Android unit tests
- release APK artifact

移除：

- Astronomy Engine dependency
- Deno/Kotlin astrology parity
- astrology calibration jobs
- astrology weight-search jobs
- astrology feature cache/optimization tools

Supabase 新 Edge Functions 在 CI 進行 Deno check/test，不再存在 astrology parity step。

## 12. Failure policy

產品核心必須 offline-first：

- Remote Config 失敗：使用 cached/default config。
- Analytics upload 失敗：事件留在 queue，之後重試。
- App 重開：從 Room 復原當地日期最後一籤。
- 時區跨日：以當下裝置 local date 決定是否顯示舊日結果。

研究功能不得使「今天能不能抽籤」依賴網路。