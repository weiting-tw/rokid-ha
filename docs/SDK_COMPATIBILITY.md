# SDK 相容性驗證（P0）

> 最後更新：2026-11-02
> 目標：Rokid Glasses Founder Edition（Glasses_0651）· YODAOS-SPRITE · 韌體 1.27.009-20261014-150203
> SDK：`com.rokid.cxr:client-l:1.0.4`（預設），`1.0.3`（對照）

## 狀態定義

| 狀態 | 意義 |
| --- | --- |
| **VERIFIED** | 已在**目標實機**（Glasses_0651 + 實際手機）上測試並由人員確認 |
| **DOCUMENTED** | 官方文件有說明，**尚未實機驗證** |
| **EXPERIMENTAL** | 社群範例、二進位中存在但文件未記載、或實驗性功能 |
| **UNSUPPORTED** | 已確認不支援（註明確認依據） |

規則：**不得把 DOCUMENTED 當成 VERIFIED。** P0 App 畫面上的「observed（已觀察到回呼）」也不等於 VERIFIED；必須有人實際看到眼鏡畫面並把結果填入 §4。

> **目前沒有任何項目是 VERIFIED。** 本次工作在雲端容器完成，沒有手機、眼鏡或 ADB。
> 第 1 項（Gradle 解析）是建置環境的驗證，不是實機驗證，另外標註。

---

## 1. P0 驗證項目總表

| # | 項目 | 狀態 | 依據 / 備註 |
| --- | --- | --- | --- |
| 1 | Gradle 能成功解析 Rokid SDK | **VERIFIED（建置環境）** | 2026-11-02：1.0.4 與 1.0.3 都解析成功，含傳遞依賴 `cxr-service-bridge:1.0-20260522.063600-105`（timestamped snapshot）。見 §2。**這是建置主機驗證，與眼鏡無關。** |
| 2 | Android App 能正常啟動 | DOCUMENTED | APK 已建置（debug）；尚未安裝到實體手機。注意：Rokid 原生函式庫只有 arm64-v8a / armeabi-v7a，x86 模擬器無法建立 `CXRLink`。 |
| 3 | 能取得 Rokid 授權 | DOCUMENTED | `AuthorizationHelper.requestAuthorization` / `parseAuthorizationResult`（官方「鑑權」章節）。P0 不申請任何眼鏡端權限，**是否允許空權限授權待實機確認**。 |
| 4 | 能連接 Rokid AI App | DOCUMENTED | `connect(token)` → `onCXRLConnected(true)`。須確認目標設備是用 Rokid AI App（大陸）還是 Hi Rokid（海外）。 |
| 5 | 能辨識眼鏡連線狀態 | DOCUMENTED | `onGlassBtConnected`、`isGlassBtConnected()`（後者見於二進位與社群範例，文件未列，EXPERIMENTAL）、`onGlassDeviceInfo`。 |
| 6 | 能建立 CUSTOMVIEW Session | DOCUMENTED | `configCXRSession(CXRSession(CUSTOMVIEW))` 必須在 `connect` 前呼叫。 |
| 7 | 能開啟 CustomView | DOCUMENTED | `customViewOpen(json)` → `onCustomViewOpened()`；須鏈路就緒。 |
| 8 | 能顯示簡單 TextView | DOCUMENTED | 官方 schema（LinearLayout + TextView）。中文字型、字級、可視區域、綠色映射皆未知。 |
| 9 | 能用 customViewUpdate 更新內容 | DOCUMENTED | `[{"action":"update","id":..,"props":{"text":..}}]` → `onCustomViewUpdated()`。 |
| 10 | 能正常關閉及重新開啟 CustomView | DOCUMENTED | `customViewClose()` → `onCustomViewClosed()`，再 `customViewOpen`。重複開啟的行為文件未說明。 |

---

## 2. 建置驗證紀錄（建置環境，非實機）

| 項目 | 結果 |
| --- | --- |
| 日期 | 2026-11-02 |
| 主機 | Linux x86_64 雲端容器，OpenJDK 21.0.12，Gradle 8.14.3 |
| Android SDK | platform `android-35`，build-tools `35.0.0` |
| 工具鏈 | AGP 8.7.3、Kotlin 2.0.21、Compose BOM 2024.12.01 |
| `./gradlew test assembleDebug`（client-l **1.0.4**） | ✅ BUILD SUCCESSFUL；單元測試 11/11 通過 |
| `./gradlew -Prokid.cxrl.version=1.0.3 clean test assembleDebug` | ✅ BUILD SUCCESSFUL；單元測試 11/11 通過；APK 內 SDK 字串 `CXRL SDK ver: 1.0.3` |
| APK 內原生函式庫 | `lib/arm64-v8a`、`lib/armeabi-v7a`：`libcaps.so`、`libcxr-bridge-jni.so`、`libcxr-sock-proto-jni.so`、`libflora-cli.so`、`libmutils.so`。**沒有 x86/x86_64 版本。** |
| merged manifest | `INTERNET` 權限；`<queries>` 含 `com.rokid.sprite.aiapp`、`com.rokid.sprite.global.aiapp`；無 `networkSecurityConfig` |

建置環境附註：此雲端容器對 `repo.maven.apache.org` 會收到 HTTP 429（限流），所以建置時用了一個**不進版控**的 Gradle init script，把 Maven Central 導向 Google 的官方鏡像
（`maven-central.storage-download.googleapis.com`）。專案本身的 repository 設定是標準設定，一般開發機不需要這個 script。

### 1.0.3 與 1.0.4 相容性結論

- 1.0.4 是 1.0.3 的**純新增超集合**（javap 比對，無刪除、無簽章改變），兩者依賴完全相同。
- 本專案的 adapter 只使用兩版共有的 API，因此兩版都能編譯。
- 1.0.4 才有的 API（`ICXRSessionCbk`、`setGlassBrightness/Volume`、`getCXRSessionState`、`GlassInfo.screenOn`）**刻意不用**，待 P1 再評估。
- 建議預設使用 **1.0.4**（官方文件與範例目前對應的版本）。若實機在 1.0.4 出問題，用 `-Prokid.cxrl.version=1.0.3` 重建交叉比對。

---

## 3. 延伸能力狀態（P0 範圍外，供規劃參考）

| 能力 | 狀態 | 依據 |
| --- | --- | --- |
| CustomView `ImageView` + `customViewSetIcons` | DOCUMENTED | 官方 schema |
| CustomView `RelativeLayout` | DOCUMENTED | 官方 schema（注意 `layout_aligin*` 拼法） |
| CustomView 點擊 / 選取 / 輸入事件 | **UNSUPPORTED**（依文件） | `ICustomViewCbk` 只有 opened/updated/closed/iconsSent/error；schema 無事件欄位。依據是官方文件與 SDK 介面，非實機。 |
| CustomView Lottie 動畫 | UNSUPPORTED（CXR-L） | 範例有 `LottieAnimProps`，但 schema 未列、CXR-L 無對應 API |
| 亮度 / 音量控制（0…15） | DOCUMENTED | 僅 1.0.4 |
| Session 暫停 / 系統場景搶佔回呼（`ICXRSessionCbk`） | EXPERIMENTAL | 1.0.4 二進位存在，文件未記載 |
| 音訊串流 | DOCUMENTED | 需 `GlassPermission.MICROPHONE`，會話建構完成後才可用 |
| 拍照 | DOCUMENTED | 需 `GlassPermission.CAMERA` |
| CustomApp（遠端安裝 / 啟動眼鏡端 APK） | DOCUMENTED | 需 CXR-S；`appUploadAndInstall` 不需要 ADB |
| 自訂指令（手機 ⇄ 眼鏡 `Caps`） | DOCUMENTED | 僅 CustomApp |
| 鏡腿按鍵：單擊 / 雙擊 / 長按 / 按下 / 放開 | DOCUMENTED | 眼鏡端系統有序廣播 `com.android.action.ACTION_SPRITE_BUTTON_*`，**僅能在眼鏡端 App（CustomApp）接收** |
| 觸控板：雙指單擊 / 雙擊 / 前滑 / 後滑、AI 長按 | DOCUMENTED | `ACTION_TWO_FINGER_*`、`ACTION_AI_START`；同上 |
| 雙擊鍵攔截 | DOCUMENTED（限制） | 官方範例註解：雙擊是系統「退出」事件，**不可截斷** |
| 單指觸控板滑動 | 無資料 | 官方廣播清單中沒有單指滑動 |
| IMU（頭部動作） | EXPERIMENTAL | CXR-L / CXR-S **都沒有 IMU API**。社群韌體分析顯示有 ICM-4x6xx IMU；眼鏡端 App 或可用標準 Android `SensorManager`，完全未驗證 |
| 眼鏡端 ADB | DOCUMENTED（限制） | 需經 Rokid AI App 開啟，且需專用開發線（非標配） |
| CXR-M | 不評估 | 授權模式不明，見 SDK_RESEARCH.md §9 |

---

## 3a. Home Assistant 整合功能的硬體相依狀態（2026-11-02 新增）

本節使用 VERIFIED / DOCUMENTED / EXPERIMENTAL / **UNKNOWN**（無文件也未測試）。手機端 HA 邏輯以自動化測試涵蓋，但那不是眼鏡實機驗證。

| 功能 | 狀態 | 說明 |
| --- | --- | --- |
| 眼鏡鏈路就緒後自動開啟 Dashboard | DOCUMENTED | 依 `customViewOpen` / `onCustomViewOpened`；邏輯以假眼鏡測試 |
| 狀態變化即時更新（只送變更行） | DOCUMENTED | `customViewUpdate`；更新頻率上限未知（V-12b），bridge 預設最小間隔 500 ms |
| 8 行文字 Dashboard 的可讀性 / 可視區域 | UNKNOWN | 行數、字級、中文顯示待 V-11 |
| 在眼鏡上顯示 HA 通知 | DOCUMENTED | 以 Dashboard 的一行文字呈現（無彈窗能力） |
| 從眼鏡本身觸發控制 | UNSUPPORTED（CustomView 本身） | CustomView 無輸入事件 |
| AI 鍵 / 喚醒 → 手機（`onGlassAiAssistStart`、`onGlassAiInterrupt`） | DOCUMENTED（回呼）／EXPERIMENTAL（作為控制輸入） | 已實作為單鍵 PRIMARY 輸入（預設關閉，兩段式確認）；未實機驗證，見 GLASSES_CONTROL.md |
| `setInterruptAiWake`（抑制 Rokid 助理） | EXPERIMENTAL | 二進位存在、文件未記載；可選開關 |
| 鏡腿按鍵 / 觸控板事件 → 手機 | DOCUMENTED（僅經 CXR-S 眼鏡端 CustomApp 轉送） | 需第二個 APK；未實作，待核准 |
| 語音辨識結果 → 手機 | UNKNOWN（CXR-L 無 API） | 只有原始 PCM 音訊串流（DOCUMENTED） |
| 手機端命令管線（allowlist、去重、無重送、狀態確認） | SIMULATED | `CommandPipelineTest` 15 項 |
| 眼鏡掃描 QR Code | UNKNOWN | CXR-L 只有 `takePhoto`，無 QR 解碼；未實作也未計畫 |
| App 重啟後無需使用者操作即重新連上眼鏡 | UNKNOWN | Rokid token 只存記憶體；授權「可能同步返回」為 DOCUMENTED，未驗證 |
| 前景服務期間 CXR-L 連結在背景保持 | UNKNOWN | 取決於 Rokid AI App / Hi Rokid 背景行為 |
| HA token 不會傳到眼鏡 | 以設計 + 自動化測試保證 | Bridge 型別上拿不到 token；`GlassesCredentialIsolationTest` 檢查所有送往眼鏡的文字 |
| mDNS 自動發現 | UNKNOWN（實網） | NsdManager 實作；TXT 解析有單元測試；VLAN / 隔離 Wi-Fi / VPN 下預期找不到 |

## 4. 實機驗證紀錄

每次實機測試新增一列。使用 P0 App 的「Copy report」把報告貼到 `docs/device-runs/YYYY-MM-DD-<n>.md`，並在此填表。
只有在「人員確認」欄為「是」時，才能把 §1 對應項目改為 VERIFIED。

| 日期 | 測試者 | 手機型號 / Android | Rokid AI App 或 Hi Rokid 版本 | 眼鏡韌體（`GlassInfo.systemVersion`） | client-l | 通過項目 | 失敗項目 | 報告 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| _尚未執行_ | | | | | | | | |

### 失敗時的記錄要求

- 最早失敗的步驟編號與 log 中的 `ERROR` 行
- `onCustomViewError` 的 code / message（若有）
- 是否可重現；換 1.0.3 是否相同
- **不要**貼出 token；P0 App 不會顯示或記錄 token，但 SDK 本身會把 token 寫進 logcat（SDK_RESEARCH.md §8-2），分享 logcat 前要先遮蔽。
