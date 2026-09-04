# 今日運勢 Android 上架檢查表

## 已由程式／CI 處理

- [x] Remote Config / A/B experiment / telemetry production backend。
- [x] Remote Config cache 與 non-blocking startup。
- [x] 離線 telemetry queue 與恢復網路補傳。
- [x] Google Mobile Ads SDK + rewarded ad framework。
- [x] Google UMP 隱私同意 framework。
- [x] AdMob App ID：`ca-app-pub-8284304703726644~2628433223`。
- [x] Rewarded ad unit：`ca-app-pub-8284304703726644/9731073792`。
- [x] Production Remote Config 預設 `ads.enabled=false`。
- [x] 大吉（overall score 7）廣告 bypass policy 已預留。
- [x] Debug/QA 強制 Google 官方測試 rewarded ad unit，避免誤打正式廣告流量。
- [x] 廣告載入／顯示／完成／失敗／reroll unlock telemetry。
- [x] CI 產 installable QA APK。
- [x] CI 產 Play AAB；有 upload-key secrets 時自動正式簽章。
- [x] Privacy Policy 草稿。
- [x] Google Play Data safety 填表草稿。
- [x] app-ads.txt 內容準備。

## Play upload key / GitHub Actions secrets

正式上 Play 前，需要一把**只屬於此 App 的 upload key**。不要把 `.jks`、密碼或 Base64 金鑰 commit 到 repo。

CI 已預留四個 GitHub Actions secrets：

- `DAILY_FORTUNE_UPLOAD_KEYSTORE_B64`
- `DAILY_FORTUNE_RELEASE_STORE_PASSWORD`
- `DAILY_FORTUNE_RELEASE_KEY_ALIAS`
- `DAILY_FORTUNE_RELEASE_KEY_PASSWORD`

設定完成後，main CI 的 `daily-fortune-play-aab` 會是由 upload key 簽署的 AAB；未設定時 CI 仍會建出 AAB，但 artifact 內 `release-signing.txt` 會標示 `unsigned-needs-github-secrets`，不可直接當正式上架包。

## 需要帳號擁有者在 Google 後台人工處理

### AdMob

- [ ] 在「隱私權與訊息」設定 GDPR/適用地區的訊息；App 已整合 UMP，但實際訊息內容由 AdMob 後台管理。
- [ ] App 上架後，把 AdMob App 連結到 Google Play 商店頁。
- [ ] 設定 developer website，並確保該網站**根網域**可讀取 `/app-ads.txt`。
- [ ] 在 AdMob 檢查 app-ads.txt / App 驗證狀態。
- [ ] 真正要開始營利時，才將 Supabase `app_config.default.ads.enabled` 改成 `true`。

### Google Play Console

- [ ] 建立 Android App：package `com.treepolo.dailyfortune`。
- [ ] 啟用 Play App Signing，使用本專案 upload key 上傳 AAB。
- [ ] 填寫 App access（目前無登入限制）。
- [ ] 填寫 Ads declaration：AAB 含廣告 SDK且可遠端啟用，應回答 App contains ads = Yes。
- [ ] 填寫 Data safety：依 `docs/play-data-safety.md` 再對照當下表單逐項確認。
- [ ] 填寫內容分級、目標族群與政策聲明。
- [ ] 提供公開 Privacy Policy URL。
- [ ] 填商店標題、簡短說明、完整說明、圖示、feature graphic、手機 screenshots。
- [ ] 若帳號受「12 名測試者連續 14 天 closed test」規則約束，先完成該流程再申請 production。
- [ ] 最後人工確認並送出審查／發布。

## Release regression matrix

每個候選 AAB/QA APK 至少驗證：

1. 冷啟動：不被 Supabase/AdMob/telemetry 阻塞。
2. 已有今日籤時重開：狀態恢復正確。
3. 首次抽籤／reroll：資料與 reveal flow 正確。
4. 離線啟動、離線抽籤、關 App、恢復網路後 telemetry 補傳。
5. Remote Config cache TTL 與更新後 config_id 改變。
6. Ads OFF：完全維持目前體驗，不載入／顯示廣告。
7. Ads ON + debug：只出 Google 測試 rewarded ad。
8. Ads ON + 大吉：bypass 廣告。
9. Ads ON + rewarded 完成：reroll 解鎖且 telemetry 完整。
10. Ads ON + no-fill / timeout / 斷網：依 `FAIL_OPEN` / `FAIL_CLOSED` policy 正確處理。
11. 舊 QA 版升級安裝到新 QA APK：簽章／versionCode 可正常升級。
12. Play AAB 簽章：正式上架前確認 upload key，而非 repo debug key。
