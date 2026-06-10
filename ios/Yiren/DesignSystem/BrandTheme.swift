import SwiftUI

/// 品牌暖色 —— 与 Android `core/designsystem/theme/Color.kt` 及 logo 渐变一致。
extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }

    /// 珊瑚 — 主色
    static let brandPrimary = Color(hex: 0xFF6F61)
    /// 琥珀 — 次色
    static let brandSecondary = Color(hex: 0xFFB152)
    /// 品红 — 强调
    static let brandTertiary = Color(hex: 0xC2479B)
}

/// logo 同款暖色对角渐变（启动页/重点视觉用）。
let brandGradient = LinearGradient(
    colors: [.brandSecondary, .brandPrimary, .brandTertiary],
    startPoint: .topLeading,
    endPoint: .bottomTrailing
)
