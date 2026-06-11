import SwiftUI
import UIKit

/// 译文品牌分享卡片：暖色对角渐变底 + 白色圆角卡（原文/译文）+ 品牌署名。
/// `render()` 用 ImageRenderer 出 3x PNG，交给系统分享面板。
struct ShareCardView: View {
    let source: String
    let translated: String

    private var zh: Bool { PromptTemplates.isZhUi }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            VStack(alignment: .leading, spacing: 4) {
                Text(zh ? "译人" : "Yiren")
                    .font(.system(size: 26, weight: .bold))
                    .foregroundStyle(.white)
                Text(zh ? "纯离线推理 · 数据不出手机" : "Fully on-device · Your data never leaves")
                    .font(.system(size: 13))
                    .foregroundStyle(.white.opacity(0.85))
            }
            VStack(alignment: .leading, spacing: 12) {
                Text(source)
                    .font(.system(size: 16))
                    .foregroundStyle(Color(.systemGray))
                Text(translated)
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(Color(white: 0.11))
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(20)
            .background(Color.white, in: RoundedRectangle(cornerRadius: 16))
            Text("github.com/chunguangwei/offline-translator")
                .font(.system(size: 12))
                .foregroundStyle(.white.opacity(0.85))
        }
        .padding(26)
        .frame(width: 360)
        .background(
            LinearGradient(
                colors: [.brandSecondary, .brandPrimary, .brandTertiary],
                startPoint: .topLeading, endPoint: .bottomTrailing
            )
        )
    }

    /// 渲染为高清 UIImage（3x）。
    @MainActor
    static func render(source: String, translated: String) -> UIImage? {
        let renderer = ImageRenderer(content: ShareCardView(source: source, translated: translated))
        renderer.scale = 3
        return renderer.uiImage
    }
}

/// 系统分享面板封装。
struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
