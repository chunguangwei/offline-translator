import SwiftUI
import SwiftData
import UIKit
import UniformTypeIdentifiers

/// 设置 Tab —— 对应 Android SettingsScreen：主题/推理后端/模型管理/关于与许可。
/// 无「应用更新」：iOS 由 App Store 承担（平台差异）；
/// 应用语言切换走系统级 per-app 语言设置。
struct SettingsView: View {
    @AppStorage("themeMode") private var themeMode = "system"
    @AppStorage("backendPref") private var backendPref = "AUTO"
    @AppStorage("modelSourcePref") private var modelSource = "CN_FIRST"
    @AppStorage("customMirrorBase") private var customMirrorBase = ""
    @AppStorage("reminderEnabled") private var reminderEnabled = false
    @AppStorage("reminderHour") private var reminderHour = 20
    @AppStorage("reminderMinute") private var reminderMinute = 0
    @ObservedObject private var gemma = GemmaService.shared
    @Environment(\.modelContext) private var context
    @State private var showModels = false
    // 备份与还原
    @State private var exportURL: URL?
    @State private var showImporter = false
    @State private var restoreAlert = false
    @State private var restoreMessage = ""

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

                Section {
                    Toggle(zh ? "每日复习提醒" : "Daily review reminder", isOn: Binding(
                        get: { reminderEnabled },
                        set: { on in
                            reminderEnabled = on
                            if on {
                                ReviewReminder.enable(hour: reminderHour, minute: reminderMinute)
                            } else {
                                ReviewReminder.disable()
                            }
                        }))
                    if reminderEnabled {
                        DatePicker(zh ? "提醒时间" : "Reminder time",
                                   selection: Binding(
                                     get: {
                                        var c = DateComponents(); c.hour = reminderHour; c.minute = reminderMinute
                                        return Calendar.current.date(from: c) ?? Date()
                                     },
                                     set: { d in
                                        let c = Calendar.current.dateComponents([.hour, .minute], from: d)
                                        reminderHour = c.hour ?? 20; reminderMinute = c.minute ?? 0
                                        ReviewReminder.enable(hour: reminderHour, minute: reminderMinute) // reschedule
                                     }),
                                   displayedComponents: .hourAndMinute)
                    }
                } header: {
                    Text(zh ? "每日复习提醒" : "Daily review")
                } footer: {
                    Text(zh ? "到点提醒你复习到期单词（纯本地，不联网）"
                            : "Local reminder to review due words (offline)")
                }

                Section {
                    Button {
                        prepareExport()
                    } label: {
                        Label(zh ? "导出备份" : "Export backup", systemImage: "square.and.arrow.up")
                    }
                    .foregroundStyle(.primary)

                    Button {
                        showImporter = true
                    } label: {
                        Label(zh ? "导入还原" : "Import & restore", systemImage: "square.and.arrow.down")
                    }
                    .foregroundStyle(.primary)
                } header: {
                    Text(zh ? "备份与还原" : "Backup & Restore")
                } footer: {
                    Text(zh ? "把单词本、学习进度、翻译/问答与设置导出为文件，换手机后导入即可恢复（不含模型文件）。"
                            : "Export word books, study progress, translations/chats and settings to a file; import on a new phone to restore (model files not included).")
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
            .sheet(item: $exportURL) { url in
                ActivityView(items: [url])
            }
            .fileImporter(isPresented: $showImporter,
                          allowedContentTypes: [.json],
                          allowsMultipleSelection: false) { result in
                handleImport(result)
            }
            .alert(zh ? "还原" : "Restore", isPresented: $restoreAlert) {
                Button(zh ? "好" : "OK", role: .cancel) {}
            } message: {
                Text(restoreMessage)
            }
        }
    }

    // 导出：构建 JSON → 写入临时文件 → 分享。
    private func prepareExport() {
        let backup = BackupIO.buildBackup(context)
        let data = BackupCodec.encode(backup)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("译人备份.json")
        try? data.write(to: url, options: .atomic)
        exportURL = url
    }

    // 导入：读取（处理安全作用域 URL）→ 还原 → 弹结果。
    private func handleImport(_ result: Result<[URL], Error>) {
        switch result {
        case .failure:
            restoreMessage = zh ? "无法读取所选文件" : "Could not read the selected file"
            restoreAlert = true
        case .success(let urls):
            guard let url = urls.first else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard let data = try? Data(contentsOf: url) else {
                restoreMessage = zh ? "无法读取所选文件" : "Could not read the selected file"
                restoreAlert = true
                return
            }
            switch BackupIO.restore(context, json: data) {
            case .failure(let msg):
                restoreMessage = msg
            case .success(let b, let e, let t, let c):
                restoreMessage = zh
                    ? "已导入：单词本 \(b)、词条 \(e)、翻译/收藏 \(t)、问答 \(c)"
                    : "Imported: \(b) books, \(e) entries, \(t) translations, \(c) chats"
            }
            restoreAlert = true
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
