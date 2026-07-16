import SwiftUI

/// 模型管理页 —— 列表/下载（双源兜底）/激活/删除，对应 Android ModelsScreen。
struct ModelsView: View {
    private var zh: Bool { PromptTemplates.isZhUi }
    // 共享单例：background session identifier 全 App 唯一，且锁屏/重启后能接回任务。
    @ObservedObject private var downloader = ModelDownloader.shared
    @State private var refreshTick = 0 // 下载完/删除后驱动行状态刷新
    @Environment(\.dismiss) private var dismiss

    private let storage = ModelStorage.shared
    /// 从「去下载」入口传入：打开页面后自动开始下载默认模型。
    var autoDownload: Bool = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(ModelRegistry.all) { model in
                        ModelRow(
                            model: model,
                            phase: downloader.phase(for: model),
                            isDownloaded: storage.isDownloaded(model),
                            isBundled: storage.isBundled(model),
                            isActive: storage.activeModelId == model.id,
                            onDownload: { downloader.download(model) },
                            onCancel: { downloader.cancel(model) },
                            onActivate: {
                                storage.activeModelId = model.id
                                refreshTick += 1
                            },
                            onDelete: {
                                storage.delete(model)
                                refreshTick += 1
                            }
                        )
                    }
                } footer: {
                    Text(zh ? "模型约 2.4–3.4 GB，建议 Wi-Fi 下载。下载完成后自动启用，推理全程离线。" : "Models are 2.4–3.4 GB; Wi-Fi recommended. Auto-activated after download; inference is fully offline.")
                }
            }
            .id(refreshTick)
            .navigationTitle(zh ? "模型管理" : "Models")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(zh ? "完成" : "Done") { dismiss() }
                }
            }
            .onAppear {
                if autoDownload {
                    let model = ModelRegistry.defaultModel
                    if !storage.isDownloaded(model) && !storage.isBundled(model) && !downloader.isDownloading(model) {
                        downloader.download(model)
                    }
                }
            }
        }
    }
}

private struct ModelRow: View {
    private var zh: Bool { PromptTemplates.isZhUi }
    let model: ModelInfo
    let phase: ModelDownloader.Phase
    let isDownloaded: Bool
    /// App 包内预置（开发期模型随包），无需下载、不可删除。
    let isBundled: Bool
    let isActive: Bool
    let onDownload: () -> Void
    let onCancel: () -> Void
    let onActivate: () -> Void
    let onDelete: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(model.displayName).font(.headline)
                if isActive {
                    Text(zh ? "使用中" : "Active")
                        .font(.caption2.weight(.semibold))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Capsule().fill(Color.brandPrimary.opacity(0.15)))
                        .foregroundStyle(Color.brandPrimary)
                }
            }
            Text(model.summary)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(ByteCountFormatter.string(fromByteCount: model.sizeBytes, countStyle: .file))
                .font(.caption2)
                .foregroundStyle(.tertiary)

            switch phase {
            case .downloading(let fraction, let downloaded, let total):
                VStack(alignment: .leading, spacing: 4) {
                    ProgressView(value: fraction)
                        .tint(Color.brandPrimary)
                    HStack {
                        Text("\(ByteCountFormatter.string(fromByteCount: downloaded, countStyle: .file)) / \(ByteCountFormatter.string(fromByteCount: total, countStyle: .file))")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                        Spacer()
                        Button(zh ? "取消" : "Cancel", action: onCancel)
                            .font(.caption)
                    }
                }
                .padding(.top, 4)
            case .failed(let message):
                Text((zh ? "下载失败：" : "Download failed: ") + message)
                    .font(.caption)
                    .foregroundStyle(.red)
                Button(zh ? "重试" : "Retry", action: onDownload)
                    .font(.subheadline.weight(.semibold))
            case .idle:
                HStack(spacing: 16) {
                    if isDownloaded || isBundled {
                        if isBundled && !isDownloaded {
                            Text(zh ? "已内置 · 随包可用" : "Bundled · ready")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        if !isActive {
                            Button(zh ? "启用" : "Activate", action: onActivate)
                                .font(.subheadline.weight(.semibold))
                        }
                        // 只有下载副本可删；包内预置随 App 卸载走。
                        if isDownloaded {
                            Button(zh ? "删除" : "Delete", role: .destructive, action: onDelete)
                                .font(.subheadline)
                        }
                    } else {
                        Button(zh ? "下载" : "Download", action: onDownload)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Color.brandPrimary)
                    }
                }
                .buttonStyle(.borderless)
                .padding(.top, 2)
            }
        }
        .padding(.vertical, 6)
    }
}
