#if DEBUG
import Foundation
import OSLog

/// 推理冒烟（仅 Debug 构建生效）：
/// `simctl launch <设备> com.offlinetranslator.yiren -autoSmokeTranslate`
/// 启动后自动加载模型并翻译「你好，世界」，结果打到 os_log（category=smoke），
/// 供命令行自动化验证端到端推理 —— 不影响正常使用与发布包。
@MainActor
func runTranslateSmokeIfRequested() {
    guard ProcessInfo.processInfo.arguments.contains("-autoSmokeTranslate") else { return }
    let log = Logger(subsystem: "com.offlinetranslator.yiren", category: "smoke")
    Task {
        log.warning("SMOKE: start")
        let gemma = GemmaService.shared
        switch await gemma.ensureLoaded() {
        case .failure(let failure):
            log.error("SMOKE: LOAD FAILED \(String(describing: failure), privacy: .public)")
            return
        case .success(let model):
            log.warning("SMOKE: loaded \(model.id, privacy: .public)")
        }
        do {
            let stream = try await gemma.generateStream(
                prompt: PromptTemplates.translate("你好，世界", fromZh: true)
            )
            var output = ""
            for try await delta in stream { output += delta }
            log.warning("SMOKE: RESULT=\(output, privacy: .public)")
        } catch {
            log.error("SMOKE: GENERATE FAILED \(String(describing: error), privacy: .public)")
        }
    }
}
#endif
