import SwiftUI

/// 品牌启动页 —— 与 Android SplashScreen 完全对齐：
/// 暖色渐变 + 眨眼 logo 角色 + 轮换标语 + 版本号；6 秒展示、右上角可跳过；
/// 远程配置了启动图（splash.json）则整屏显示远程图。
struct SplashView: View {
    let onFinished: () -> Void
    @StateObject private var remote = SplashRemoteStore.shared
    @State private var remaining = 6
    @State private var tagIndex = 0

    private var zh: Bool { PromptTemplates.isZhUi }
    private var taglines: [String] {
        zh ? ["纯离线推理 · 数据不出手机", "中英互译 · 语音 · 图片问答", "Gemma 多模态本地引擎"]
           : ["Fully on-device · Your data never leaves", "ZH ⇄ EN · Voice · Image Q&A", "Powered by Gemma, locally"]
    }
    private var version: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
    }

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [.brandSecondary, .brandPrimary, .brandTertiary],
                startPoint: .topLeading, endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            if let img = remote.cachedImage {
                // 远程运营图：整屏展示，让图自己说话。
                Image(uiImage: img)
                    .resizable()
                    .scaledToFill()
                    .ignoresSafeArea()
            } else {
                VStack(spacing: 0) {
                    Spacer()
                    BlinkingLogo()
                        .frame(width: 170, height: 170)
                    Spacer().frame(height: 28)
                    Text(zh ? "译人" : "Yiren")
                        .font(.system(size: 40, weight: .bold))
                        .foregroundStyle(.white)
                    Spacer().frame(height: 6)
                    Text(zh ? "离线 AI 翻译与问答" : "Offline AI translation & Q&A")
                        .font(.body)
                        .foregroundStyle(.white.opacity(0.85))
                    Spacer()
                    Text(taglines[tagIndex])
                        .font(.subheadline)
                        .foregroundStyle(.white.opacity(0.92))
                        .id(tagIndex)
                        .transition(.opacity)
                        .padding(.bottom, 88)
                }
            }

            VStack {
                HStack {
                    Spacer()
                    // 跳过胶囊（带剩余秒数）。
                    Button(action: onFinished) {
                        Text(zh ? "跳过 \(remaining)s" : "Skip \(remaining)s")
                            .font(.footnote)
                            .foregroundStyle(.white)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 7)
                            .background(Capsule().fill(.black.opacity(0.22)))
                    }
                    .padding(.top, 12)
                    .padding(.trailing, 16)
                }
                Spacer()
                Text("v\(version)")
                    .font(.caption2)
                    .foregroundStyle(.white.opacity(0.55))
                    .padding(.bottom, 40)
            }
        }
        .task {
            // 倒计时 + 标语轮换。
            for sec in stride(from: 6, to: 0, by: -1) {
                remaining = sec
                if sec % 2 == 0 {
                    withAnimation(.easeInOut(duration: 0.32)) {
                        tagIndex = (tagIndex + 1) % taglines.count
                    }
                }
                try? await Task.sleep(for: .seconds(1))
                if Task.isCancelled { return }
            }
            onFinished()
        }
    }
}

/// 用 logo master SVG 的几何重绘的白色角色（1024 视口）：
/// 头=气泡圆 + 左下尾巴三角，身=肩部双三次曲线；
/// 眼睛 destinationOut 真镂空透出渐变，随机眨眼（30% 连眨两下）。
private struct BlinkingLogo: View {
    @State private var blink: CGFloat = 1
    @State private var bounce: CGFloat = 1

    var body: some View {
        logoCanvas
            .scaleEffect(bounce)
            // 彩蛋：点角色 → 弹一下 + 连眨两下。
            .onTapGesture {
                Task {
                    withAnimation(.spring(response: 0.22, dampingFraction: 0.45)) { bounce = 1.12 }
                    await doBlink()
                    try? await Task.sleep(for: .milliseconds(120))
                    await doBlink()
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.6)) { bounce = 1 }
                }
            }
    }

    private var logoCanvas: some View {
        Canvas { ctx, size in
            let s = min(size.width, size.height) / 1024
            ctx.scaleBy(x: s, y: s)
            ctx.drawLayer { layer in
                // 头：圆心(512,380) r150 + 尾巴三角（顶点都在圆周/原 SVG 点上）。
                var head = Path(ellipseIn: CGRect(x: 512 - 150, y: 380 - 150, width: 300, height: 300))
                head.move(to: CGPoint(x: 408, y: 487))
                head.addLine(to: CGPoint(x: 328, y: 533))
                head.addLine(to: CGPoint(x: 358, y: 451))
                head.closeSubpath()
                layer.fill(head, with: .color(.white))
                // 肩身（原 SVG cubic 原样）。
                var bodyPath = Path()
                bodyPath.move(to: CGPoint(x: 300, y: 858))
                bodyPath.addCurve(to: CGPoint(x: 512, y: 520),
                                  control1: CGPoint(x: 300, y: 622),
                                  control2: CGPoint(x: 392, y: 520))
                bodyPath.addCurve(to: CGPoint(x: 724, y: 858),
                                  control1: CGPoint(x: 632, y: 520),
                                  control2: CGPoint(x: 724, y: 622))
                bodyPath.closeSubpath()
                layer.fill(bodyPath, with: .color(.white))
                // 眼睛：destinationOut 镂空，以眼心为轴 scaleY 眨眼。
                layer.blendMode = .destinationOut
                for cx in [476.0, 548.0] {
                    let r = 26.0
                    let rect = CGRect(x: cx - r, y: 392 - r * blink, width: r * 2, height: r * 2 * blink)
                    layer.fill(Path(ellipseIn: rect), with: .color(.black))
                }
            }
        }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(Double.random(in: 1.8...3.4)))
                if Task.isCancelled { return }
                await doBlink()
                if Double.random(in: 0...1) < 0.3 { // 俏皮连眨
                    try? await Task.sleep(for: .milliseconds(150))
                    await doBlink()
                }
            }
        }
    }

    private func doBlink() async {
        withAnimation(.easeIn(duration: 0.08)) { blink = 0.06 }
        try? await Task.sleep(for: .milliseconds(90))
        withAnimation(.easeOut(duration: 0.13)) { blink = 1 }
        try? await Task.sleep(for: .milliseconds(140))
    }
}
