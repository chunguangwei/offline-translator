import PhotosUI
import SwiftUI

/// 翻译页 —— 与 Android 翻译 Tab 功能一致（文字/语音/拍照/选图翻译）。
struct TranslateView: View {
    @StateObject private var vm = TranslateViewModel()
    @State private var showModels = false
    @State private var photoItem: PhotosPickerItem?
    @State private var showCamera = false
    @State private var shareImage: UIImage?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    header

                    if vm.isModelMissing {
                        missingModelBanner
                    }

                    sourceCard
                    swapRow
                    targetCard
                    actionButton

                    if let err = vm.errorMessage {
                        Text(err)
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                }
                .padding(20)
                .readingWidth()
            }
            .scrollDismissesKeyboard(.interactively)
            // 点输入框外的空白处收起键盘（卡片/按钮自身交互不受影响）。
            .dismissKeyboardOnTap()
            .background(Color(.systemGroupedBackground))
            .onAppear { vm.refreshModel() }
            .sheet(isPresented: $showModels, onDismiss: { vm.refreshModel() }) {
                ModelsView(autoDownload: true)
            }
            .sheet(item: $shareImage) { img in
                ActivityView(items: [img])
            }
            .fullScreenCover(isPresented: $showCamera) {
                CameraPicker { image in
                    showCamera = false
                    if let image { vm.translateImage(image) }
                }
                .ignoresSafeArea()
            }
            .onChange(of: photoItem) { _, item in
                guard let item else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self),
                       let ui = UIImage(data: data) {
                        vm.translateImage(ui)
                    }
                    photoItem = nil
                }
            }
        }
    }

    private var zh: Bool { PromptTemplates.isZhUi }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(zh ? "译人" : "Yiren")
                .font(.largeTitle.weight(.semibold))
            Text(zh ? "离线 AI 翻译 · 数据不出手机" : "Offline AI translation · On-device")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .padding(.top, 8)
    }

    /// 模型缺失：顶部内联横幅 + 「去下载」——与 Android 一致，不用弹窗压内容。
    private var missingModelBanner: some View {
        HStack {
            Text(zh ? "还没有可用的模型" : "No model available yet")
                .font(.subheadline)
            Spacer()
            Button(zh ? "去下载" : "Download") { showModels = true }
                .font(.subheadline.weight(.semibold))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(Color.brandPrimary.opacity(0.12), in: RoundedRectangle(cornerRadius: 14))
    }

    private var sourceCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(vm.sourceIsZh ? "中文" : "English")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color.brandPrimary)
                Spacer()
                // 语音输入：录音 → 离线转写回填输入框（与 Android 翻译页一致）。
                if vm.isTranscribing {
                    ProgressView().controlSize(.small)
                    Text(zh ? "正在转写…" : "Transcribing…")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else if vm.isRecording {
                    Text(zh ? "正在聆听…" : "Listening…")
                        .font(.caption)
                        .foregroundStyle(Color.brandPrimary)
                    Button { Task { await vm.stopVoiceAndFill() } } label: {
                        Image(systemName: "stop.circle.fill")
                            .font(.title3)
                            .foregroundStyle(.red)
                    }
                } else {
                    // 清空输入与译文。
                    if !vm.input.isEmpty {
                        Button { vm.clear() } label: {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundStyle(.tertiary)
                        }
                        .disabled(vm.isTranslating)
                    }
                    // 拍照翻译 / 选图翻译（识别图中文字逐行对照译出）。
                    Button { showCamera = true } label: {
                        Image(systemName: "camera.fill")
                            .foregroundStyle(Color.brandPrimary)
                    }
                    .disabled(vm.isTranslating)
                    PhotosPicker(selection: $photoItem, matching: .images) {
                        Image(systemName: "photo")
                            .foregroundStyle(Color.brandPrimary)
                    }
                    .disabled(vm.isTranslating)
                    Button { Task { await vm.startVoice() } } label: {
                        Image(systemName: "mic.fill")
                            .foregroundStyle(Color.brandPrimary)
                    }
                    .disabled(vm.isTranslating)
                }
            }
            TextEditor(text: $vm.input)
                .frame(minHeight: 120)
                .font(.body)
                .scrollContentBackground(.hidden)
                .overlay(alignment: .topLeading) {
                    if vm.input.isEmpty {
                        Text(vm.sourceIsZh ? (zh ? "输入要翻译的中文…" : "Enter Chinese text…") : (zh ? "输入要翻译的英文…" : "Enter English text…"))
                            .foregroundStyle(.tertiary)
                            .padding(.top, 8)
                            .allowsHitTesting(false)
                    }
                }
        }
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18))
    }

    private var swapRow: some View {
        HStack {
            Spacer()
            Button {
                vm.swapDirection()
            } label: {
                Image(systemName: "arrow.up.arrow.down")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(.white)
                    .padding(10)
                    .background(Circle().fill(Color.brandPrimary))
            }
            Spacer()
        }
    }

    private var targetCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(vm.sourceIsZh ? "English" : "中文")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color.brandTertiary)
                Spacer()
                if !vm.output.isEmpty && !vm.isTranslating {
                    // 品牌分享卡片（渐变卡图 → 系统分享面板）。
                    Button {
                        shareImage = ShareCardView.render(
                            source: vm.input.trimmingCharacters(in: .whitespacesAndNewlines),
                            translated: vm.output.trimmingCharacters(in: .whitespacesAndNewlines)
                        )
                    } label: {
                        Image(systemName: "square.and.arrow.up")
                            .font(.subheadline)
                            .foregroundStyle(Color.brandPrimary)
                    }
                }
            }
            Group {
                if vm.loadingModel {
                    Label(zh ? "正在加载模型，请稍等…" : "Loading model, please wait…", systemImage: "hourglass")
                        .foregroundStyle(.secondary)
                } else if vm.output.isEmpty && !vm.isTranslating {
                    Text(zh ? "译文将出现在这里" : "Translation appears here")
                        .foregroundStyle(.tertiary)
                } else {
                    Text(vm.output)
                        .textSelection(.enabled)
                }
            }
            .font(.body)
            .frame(minHeight: 100, alignment: .topLeading)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18))
    }

    private var actionButton: some View {
        Button {
            if vm.isTranslating { vm.stop() } else { vm.translate() }
        } label: {
            Text(vm.isTranslating ? (zh ? "停止" : "Stop") : (zh ? "翻译" : "Translate"))
                .font(.headline)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(
                    RoundedRectangle(cornerRadius: 16)
                        .fill(vm.isTranslating ? Color.brandTertiary : Color.brandPrimary)
                )
                .foregroundStyle(.white)
        }
        .disabled(!vm.isTranslating && vm.input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
    }
}
