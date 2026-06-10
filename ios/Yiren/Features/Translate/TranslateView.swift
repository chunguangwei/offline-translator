import SwiftUI

/// 翻译页 —— 与 Android 翻译 Tab 的结构/暖色风格一致（V1 无语音输入）。
struct TranslateView: View {
    @StateObject private var vm = TranslateViewModel()
    @State private var showModels = false

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
            }
            .scrollDismissesKeyboard(.interactively)
            .background(Color(.systemGroupedBackground))
            .onAppear { vm.refreshModel() }
            .sheet(isPresented: $showModels, onDismiss: { vm.refreshModel() }) {
                ModelsView()
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("译人")
                .font(.largeTitle.weight(.semibold))
            Text("离线 AI 翻译 · 数据不出手机")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .padding(.top, 8)
    }

    /// 模型缺失：顶部内联横幅 + 「去下载」——与 Android 一致，不用弹窗压内容。
    private var missingModelBanner: some View {
        HStack {
            Text("还没有可用的模型")
                .font(.subheadline)
            Spacer()
            Button("去下载") { showModels = true }
                .font(.subheadline.weight(.semibold))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(Color.brandPrimary.opacity(0.12), in: RoundedRectangle(cornerRadius: 14))
    }

    private var sourceCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(vm.sourceIsZh ? "中文" : "English")
                .font(.caption.weight(.semibold))
                .foregroundStyle(Color.brandPrimary)
            TextEditor(text: $vm.input)
                .frame(minHeight: 120)
                .font(.body)
                .scrollContentBackground(.hidden)
                .overlay(alignment: .topLeading) {
                    if vm.input.isEmpty {
                        Text(vm.sourceIsZh ? "输入要翻译的中文…" : "Enter English text…")
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
            Text(vm.sourceIsZh ? "English" : "中文")
                .font(.caption.weight(.semibold))
                .foregroundStyle(Color.brandTertiary)
            Group {
                if vm.loadingModel {
                    Label("正在加载模型，请稍等…", systemImage: "hourglass")
                        .foregroundStyle(.secondary)
                } else if vm.output.isEmpty && !vm.isTranslating {
                    Text("译文将出现在这里")
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
            Text(vm.isTranslating ? "停止" : "翻译")
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
