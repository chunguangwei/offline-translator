# 译人 iOS App Store 上架素材

> 2026-07-10 更新。目标：**海外英文区上架**（不上中国区、欧盟区），**完全免费、无内购**。
> App Store 显示名：**Yiren AI**（桌面图标名仍是「译人」，无需改工程）。
> 本文是提交 App Store Connect 时逐字段照抄的内容 + 待办清单。文案可改，先有可用初稿。

---

## 0. 一句话定位

全部在手机本地运行的离线 AI 翻译 + 问答 + 背单词 App：中英互译、AI 对话、拍照/语音翻译、
单词本与间隔重复记忆，**断网可用、数据不出手机**。

---

## 1. App 信息（App Information）

| 字段 | 中国区（简体中文） | 海外（English, U.S.） |
|---|---|---|
| **名称 Name**（≤30） | Yiren AI |
| **副标题 Subtitle**（≤30） | 全离线 · 翻译问答 · 背单词 | Private on-device AI translator |
| **主分类 Primary** | 参考（Reference） | Reference |
| **次分类 Secondary** | 教育（Education） | Education |
| **版权 Copyright** | © 2026 weichunguang | © 2026 weichunguang |
| **版权/反馈联系 Contact** | chunguangwee@gmail.com | chunguangwee@gmail.com |
| **价格 Price** | 免费（Free） | Free |

> 备选主分类：效率（Productivity）。翻译类放 参考 / 效率 都常见；学习闭环让 教育 适合做次分类。
> 桌面图标名仍是「译人」（`CFBundleDisplayName`），与 App Store 列表名可不同，无需改工程。

---

## 2. 推广文本 Promotional Text（≤170，可随时改、不需审核）

**中文：**
> 模型全部跑在手机本地，断网也能用。中英互译、AI 问答、拍照与语音翻译、单词本与间隔重复记忆，
> 全程离线，数据不出手机。

**English:**
> A fully offline AI translator. Translation, AI Q&A, camera & voice translation, vocabulary books with
> spaced-repetition review — everything runs on your device. No internet required, nothing leaves your phone.

---

## 3. 描述 Description

### 中文

> **译人**是一款完全离线的 AI 翻译与学习工具。所有 AI 模型都在你的手机本地运行——
> 不联网也能用，输入的内容、照片、语音、聊天记录**全部留在手机里，不上传任何服务器**。
>
> **主要功能**
> · 中英互译：地道、单语输出，支持语音输入与拍照翻译
> · AI 问答：本地大模型对话，多种角色人设，长对话自动压缩上下文
> · 拍照翻译：对着文字拍一张，逐行对照翻译
> · 语音翻译：说一句，离线转写成文字再翻译
> · 单词本：粘贴或导入生词，AI 自动提取建库；可编辑、添加、一键去重
> · 间隔重复记忆（SRS）：每日到期复习、连续打卡、错题本、本地提醒，科学记词
> · 翻译历史与收藏：随时回看、星标收藏纳入复习
> · 备份与还原：一个文件搬走全部数据，换手机也不丢
>
> **隐私优先**
> 译人不需要注册、不收集个人信息、不含广告与追踪。AI 推理全在设备本地完成。
> 仅在你主动下载模型 / 检查更新时联网获取文件，不会发送你的任何使用数据。
>
> **关于模型**
> 首次使用需在 App 内一次性下载离线 AI 模型（约 2.4 GB，建议 Wi-Fi）。下载后即可永久离线使用。

### English

> **Yiren** is a fully offline AI translator and study tool. Every AI model runs locally on your device —
> it works without internet, and your text, photos, voice, and chats **stay on your phone and are never
> uploaded to any server**.
>
> **Features**
> · Chinese ⇄ English translation — natural, single-language output, with voice and camera input
> · AI chat — on-device large language model with selectable personas and automatic long-context handling
> · Camera translation — snap a photo of text for line-by-line translation
> · Voice translation — speak and have it transcribed offline, then translated
> · Vocabulary books — paste or import words; AI extracts entries automatically; edit, add, one-tap dedup
> · Spaced-repetition review (SRS) — daily due cards, streaks, a "hard words" deck, and local reminders
> · History & favorites — revisit translations, star them into your review pool
> · Backup & restore — move all your data in a single file when switching phones
>
> **Privacy first**
> No account, no personal data collection, no ads, no tracking. All AI inference happens on-device.
> The app only goes online when you choose to download a model or check for updates — it never sends your data.
>
> **About the model**
> First-time use requires a one-time in-app download of the offline AI model (~2.4 GB, Wi-Fi recommended).
> After that the app works fully offline, forever.

---

## 4. 关键词 Keywords（每区 ≤100 字符，逗号分隔、不留空格）

**中国区：**
```
离线翻译,翻译,英语,词典,AI,问答,背单词,单词本,记单词,语音翻译,拍照翻译,中英,学习,离线
```
**海外：**
```
offline translator,translate,english,dictionary,AI,vocabulary,flashcards,SRS,voice,camera,private
```
> 提交前用字符计数核对每行 ≤100（含逗号）。名称/副标题里已出现的词不必再放进关键词。

---

## 5. URL

| 字段 | 值 | 说明 |
|---|---|---|
| **支持 URL Support URL** | https://github.com/chunguangwei/offline-translator | 必填。可后续换成专门支持页 |
| **支持/反馈邮箱 Support email** | chunguangwee@gmail.com | 版权联系人 & bug/反馈接收邮箱；App Review Contact 也用此邮箱 |
| **营销 URL Marketing URL** | （可留空） | 选填 |
| **隐私政策 URL Privacy Policy** | **https://chunguangwei.github.io/offline-translator/privacy/** ✅ 已上线 | **必填** |

---

## 6. App 隐私（App Privacy，数据收集问卷）

**答案：Data Not Collected（不收集任何数据）。**
- 不收集：联系信息、健康、财务、位置、联系人、用户内容、标识符、使用数据、诊断 —— **全部不收集**。
- 无第三方 SDK、无分析、无广告、无追踪（`NSPrivacyTracking = false`）。
- 联网仅用于：①下载离线模型（ModelScope / Hugging Face CDN）②检查更新（GitHub）③拉取启动图
  （jsDelivr / GitHub）。这些是**下载文件**，不上传用户数据，不构成「数据收集」。

> 隐私清单文件 `PrivacyInfo.xcprivacy` 已随本轮加入工程（声明 0 收集 + Required-Reason API 用途）。

---

## 7. 年龄分级 Age Rating

建议 **4+**：内容分级问卷各项选「无 / None」；「无限制网页访问 Unrestricted Web Access」选**否**
（AI 为本地模型，无内置浏览器）。

> 注意：App 含**本地 AI 生成内容**（对话）。无社交/分享/UGC 互传，风险低；若审核就 AI 内容追问，
> 在「审核备注」里说明：内容由设备本地模型生成、不联网、无用户间分享、面向语言学习用途即可。

---

## 8. 导出合规 Export Compliance

`Info.plist` 已设 `ITSAppUsesNonExemptEncryption = false` —— 仅用标准 HTTPS，**豁免**，
每次上传不再弹导出合规问题。无需额外提交。

---

## 9. 审核备注 App Review Notes（**关键，照抄**）

> 提交海外区时用英文版，中国区用中文版。两版内容一致。

### 英文版（海外区 / English）

```
Yiren is a fully offline AI translator and study tool. No account, no sign-in required.

[How to test core features]
1. On first launch, go to Settings → Model Management and download the offline AI model
   (~2.4 GB, please use Wi-Fi; first download may take several minutes).
2. Once the model is active, go to the Translate tab and enter Chinese or English text
   to see offline translation results. The Chat tab lets you converse with the on-device AI.
3. All AI inference runs locally on the device. After the model download, the app works
   fully offline.

[Notes]
- The large model file is inherent to on-device offline AI. To keep the install size small,
  the model is downloaded in-app on demand.
- AI chat content is generated by an on-device model, with no internet connection and no
  user-to-user sharing. It is intended for language learning and translation only.
- If downloading the large model during review is inconvenient, we can provide a review
  build with the model bundled (ready to use out of the box). Please let us know.

No demo account needed (the app has no login).
```

### 中文版（中国区 / 简体中文）

```
本 App 为完全离线的设备端 AI 翻译/学习工具，无需注册、无登录账号。

【如何测试核心功能】
1. 首次启动后进入「设置 → 模型管理」，下载离线 AI 模型（约 2.4 GB，请连 Wi-Fi，
   首次下载约需数分钟）。
2. 下载完成后会自动启用。回到「翻译」页输入中文或英文即可看到离线翻译结果；
   「问答」页可与本地 AI 对话。
3. 全部 AI 推理在设备本地完成，下载完成后断网仍可使用。

【说明】
- 模型文件较大是设备端离线 AI 的必要代价；为不增大安装包，模型在 App 内按需下载。
- AI 对话内容由设备本地模型生成，不联网、无用户间分享，仅用于语言学习与翻译。
- 如审核网络下载不便，我们可提供内置小模型的审核专用构建，请告知。

无演示账号需求（App 无登录）。
```

> **审核构建策略（重要决策）**：审核员未必有耐心下载 2.4GB 模型。强烈建议首次审核用
> `--bundle-model` 随包构建（reviewer 开箱即用），通过审核后再切回按需下载的正式版。
>
> 归档/上传脚本已备好（账号生效后一条命令）：
> - 正式版（不随包模型）：`cd ios && ./archive-appstore.sh`
> - 审核专用（随包 2.4G 模型，reviewer 开箱即用）：`cd ios && ./archive-appstore.sh --bundle-model`
> - 导出配置：`ios/ExportOptions-appstore.plist`（teamID L35RLT89XN，自动签名）。
> - ⚠️ **签名前提**：当前钥匙串只有「Apple Development」「Developer ID」证书，**缺「Apple Distribution」**。
>   归档导出 App Store 包必须先有**付费 Apple Developer Program 账号** + Xcode 登录该账号，
>   自动签名（`-allowProvisioningUpdates`）才能拉到 Distribution 证书与 App Store 描述文件。
> - 上传方式：Transporter.app（推荐，拖拽 ipa 即可），或 `xcrun altool --upload-app`（需 app-专用密码），
>   或 App Store Connect API Key（`xcrun altool --upload-app -apiKey <key> --apiIssuer <issuer>`）。

---

## 10. 截图 Screenshots

App Store Connect 当前要求至少：
- **6.9"（iPhone 16 Pro Max，1320×2868）** —— 必传一组（也覆盖 6.7"）
- **6.5"（iPhone 11 Pro Max / XS Max，1242×2688）** —— 建议补一组
- 若支持 iPad，另需 13" iPad 截图（**确认 App 是否 iPhone-only**，是则不需要）

每组 3–10 张，建议覆盖：翻译页 / 问答页 / 学习（SRS）页 / 单词本详情 / 设置（突出「离线·隐私」）。
可在 iPhone 真机或模拟器截图（模拟器 ⌘S）。中英两套各一组（文案语言对应）。

✅ **已生成（2026-06-15）**：`docs/appstore-screenshots/`（gitignore，不入库）下中英各 5 张，
均 **1320×2868（6.9"，iPhone 17 Pro Max）** —— App Store 当前要求一组 6.9" 即覆盖全部 iPhone 机型：
- 中文：`01_translate_zh.png` ~ `05_settings_zh.png`（translate/learn/history/chat/settings）
- 英文：`01_translate_en.png` ~ `05_settings_en.png`
- 翻译主图为**真实离线结果**（你好，世界 → Hello, world）。如需更丰富画面（带词的单词本、有打卡的学习页），
  可在已备好的模拟器里手动造数据后补拍，或加文字标题做成市场图。

> ⚠️ **海外上架建议**：当前只有 6.9" 一组。若审核员设备为 6.7" 或 6.5"，App Store Connect 可能要求补传。
> 建议至少再补一组 6.5" (1242×2688) 截图，或确认 App Store Connect 已接受 6.9" 单组覆盖。

---

## 11. 提交前待办清单（Checklist）

### 已就位 ✅

- [x] **隐私政策托管** ✅：已发布到 GitHub Pages →
      https://chunguangwei.github.io/offline-translator/privacy/ （源文件 `docs/privacy/index.html`，中英双语）
- [x] **PrivacyInfo.xcprivacy** ✅ 已加入工程并验证进包（`ios/Yiren/PrivacyInfo.xcprivacy`）
- [x] **InfoPlist.strings 本地化** ✅ 已创建 `en.lproj/InfoPlist.strings` + `zh-Hans.lproj/InfoPlist.strings`
      （权限描述按系统语言显示中/英文）
- [x] **截图** ✅ 中英各 5 张 1320×2868 已生成于 `docs/appstore-screenshots/`（详见第 10 节）
- [x] **App 图标** 1024 已就位（Assets.xcassets/AppIcon，无 alpha、无圆角）
- [x] **归档脚本** `archive-appstore.sh` + `ExportOptions-appstore.plist` 已备好
- [x] **导出合规** `ITSAppUsesNonExemptEncryption = false`（豁免）
- [x] **审核备注** 中英双语已备好（第 9 节），海外区用英文版

### 待完成 ⬜

- [ ] **付费开发者账号生效**（个人/公司 Apple Developer Program，$99/年）—— TestFlight 与上架前提
- [ ] **Xcode 登录该 Apple ID**（Settings → Accounts → 添加）—— 自动签名拉取 Distribution 证书的前提
- [ ] **app-专用密码**：在 appleid.apple.com 生成 app-专用密码（用于 Transporter / altool 上传）
- [ ] **Archive + 上传**：账号生效后跑 `cd ios && ./archive-appstore.sh`，再用 Transporter.app 上传 ipa
      （审核专用构建：`./archive-appstore.sh --bundle-model`，模型随包，reviewer 开箱即用）
- [ ] **构建版本号**：首次上传 CURRENT_PROJECT_VERSION=1 即可；若被拒重传需 +1
- [ ] **中国区软著**：中国区提交需「计算机软件著作权登记证书」（约 30 天，可加急）。**海外区不需要**
- [ ] **App Store Connect 填建条目**：
      - 新建 App → 选英语为主要语言 → 填入本文第 1–9 节内容
      - 「价格与可用性」→ 勾选目标上架国家/区域
      - 绑定截图与构建 → 提交审核
- [ ] **补 6.5" 截图**（可选但建议）：若 App Store Connect 要求 6.5" 尺寸，需补拍 1242×2688 一组

---

## 12. What's New（首版可不填；后续更新版本用）

**中文：**
```
首个 App Store 版本：完全离线的 AI 翻译、问答、拍照/语音翻译，单词本 + 间隔重复记忆，
全量备份还原。所有 AI 在设备本地运行，数据不出手机。
```

**English:**
```
First App Store release: fully offline AI translation, Q&A, camera & voice translation,
vocabulary books with spaced-repetition review, and full backup & restore. All AI runs
on-device — your data never leaves your phone.
```
