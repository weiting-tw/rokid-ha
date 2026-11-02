# 實機驗證清單（P0）

用途：在目標設備上逐步驗證 CXR-L CustomView 基本能力。結果填入 [`SDK_COMPATIBILITY.md`](SDK_COMPATIBILITY.md) §4。
不需要 ADB：P0 App 內建事件 log 與「Copy report」。

---

## A. 測試前準備

| # | 項目 | 記錄 |
| --- | --- | --- |
| A-1 | 手機為 **ARM** Android 裝置，Android 12（API 31）以上。不可用 x86 模擬器（Rokid 原生函式庫只有 ARM）。 | 型號 / Android 版本： |
| A-2 | 眼鏡 Glasses_0651 已充電、開機，韌體 1.27.009-20261014-150203 | 韌體： |
| A-3 | 手機已安裝並登入 **Rokid AI App ≥ 1.9.0**（大陸）**或** **Hi Rokid**（海外）。記下是哪一個。 | App / 版本： |
| A-4 | 眼鏡已在該 App 中配對並連線，且能正常使用（例如 App 內看得到電量） | |
| A-5 | 安裝 P0 App：`./gradlew :app:assembleDebug`，APK 在 `app/build/outputs/apk/debug/app-debug.apk`（側載或用 Android Studio 安裝） | client-l 版本： |
| A-6 | 測試期間關閉其他使用 CXR 的 App（例如官方 Sample），避免搶同一個服務 | |

## B. P0 主流程（對應 SDK_COMPATIBILITY.md §1）

每一步完成後，看 P0 App 的 **Link** 卡片與 **Event log**。

| 步驟 | 操作 | 預期 | 對應項目 | 結果 |
| --- | --- | --- | --- | --- |
| B-1 | 開啟 P0 App | 畫面出現；Environment 卡片顯示 Rokid AI App / Hi Rokid 版本與 SDK check 結果 | #2 | ☐ |
| B-2 | 若兩個 SDK check 都是 false | 記錄已安裝的 App 名稱與版本後停止（無法繼續） | — | ☐ |
| B-3 | 按 **1 Authorize** | 跳轉到 Rokid AI App / Hi Rokid 授權頁 → 同意 → 返回；log 出現 `auth: result=SUCCESS ... tokenPresent=true`；Link 顯示 `AUTHORIZED` | #3 | ☐ |
| B-3a | 若 B-3 失敗或取消 | 記錄 log 中的 `resultCode`。可能是空權限不被接受 → 回報，下一版改為申請 `MICROPHONE`（官方範例做法）再試 | #3 | ☐ |
| B-4 | 按 **2 Connect** | log：`session: configCXRSession(CUSTOMVIEW) -> true`、`link: connect(token) -> true` | #6 | ☐ |
| B-5 | 等待 ≤ 10 秒 | log：`cb: onCXRLConnected(true)`；Link「CXR service」= true | #4 | ☐ |
| B-6 | 同上 | log：`cb: onGlassBtConnected(true)` **或** `isGlassBtConnected()=true`；「Link ready」= true；Environment 出現眼鏡名稱與韌體 | #5 | ☐ |
| B-6a | 記錄 `onGlassDeviceInfo` 中的 `systemVersion` 是否等於 1.27.009 | 若不同，以裝置回報為準更新文件 | #5 | ☐ |
| B-7 | 按 **3 Open view** | log：`view: customViewOpen(...) -> true` 然後 `cb: onCustomViewOpened()`；「CustomView open」= true | #7 | ☐ |
| B-8 | **戴上眼鏡看** | 看到 4 行綠色文字：`ROKID HA · P0`、`CustomView OK`、`update #0`、`opened HH:mm:ss` | #8 | ☐ |
| B-9 | 在 P0 App 對 #7、#8 按 **Seen on glasses** / **Not seen** | 人員確認 | #7 #8 | ☐ |
| B-10 | 按 **4 Update** 三次 | 每次 log：`customViewUpdate(2 item(s)) -> true` 與 `cb: onCustomViewUpdated() #n`；眼鏡上 `update #1/#2/#3` 與時間改變，其他行不閃爍、不重排 | #9 | ☐ |
| B-11 | 按 **5 Close** | log：`cb: onCustomViewClosed()`；眼鏡畫面回到系統畫面 | #10 | ☐ |
| B-12 | 再按 **3 Open view** | 再次 `onCustomViewOpened()`；眼鏡上 `update #0` 重新出現；清單 #10 顯示 re-opened | #10 | ☐ |
| B-13 | 按 **isOpen?** | log：`customViewIsOpen() -> true`（與 callback 狀態一致） | — | ☐ |
| B-14 | 按 **5 Close** → **Disconnect** | log：`link: disconnected` | — | ☐ |
| B-15 | 按 **Copy report**，存成 `docs/device-runs/YYYY-MM-DD-1.md` | 報告已將眼鏡 SN 遮蔽；確認內容沒有 token | — | ☐ |

## C. 顯示特性（P1 UI 設計需要的資料）

| # | 問題 | 方法 | 結果 |
| --- | --- | --- | --- |
| V-11a | 可視區域：預設 `marginTop 160dp / marginBottom 80dp`（官方範例值）下，文字是否完整、置中、未被裁切 | 目視；必要時調整 `DisplayScreenRenderer.Style` 後重測 | |
| V-11b | 中文字是否正常顯示（「客廳」「冷氣」） | 修改測試文字 | |
| V-11c | `22sp / 18sp / 14sp` 是否易讀；一屏最多幾行 | 目視 | |
| V-11d | `#FF00FF00` 與 `#FF00A000` 是否有可分辨的亮度差 | 目視 | |
| V-11e | `°`、`%`、`·` 等符號是否正常 | 目視 | |

## D. 行為邊界（文件未說明，需實機回答）

| # | 問題 | 方法 | 結果 |
| --- | --- | --- | --- |
| V-12a | View 已開啟時再呼叫 `customViewOpen` 會怎樣？ | 目前 adapter 會拒絕；需要時暫時移除檢查做一次實驗 | |
| V-12b | 連續快速 Update（例如 10 次 / 秒）是否丟失或報錯 | 快速連按 Update | |
| V-12c | 眼鏡螢幕熄滅（不戴 / 逾時）後 View 是否保持？重新點亮後是否仍在？ | 摘下眼鏡等待熄屏再戴上 | |
| V-12d | 按 AI 鍵喚醒助理後 View 是否被關閉？是否收到 `onCustomViewClosed`？ | 按眼鏡 AI 鍵 | |
| V-12e | 進入系統場景（拍照、錄音、導航）後的行為 | 觸發系統功能 | |
| V-12f | 眼鏡藍牙斷線再連回：是否收到 `onGlassBtConnected(false/true)`？View 是否需要重開？ | 關閉眼鏡藍牙或走遠 | |
| V-12g | 強制停止 Rokid AI App：P0 App 是否收到斷線？重新開啟後可否不重新授權就 Connect？ | 系統設定 → 強制停止 | |
| V-12h | P0 App 被殺後重開：token 已不在記憶體，是否需要重新授權？授權是否可直接同步回傳（不跳頁）？ | 滑掉 App 再開 | |
| V-12i | 同一 token 斷線後再 Connect 是否可用？token 是否會過期？ | Disconnect → Connect；隔天再試 | |
| V-12j | 戴著眼鏡時 `onGlassWearingStatus` 是否觸發 | 摘戴眼鏡 | |
| V-12k | `client-l:1.0.3` 重建後 B-1~B-15 是否相同 | `-Prokid.cxrl.version=1.0.3` | |

## E. 完成條件

P0 完成 = §B 全部通過、§1 的 #2–#10 在 SDK_COMPATIBILITY.md 中改為 VERIFIED，並附上報告檔。
在此之前**不開始** Home Assistant 介面實作（專案規則）。
