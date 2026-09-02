# 今日運勢 / Daily Fortune

Android 星座運勢 App。公共十二星座命運由當日真實天象與固定 Astrology Engine v1 規則計算；使用者可按「逆天改命!!」安全亂數抽取另一個物理上真實存在的完整天空，再用同一套占星規則重算私人世界線。

## 已定案核心

- 公共天命：真實星曆，無亂數，十二星座同日結果中央一致。
- 逆天改命：不使用古籤；隨機替換為另一個同季節、完整且物理一致的真實天空，再重新占星。
- 五領域：財運、戀愛、工作／學業、人際、健康；各自獨立計分。
- 全部結果可追溯到 astronomy snapshot、AstrologyFactor、領域加減分、engine version。
- Room/SQLite 是 Android 本機命運資料 source of truth；DataStore 只留小型設定。
- 被改掉的世界線保留為 immutable history，統計由事件歷史重新聚合。
- 正式版沒有「後端掛掉就本機假算一份」fallback；有 cache 離線讀，沒 cache 就 unavailable。
- Supabase migration、Edge Function、每日中央生成與部署規劃已放在 Repo；真正部署等 Free project 建立。

## 技術基線

- Kotlin 2.3.21
- Jetpack Compose
- Android Gradle Plugin 9.3.1
- Room 3.0.2 + bundled SQLite
- KSP 2.3.9
- Astronomy Engine 2.1.19
- `compileSdk 36` / `targetSdk 36` / `minSdk 26`

## 文件

- 產品規格：[`docs/PRODUCT_SPEC.md`](docs/PRODUCT_SPEC.md)
- 占星規則：[`docs/ASTROLOGY_ENGINE_V1.md`](docs/ASTROLOGY_ENGINE_V1.md)
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

Supabase secret、正式簽章金鑰與其他敏感值不得提交到 Git。
