# Google Play Data safety 填表草稿（v0.6.9）

本文件是依目前程式碼、Supabase telemetry 與 Google Mobile Ads SDK 25.4.0 的資料揭露整理的上架填表草稿。正式送出 Play Console 前仍應依當時 Console 問題文字逐項核對。

## 總體

- App 是否收集或分享使用者資料：**是**。
- 所有透過自有 Supabase 後端傳輸的資料：HTTPS/TLS。
- Google Mobile Ads SDK 官方表示其自動處理的資料亦使用 TLS 傳輸。
- App 不要求建立使用者帳號。
- App 目前沒有帳號刪除需求；若 Play Console 問的是「是否提供資料刪除請求」，在沒有新增專用流程前請選 **否**，不要誤填。

## 我們自己的 Supabase telemetry

### Device or other IDs / 裝置或其他 ID
- 資料：App 隨機產生的 installation ID、session ID。
- 收集：是。
- 分享：否（Supabase 作為服務提供者／資料處理基礎設施，不是拿來販售或廣告分享）。
- 用途：App functionality、Analytics、Developer communications/Research 若 Console 有相應選項時以「分析／功能／開發者用途」為主。
- 是否必要：目前 telemetry 為產品研究架構的一部分；若 Console 詢問「optional」，依實際使用者是否能關閉而定。目前沒有使用者開關，因此填 required。

### App activity / App 活動
- 資料：app_open、session_start/end、initial_draw、reroll、實驗曝光、廣告流程事件，以及隨機運勢結果與抽籤序號。
- 收集：是。
- 分享：否（同上）。
- 用途：App functionality、Analytics。

## Google Mobile Ads SDK 25.4.0

官方資料揭露指出，當 Mobile Ads SDK 實際運作時，預設可能收集／分享：

### Approximate location / 概略位置
- 來源：IP 位址可用於估算概略位置。
- 收集：是。
- 分享：是（Google 廣告服務）。
- 用途：Advertising or marketing、Analytics、Fraud prevention/security/compliance。

### App activity / App 活動
- 來源：App 啟動、點擊、影片觀看與其他互動。
- 收集：是。
- 分享：是。
- 用途：Advertising or marketing、Analytics、Fraud prevention/security/compliance。

### App info and performance / App 資訊與效能
- 類型：Diagnostics。
- 來源：SDK/App 啟動時間、hang rate、energy usage 等診斷資訊。
- 收集：是。
- 分享：是。
- 用途：Advertising or marketing、Analytics、Fraud prevention/security/compliance。

### Device or other IDs / 裝置或其他 ID
- 來源：Android advertising ID、App Set ID，以及可能的其他裝置／帳戶相關識別碼。
- 收集：是。
- 分享：是。
- 用途：Advertising or marketing、Analytics、Fraud prevention/security/compliance。

## User Messaging Platform（UMP）

UMP 用來在依法需要時取得／管理廣告相關隱私選擇。v0.6.9 的程式邏輯只有在 `ads.enabled=true` 時才主動進行 UMP/AdMob 準備流程；正式送審時仍以 Google Play SDK Index 與當時 UMP 官方揭露為準。

## v0.6.9 特別注意

- production Remote Config 預設 `ads.enabled=false`，但 AAB 已包含 Mobile Ads/UMP SDK，且未來可遠端啟用，因此 Data safety 應以「功能可啟用後的完整資料處理能力」保守申報，而不是因初始開關關閉就填成沒有廣告資料。
- Debug/QA build 強制使用 Google 官方測試 rewarded ad unit；production release 才可能使用正式 ad unit。
- 不要宣稱「完全匿名」；我們自己的 telemetry 是**假名化**，因為 installation ID 仍可把多筆事件串在一起。

## 正式填表前重新核對的官方頁

- Google Mobile Ads SDK Android Play data disclosure：
  https://developers.google.com/admob/android/privacy/play-data-disclosure
- Google Play Data safety：
  https://support.google.com/googleplay/android-developer/answer/10787469
