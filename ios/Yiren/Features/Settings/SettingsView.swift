import SwiftUI
import UIKit

/// 设置 Tab —— 对应 Android SettingsScreen：主题/推理后端/模型管理/关于与许可。
/// 无「应用更新」：iOS 由 App Store 承担（平台差异）；
/// 应用语言切换走系统级 per-app 语言设置。
struct SettingsView: View {
    @AppStorage("themeMode") private var themeMode = "system"
    @AppStorage("backendPref") private var backendPref = "AUTO"
    @AppStorage("modelSourcePref") private var modelSource = "CN_FIRST"
    @AppStorage("customMirrorBase") private var customMirrorBase = ""
    @ObservedObject private var gemma = GemmaService.shared
    @State private var showModels = false

    private var zh: Bool { PromptTemplates.isZhUi }
    private var version: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
    }

    var body: some View {
        NavigationStack {
            Form {
                Section(zh ? "外观" : "Appearance") {
                    Picker(zh ? "主题" : "Theme", selection: $themeMode) {
                        Text(zh ? "跟随系统" : "System").tag("system")
                        Text(zh ? "浅色" : "Light").tag("light")
                        Text(zh ? "深色" : "Dark").tag("dark")
                    }
                    .pickerStyle(.segmented)
                }

                Section(zh ? "语言" : "Language") {
                    Button(zh ? "应用语言（到系统设置切换）" : "App language (system settings)") {
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    }
                }

                Section(zh ? "模型" : "Model") {
                    Button {
                        showModels = true
                    } label: {
                        HStack {
                            Text(zh ? "模型管理" : "Manage models")
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.caption)
                                .foregroundStyle(.tertiary)
                        }
                    }
                    .foregroundStyle(.primary)

                    Picker(zh ? "下载源" : "Download source", selection: $modelSource) {
                        Text(zh ? "国内优先（魔搭）" : "CN first (ModelScope)").tag("CN_FIRST")
                        Text(zh ? "官方优先（HF）" : "Official first (HF)").tag("OFFICIAL")
                        Text(zh ? "自定义镜像" : "Custom mirror").tag("CUSTOM")
                        Text(zh ? "仅本地" : "Local only").tag("LOCAL_ONLY")
                    }
                    if modelSource == "CUSTOM" {
                        TextField("https://your-mirror.example.com/path/", text: $customMirrorBase)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .keyboardType(.URL)
                            .font(.footnote)
                    }
                }

                Section {
                    Picker(zh ? "推理后端" : "Inference backend", selection: $backendPref) {
                        Text(zh ? "自动" : "Auto").tag("AUTO")
                        Text("GPU").tag("GPU")
                        Text("CPU").tag("CPU")
                    }
                    .pickerStyle(.segmented)
                    // 切换在下次生成时生效（ensureLoaded 检测偏好变更后安全重载，
                    // 经生成门串行，不会拔掉正在生成的引擎）。
                    HStack {
                        Text(zh ? "实际后端" : "Active backend")
                        Spacer()
                        Text(backendStatus)
                            .foregroundStyle(.secondary)
                    }
                    .font(.subheadline)
                } header: {
                    Text(zh ? "推理后端" : "Inference")
                } footer: {
                    Text(zh ? "自动 = GPU 优先，失败回退 CPU。视觉/音频编码器始终走 CPU（稳定优先）。切换后下次生成生效。"
                            : "Auto = GPU first with CPU fallback. Vision/audio encoders always run on CPU. Takes effect on next generation.")
                }

                Section(zh ? "关于与许可" : "About & License") {
                    LabeledContent(zh ? "版本" : "Version", value: "v\(version)")
                    Text(zh ? "版权所有 © 2026 weichunguang，保留所有权利。"
                            : "Copyright © 2026 weichunguang. All Rights Reserved.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Text(zh ? "本软件采用专有许可：可出于个人学习、研究、评估目的使用与修改；任何商业用途须事先获得著作权人书面授权。"
                            : "Proprietary license: free for personal study, research and evaluation. Commercial use requires prior written permission.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Link(zh ? "GitHub 仓库" : "GitHub repository",
                         destination: URL(string: "https://github.com/chunguangwei/offline-translator")!)
                    Text(zh ? "第三方组件：Google LiteRT-LM SDK 与 Gemma 模型（遵循各自原始许可）。"
                            : "Third-party: Google LiteRT-LM SDK and Gemma models (under their own licenses).")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Text(zh ? "所有 AI 推理均在本地完成，绝不上传任何文本、图片、音频或使用行为。联网仅发生在三种情况：下载模型、检查更新、启动时获取启动图与运营信息——这些请求都不包含任何个人数据。"
                            : "All AI inference runs on-device. We never upload your text, images, audio, or usage. Network is used only for downloading models, checking for updates, and fetching the launch splash/promo config — none of these contain any personal data.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle(zh ? "设置" : "Settings")
            .sheet(isPresented: $showModels) { ModelsView() }
        }
    }

    private var backendStatus: String {
        switch gemma.state {
        case .ready: return gemma.activeBackendLabel
        case .loading: return zh ? "加载中…" : "Loading…"
        case .modelMissing: return zh ? "未下载模型" : "No model"
        case .error: return zh ? "加载失败" : "Error"
        case .idle: return zh ? "未加载" : "Idle"
        }
    }
}
