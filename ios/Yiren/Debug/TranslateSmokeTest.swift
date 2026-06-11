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
            // 长句 + 低温采样，与真实翻译路径完全一致（复现"译出一堆语言"刹不住车问题）。
            let stream = try await gemma.generateStream(
                prompt: PromptTemplates.translate("今天天气很好，我们下午去公园散步，顺便买点水果回来。", fromZh: true),
                sampler: Samplers.precise
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
