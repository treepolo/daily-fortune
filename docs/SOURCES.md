# 資料、規則與授權來源

本專案已淘汰古籤／籤詩資料；正式命運系統只依天文星曆與版本化占星規則運作。

## Astronomy Engine

- 專案：https://github.com/cosinekitty/astronomy
- 本專案固定 v1 天文依賴：`2.1.19`
- 授權：MIT
- 用途：太陽、月亮與行星位置、地心向量與黃道座標等星曆計算。
- Android 使用 Kotlin 版本；Supabase Edge Function 使用 npm `astronomy-engine@2.1.19`。

## NASA/JPL Horizons

- https://ssd.jpl.nasa.gov/horizons/
- 用途：開發期間抽查／驗證天體位置。
- 不作正式 App runtime 硬依賴，因此 JPL API 暫時不可用不會讓每日運勢停止計算。

## 占星規則參考

Astrology Engine v1 將傳統太陽星座占星概念形式化成固定演算法。參考入口包括：

- Astrodienst / Astrowiki — Tropical Zodiac
  - https://www.astro.com/astrowiki/en/Tropical_Zodiac
- Astrodienst / Astrowiki — Sun Sign Astrology
  - https://www.astro.com/astrowiki/en/Sun_sign_astrology
- Astrodienst / Astrowiki — Aspect
  - https://www.astro.com/astrowiki/en/Aspect
- Astrodienst / Astrowiki — Essential Dignity
  - https://www.astro.com/astrowiki/en/Essential_Dignity

行星／宮位／相位的大方向採成熟占星傳統中常見的對應；數值權重、容許度、分數門檻與組合公式沒有業界唯一標準，因此是本專案明確版本化的模型設計。完整數字見 `docs/ASTROLOGY_ENGINE_V1.md`。

## 產品定位

天體位置與相位的輸入是可驗算的天文計算；「這些天象代表財運、戀愛或吉凶」是占星術解讀，不具有已建立的科學預測效力。產品文案與商店資料應把此功能定位為娛樂性占星。
