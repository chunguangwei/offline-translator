# 译人 iOS V1（翻译先行）设计规格

> 2026-06-11 与用户对齐：V1 范围=翻译先行（推荐项）；签名=免费 Apple 账号真机调试，
> 上架前升级付费账号。iOS 与 Android 保持功能/UI 一致是既定要求，分阶段达成。

## 目标

iOS 版译人第一个可装机验收的包：**LiteRT-LM 跑通 + 模型下载 + 中英文本翻译可用**。
问答/历史/设置/启动页在后续阶段对齐 Android（参照 `2026-06-09-yiren-restructure-design.md`）。

## 地面真相（2026-06-11 核实）

- 本机 Xcode 26.5 + iPhone 17 系列模拟器 → **Claude 可自行编译/冒烟**，真机验收交用户。
- 复用 `ImagePilot/ios/LiteRTLM/`：Google 官方 LiteRT-LM Swift 封装（Apache 2.0，
  `Engine` actor / `EngineConfig` / `Conversation.sendMessageStream → AsyncThrowingStream` /
  `cancel()`，与 Android SDK API 一一对应）+ `CLiteRTLM.xcframework`（77MB，
  ios-arm64 真机 + ios-arm64-simulator 双切片）。仅依赖 Foundation/OSLog，无 CocoaPods。
- 旧备忘"iOS 已交付/无 Xcode"均为幻觉残留，已证伪并清理。

## 架构

- **SwiftUI + MVVM**（`@Observable`/ObservableObject + async/await），单 App target，无三方依赖。
- 目录：仓库根 `ios/`，xcodegen `project.yml` 声明式工程（.xcodeproj 不入库，随手可再生）。
- `CLiteRTLM.xcframework` **直接入库**（单文件均 <100MB，仓库自包含、CI 可构建）。
- 模块对应 Android 包结构：
  | iOS | 对应 Android |
  | --- | --- |
  | `Engine/GemmaEngine.swift` | `engine/llm/GemmaEngine.kt`（加载/卸载/流式生成/取消，GPU→CPU 兜底） |
  | `Engine/PromptTemplates.swift` | `engine/llm/PromptTemplates.kt`（同一套 Gemma 4 模板/停止词） |
  | `Models/ModelRegistry.swift` | `core/data/model/ModelRegistry.kt`（同 E2B/E4B、同 URL 双源） |
  | `Models/ModelDownloader.swift` | 下载到 App Support/models/，hf-mirror→hf 逐源重试 |
  | `Features/Translate/` | 翻译页（中英互换/流式译文/停止/模型缺失横幅/加载提示） |
  | `Shell/` | TabView 4-Tab（翻译真实，问答/历史/设置 V1 占位） |
  | `DesignSystem/` | 品牌暖色（#FF6F61/#FFB152/#C2479B），贴 logo 不照搬系统默认 |

## V1 范围（验收口径：装机后"你好世界→Hello world"流式出结果）

1. 4-Tab 壳（翻译真实，其余占位页导航成立）
2. 模型管理：列表/下载（双源兜底、进度、删除、激活），存 Application Support
3. 翻译：方向切换、流式输出、停止、模型缺失内联横幅、首载"正在加载模型"提示
4. 暖色品牌主题（浅/深色）

**非目标（后续阶段）**：语音 ASR、图片问答、会话+上下文压缩、翻译历史、启动页（splash.json 协议已双端通用）、应用内更新。

## App Store 合规预留（上架时落地）

- LaunchScreen 必须静态（纯色渐变+静态 logo）；眨眼动画放 App 内启动视图（后续阶段）。
- 隐私标签：不收集任何数据（全离线推理）——卖点即合规点。
- 大模型下载：明示体积、建议 Wi-Fi；模型=数据资产非可执行代码，不违反 2.5.2。
- 生成式 AI 年龄分级（预计 17+）提交时按当时审核口径定。
- 免费账号签名仅限自装调试（7 天有效期）；TestFlight/上架需付费账号。

## 设备门槛

iOS 17+；E2B 模型建议 6GB RAM（iPhone 13 Pro / 14 Pro 及之后）。模拟器（arm64）可开发验证。

## 风险

- xcframework 与最新 Xcode/iOS SDK 的 ABI 兼容性 → 第一步先编译冒烟，有问题早暴露。
- 免费账号无法用某些 entitlement（V1 不需要）；7 天签名过期需重装属已知约束。
