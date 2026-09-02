# 今日運勢 / Daily Fortune

一個以真實天文星曆與固定占星規則產生每日十二星座運勢的 Android App。

## 核心世界觀

- **公共天命**：以 `Asia/Taipei` 當日 00:00–24:00 的真實天空為輸入，經 Astronomy Engine + Astrology Engine v1 算出十二星座的綜合、財運、戀愛、工作／學業、人際、健康。
- **逆天改命**：不抽古籤，也不把各行星各自亂骰。系統使用安全亂數選另一年份，再找與今天正午太陽黃經最接近的真實星曆日期，整包採用那一天物理一致的天空，最後用完全相同的 Astrology Engine 重算私人命運。
- 改命可能變好、變差或幾乎不變；系統不為了討好使用者篩掉壞結果。
- 每個結果都保留 audit：天文資料 → 成立的占星因素 → 五領域加減分 → 吉凶與說明。
- 同一天的公共十二星座是中央權威資料；個人改命只改本人的世界線。

古籤／籤詩機制已自產品與執行路徑淘汰。

## 目前開發狀態

Android 本機原型已能：

- 選擇太陽星座。
- 計算真實天象的十二星座公共運勢。
- 單行電視新聞式跑馬燈。
- 使用安全亂數建立物理可成立的私人平行天象並重算。
- 顯示改命前後的吉凶與主要新增／移除因素。
- 保存今日私人世界線與改命統計。

Repo 也已備妥尚未部署的 Supabase 後端：

- PostgreSQL schema / RLS / 不可變公共天命。
- Astronomy snapshot 正規化保存。
- TypeScript/Deno 版 Astrology Engine v1。
- 每日中央 12 星座生成與交易鎖定。
- 伺服器安全亂數私人平行天象改命。
- 私人改命交易與跨裝置衝突保護。
- 星座變更隔日生效的資料層。

真正接上 Supabase 專案後，正式 App 會把中央端當權威；目前 Android 本機計算只作開發與實機驗證。

## 文件

- 產品規格：[`docs/PRODUCT_SPEC.md`](docs/PRODUCT_SPEC.md)
- 技術架構：[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- Astrology Engine v1：[`docs/ASTROLOGY_ENGINE_V1.md`](docs/ASTROLOGY_ENGINE_V1.md)
- 資料與規則來源：[`docs/SOURCES.md`](docs/SOURCES.md)
- Supabase 部署：[`supabase/DEPLOYMENT.md`](supabase/DEPLOYMENT.md)
- Supabase 初始 migration：[`supabase/migrations/0001_initial.sql`](supabase/migrations/0001_initial.sql)

## 技術基線

- Kotlin 2.3.21
- Jetpack Compose
- Android Gradle Plugin 9.3.1
- Gradle 9.5.0
- `compileSdk 36` / `targetSdk 36` / `minSdk 26`
- Astronomy Engine 2.1.19（MIT）
- Preferences DataStore：目前開發期本機狀態
- Supabase：正式中央權威與同步，程式已備妥、專案尚未部署
- GitHub Actions：Deno 後端檢查／測試 + Android 單元測試 + Debug APK

## 本機建置

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Windows：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Supabase secret key、正式簽章檔、Cron secret 與其他私密金鑰不得提交到 Git。
