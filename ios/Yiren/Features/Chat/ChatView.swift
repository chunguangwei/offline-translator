import PhotosUI
import SwiftUI

/// 问答页 —— 对应 Android ChatScreen：会话抽屉/图片问答/语音输入/流式气泡。
struct ChatView: View {
    @StateObject private var vm = ChatViewModel()
    @State private var input = ""
    @State private var showSessions = false
    @State private var photoItem: PhotosPickerItem?
    /// 全屏图片预览（UIImage 或文件路径）。
    @State private var previewImage: UIImage?
    /// 驱动消息列表刷新（SwiftData 手动 fetch 模式）。
    @State private var refreshTick = 0

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                messageList
                if let err = vm.errorMessage {
                    Text(err)
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 2)
                }
                if vm.isRecording || vm.isTranscribing {
                    voiceIndicator
                }
                if let img = vm.attachedImage {
                    attachedPreview(img)
                }
                inputBar
            }
            .navigationTitle(zh ? "问答" : "Q&A")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { showSessions = true } label: { Image(systemName: "clock.arrow.circlepath") }
                    Button {
                        vm.startNewSession()
                        refreshTick += 1
                    } label: { Image(systemName: "plus") }
                }
            }
            .sheet(isPresented: $showSessions) { sessionsSheet }
            .fullScreenCover(item: $previewImage) { img in
                FullImageViewer(image: img) { previewImage = nil }
            }
            .onChange(of: photoItem) { _, item in
                guard let item else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self),
                       let ui = UIImage(data: data) {
                        vm.attachImage(ui)
                    }
                    photoItem = nil
                }
            }
        }
    }

    private var zh: Bool { PromptTemplates.isZhUi }

    // MARK: 消息列表（贴底滚动）

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 8) {
                    ForEach(vm.messages, id: \.persistentModelID) { msg in
                        MessageBubble(msg: msg) { img in previewImage = img }
                    }
                    if vm.isGenerating || !vm.streamingContent.isEmpty {
                        StreamingBubble(content: vm.streamingContent)
                    }
                    Color.clear.frame(height: 8).id("bottom")
                }
                .padding(.horizontal, 12)
                .padding(.top, 4)
            }
            // 下滑跟手收起键盘 + 点消息区空白收起（气泡图等子视图点击不受影响）。
            .scrollDismissesKeyboard(.interactively)
            .dismissKeyboardOnTap()
            .onChange(of: vm.streamingContent) { _, _ in
                proxy.scrollTo("bottom", anchor: .bottom)
            }
            .onChange(of: vm.messages.count) { _, _ in
                proxy.scrollTo("bottom", anchor: .bottom)
            }
            .onAppear { proxy.scrollTo("bottom", anchor: .bottom) }
        }
    }

    // MARK: 语音指示

    private var voiceIndicator: some View {
        HStack(spacing: 4) {
            if vm.isTranscribing {
                ProgressView().controlSize(.small)
                Text(zh ? "正在转写…" : "Transcribing…")
                    .font(.caption)
                    .foregroundStyle(Color.brandPrimary)
            } else {
                Image(systemName: "waveform")
                    .foregroundStyle(Color.brandPrimary)
                Text(zh ? "正在聆听…" : "Listening…")
                    .font(.caption)
                    .foregroundStyle(Color.brandPrimary)
                WaveBars(amplitudes: AudioRecorder.shared.amplitudes)
                    .frame(height: 22)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
    }

    private func attachedPreview(_ img: UIImage) -> some View {
        HStack {
            Image(uiImage: img)
                .resizable()
                .scaledToFill()
                .frame(width: 56, height: 56)
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .onTapGesture { previewImage = img }
            Button { vm.clearImage() } label: {
                Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
    }

    // MARK: 输入条

    private var inputBar: some View {
        HStack(spacing: 8) {
            if !vm.isGenerating && !vm.isRecording && !vm.isTranscribing {
                PhotosPicker(selection: $photoItem, matching: .images) {
                    Image(systemName: "photo.badge.plus")
                        .foregroundStyle(Color.brandPrimary)
                }
            }
            TextField(zh ? "问点什么，或上传图片…" : "Ask anything, or attach an image…", text: $input, axis: .vertical)
                .lineLimit(1...4)
                .textFieldStyle(.plain)
            if vm.isGenerating {
                Button {
                    vm.stop()
                } label: {
                    Image(systemName: "stop.circle.fill")
                        .font(.title2)
                        .foregroundStyle(Color.brandPrimary)
                }
            } else if vm.isTranscribing {
                ProgressView().controlSize(.small)
            } else if vm.isRecording {
                Button {
                    Task {
                        let text = await vm.stopVoiceAndTranscribe()
                        if !text.isEmpty { input = text } // 回填输入框，用户确认再发
                    }
                } label: {
                    Image(systemName: "stop.circle.fill")
                        .font(.title2)
                        .foregroundStyle(.red)
                }
            } else {
                // 麦克风空闲时始终显示（含已附图时）。
                Button { Task { await vm.startVoice() } } label: {
                    Image(systemName: "mic.fill").foregroundStyle(Color.brandPrimary)
                }
                if !input.trimmingCharacters(in: .whitespaces).isEmpty || vm.attachedImage != nil {
                    Button {
                        let text = input
                        input = ""
                        vm.send(text)
                    } label: {
                        Image(systemName: "arrow.up.circle.fill")
                            .font(.title2)
                            .foregroundStyle(Color.brandPrimary)
                    }
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 24))
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
    }

    // MARK: 会话抽屉

    private var sessionsSheet: some View {
        NavigationStack {
            List {
                let list = vm.sessions()
                if list.isEmpty {
                    Text(zh ? "还没有会话" : "No sessions yet")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(list, id: \.id) { s in
                        Button {
                            vm.openSession(s.id)
                            refreshTick += 1
                            showSessions = false
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(s.title.isEmpty ? (zh ? "未命名会话" : "Untitled") : s.title)
                                    .foregroundStyle(s.id == vm.sessionId ? Color.brandPrimary : .primary)
                                    .fontWeight(s.id == vm.sessionId ? .semibold : .regular)
                                Text(s.updatedAt.formatted(date: .numeric, time: .shortened))
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .swipeActions {
                            Button(role: .destructive) {
                                vm.deleteSession(s.id)
                                refreshTick += 1
                            } label: { Label(zh ? "删除" : "Delete", systemImage: "trash") }
                        }
                    }
                }
            }
            .navigationTitle(zh ? "会话" : "Sessions")
            .navigationBarTitleDisplayMode(.inline)
            .presentationDetents([.medium, .large])
        }
    }
}

// MARK: - 组件

extension UIImage: @retroactive Identifiable {
    public var id: ObjectIdentifier { ObjectIdentifier(self) }
}

/// 气泡图片解码缓存（路径 → UIImage）：流式重渲染高频，避免每帧磁盘解码。
private let bubbleImageCache = NSCache<NSString, UIImage>()

private func cachedBubbleImage(_ path: String) -> UIImage? {
    if let hit = bubbleImageCache.object(forKey: path as NSString) { return hit }
    guard let img = UIImage(contentsOfFile: path) else { return nil }
    bubbleImageCache.setObject(img, forKey: path as NSString)
    return img
}

private struct MessageBubble: View {
    let msg: ChatMessage
    let onImageTap: (UIImage) -> Void

    var body: some View {
        HStack {
            if msg.role == "user" { Spacer(minLength: 48) }
            VStack(alignment: msg.role == "user" ? .trailing : .leading, spacing: 6) {
                if let path = msg.imagePath, let ui = cachedBubbleImage(path) {
                    Image(uiImage: ui)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: 200, maxHeight: 240)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .onTapGesture { onImageTap(ui) }
                }
                if !msg.content.isEmpty {
                    Text(rendered(msg.content))
                        .textSelection(.enabled)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(
                msg.role == "user" ? Color.brandPrimary : Color(.secondarySystemGroupedBackground),
                in: RoundedRectangle(cornerRadius: 16)
            )
            .foregroundStyle(msg.role == "user" ? .white : Color.primary)
            if msg.role != "user" { Spacer(minLength: 48) }
        }
    }

    private func rendered(_ s: String) -> AttributedString {
        (try? AttributedString(
            markdown: s,
            options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        )) ?? AttributedString(s)
    }
}

private struct StreamingBubble: View {
    let content: String

    var body: some View {
        HStack {
            Group {
                if content.isEmpty {
                    ProgressView().controlSize(.small)
                } else {
                    Text(content)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
            Spacer(minLength: 48)
        }
    }
}

/// 全屏图片预览：暗底适配屏幕，点任意处关闭。
struct FullImageViewer: View {
    let image: UIImage
    let onDismiss: () -> Void

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.opacity(0.95).ignoresSafeArea()
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(8)
            Button { onDismiss() } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.title)
                    .foregroundStyle(.white.opacity(0.9))
            }
            .padding(.top, 8)
            .padding(.trailing, 16)
        }
        .onTapGesture { onDismiss() }
    }
}

/// 简易波形条。
private struct WaveBars: View {
    let amplitudes: [Float]

    var body: some View {
        HStack(alignment: .center, spacing: 2) {
            ForEach(Array(amplitudes.suffix(24).enumerated()), id: \.offset) { _, a in
                Capsule()
                    .fill(Color.brandPrimary)
                    .frame(width: 3, height: max(3, CGFloat(a) * 22))
            }
        }
    }
}
