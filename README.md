# 今日運勢 / Daily Fortune

一個 Android 每日抽籤 App。每天可正常抽取一次今日運勢；結果會固定保存到換日。若對命運不滿，使用者可以按下「逆天改命!!」作廢目前結果並從完整籤池重新抽取，而且抽過放回。

## 已定案的核心

- 每個曆日第一次可正常抽籤一次，關閉 App、重新開機後仍維持同一結果。
- 「逆天改命!!」可無限重抽，可能再次抽到同一支籤。
- 綜合運勢之外，固定顯示財運、戀愛、工作／學業、人際、健康。
- 籤詩採公有領域古籍；現代白話解說自行撰寫，不複製現代命理網站。
- 統計總逆天改命次數、平均每個使用日改命次數、單日最高、大吉次數／比例、非凶比例、大凶次數／比例。
- 被「逆天改命」作廢的籤仍計入歷史抽籤統計。
- 架構從第一天預留 Supabase 匿名帳號、雲端同步、跨裝置，以及未來「綁凶籤」功能。
- 未來綁凶籤可附 0–20 字留言；第一版不公開陌生人的留言內容。
- 未來廣告規則已記錄但暫不實作：一般「逆天改命」需看完整廣告；當前若為大吉則免費改命，並立即捨棄該大吉結果重新抽取。

## 目前開發階段

`0.1.0` 先建立可驗證的本機垂直切片：每日固定、逆天改命、五領域顯示與統計。籤池目前只放入少量已核對古籍來源的測試籤，用來驗證機制；正式匯入完整 100 籤前不會把自創籤詩混入資料。

- 產品規格：[`docs/PRODUCT_SPEC.md`](docs/PRODUCT_SPEC.md)
- 技術架構：[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- 籤文來源：[`docs/SOURCES.md`](docs/SOURCES.md)
- Supabase 初始資料庫：[`supabase/migrations/0001_initial.sql`](supabase/migrations/0001_initial.sql)

## 技術基線

- Kotlin 2.3.21
- Jetpack Compose
- Android Gradle Plugin 9.3.1
- Gradle 9.5.0
- `compileSdk 36` / `targetSdk 36` / `minSdk 26`
- Preferences DataStore：目前本機狀態與彙總統計
- Supabase：下一階段接入帳號與事件同步

目前 Google Play 自 2026-08-31 起要求新的手機 App 指定 Android 16（API 36）以上為目標，因此專案從一開始即以 API 36 為 `targetSdk`。

## 本機建置

首次 push 後，GitHub Actions 會自動產生並提交 Gradle Wrapper。之後可使用：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Windows：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Supabase 金鑰、簽章檔與任何本機密鑰不得提交到 Git。
