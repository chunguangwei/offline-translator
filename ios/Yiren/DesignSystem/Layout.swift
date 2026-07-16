import SwiftUI

// iPad 大屏下把主内容居中并限制阅读宽度，避免一行文字过宽。
// iPhone / Compact 尺寸下 maxWidth 会由外层容器决定，本修饰仍保持 .infinity 居中。
enum ContentWidth {
    static let reading: CGFloat = 720
}

extension View {
    func readingWidth(_ maxWidth: CGFloat = ContentWidth.reading) -> some View {
        frame(maxWidth: maxWidth)
            .frame(maxWidth: .infinity)
    }
}
