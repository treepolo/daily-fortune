# 今日運勢 / Daily Fortune

Android 每日抽籤 App。使用者每天親自抽一次五項運勢，並可不限次數按「逆天改命!!」重抽；同一天重新開啟 App 會恢復最後一籤。

## 目前產品核心

- 五項：財運、戀愛、工作／學業、人際、健康。
- `1=大凶`、`2=凶`、`3=小凶`、`4=平`、`5=小吉`、`6=吉`、`7=大吉`。
- 預設五項彼此獨立，每級機率皆為 `1/7`。
- 總體先取五項算術平均，目前預設無條件捨去。
- 初抽一定由使用者按「抽籤」觸發；開啟 App 本身不產生命運。
- 「逆天改命!!」重抽全部五項、放回抽樣、無每日次數上限。
- 每日邊界使用裝置當下時區。
- 使用者前端不顯示個人歷史統計。
- 無星座、天文、占星、公共命運、平行天空或卜辭。

## 實驗與研究

App 從架構上支援 A/B/n 多臂實驗。已支援的參數可由 Remote Config 調整，不需要要求使用者更新 APK，包括：

- 初抽與重抽的 1～7 機率分布
- 五項獨立抽樣或 Gaussian copula 相關抽樣
- 五項相關矩陣
- `FLOOR` / `CEIL` / `ROUND` / `PIECEWISE` 總體判定規則
- 預留的靜態／揭曉視覺 variant ID

同一匿名 installation 在同一 experiment/salt 下穩定分組；variant 數量不限兩組，流量權重可任意配置。

研究分析不放在 Android App。App 只取得 resolved config、執行 treatment、把匿名事件先寫入本機 queue 再批次上傳。群體統計、SQL、Python、Notebook、Dashboard 等分析位於獨立研究端。

未配置研究後端 URL 時，App 仍可完全離線使用 embedded default config。

## 技術基線

- Kotlin 2.3.21
- Jetpack Compose
- Android Gradle Plugin 9.3.1
- Room 3.0.2 + bundled SQLite
- KSP 2.3.9
- Supabase Edge Functions / PostgreSQL（Remote Config 與匿名事件收集）
- `compileSdk 36` / `targetSdk 36` / `minSdk 26`

## 文件

- 產品規格：[`docs/PRODUCT_SPEC.md`](docs/PRODUCT_SPEC.md)
- 技術架構：[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- Android 本機資料：[`docs/LOCAL_DATA.md`](docs/LOCAL_DATA.md)
- Supabase 部署：[`supabase/DEPLOYMENT.md`](supabase/DEPLOYMENT.md)

## 本機建置

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Windows：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

若要建置已連接研究後端的 APK，可設定：

```text
DAILY_FORTUNE_CONFIG_URL=https://<project>.supabase.co/functions/v1/experiment-config
DAILY_FORTUNE_ANALYTICS_URL=https://<project>.supabase.co/functions/v1/analytics-events
```

Supabase service-role secret 與其他敏感值不得提交到 Git 或打包進 App。