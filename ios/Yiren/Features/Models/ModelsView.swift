import SwiftUI

/// 模型管理页 —— 列表/下载（双源兜底）/激活/删除，对应 Android ModelsScreen。
struct ModelsView: View {
    @StateObject private var downloader = ModelDownloader()
    @State private var refreshTick = 0 // 下载完/删除后驱动行状态刷新
    @Environment(\.dismiss) private var dismiss

    private let storage = ModelStorage.shared

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
                    Text("模型约 2.6–3.7 GB，建议 Wi-Fi 下载。下载完成后自动启用，推理全程离线。")
                }
            }
            .id(refreshTick)
            .navigationTitle("模型管理")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }
}

private struct ModelRow: View {
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
                    Text("使用中")
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
                        Button("取消", action: onCancel)
                            .font(.caption)
                    }
                }
                .padding(.top, 4)
            case .failed(let message):
                Text("下载失败：\(message)")
                    .font(.caption)
                    .foregroundStyle(.red)
                Button("重试", action: onDownload)
                    .font(.subheadline.weight(.semibold))
            case .idle:
                HStack(spacing: 16) {
                    if isDownloaded || isBundled {
                        if isBundled && !isDownloaded {
                            Text("已内置 · 随包可用")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        if !isActive {
                            Button("启用", action: onActivate)
                                .font(.subheadline.weight(.semibold))
                        }
                        // 只有下载副本可删；包内预置随 App 卸载走。
                        if isDownloaded {
                            Button("删除", role: .destructive, action: onDelete)
                                .font(.subheadline)
                        }
                    } else {
                        Button("下载", action: onDownload)
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
