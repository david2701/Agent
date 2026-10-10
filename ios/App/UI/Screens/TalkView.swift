import SwiftUI
import KairoCore
import KairoUI

/// Full-screen voice conversation: the orb, what you said, what Kairo did and its answer.
struct TalkView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.verticalSizeClass) private var vSize
    @State private var typing = false
    @State private var text = ""
    @FocusState private var focused: Bool

    private var voice: VoiceEngine { model.voice }

    var body: some View {
        GeometryReader { geo in
            let wide = geo.size.width > geo.size.height && vSize == .compact
            Group {
                if wide {
                    HStack(spacing: 24) {
                        orb(size: min(geo.size.height * 0.55, 220))
                        conversation
                    }
                } else {
                    VStack(spacing: 18) {
                        Spacer(minLength: 12)
                        orb(size: min(geo.size.width * 0.5, 240))
                        conversation
                    }
                }
            }
            .padding(.horizontal, 20)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .safeAreaInset(edge: .bottom) { controls }
        .overlay(alignment: .topTrailing) {
            Button { voice.cancel(); dismiss() } label: {
                Image(systemName: "xmark").font(.system(size: 15, weight: .semibold)).frame(width: 40, height: 40)
            }
            .buttonStyle(.plain)
            .glassEffect(.regular.interactive(), in: .circle)
            .padding(16)
            .accessibilityLabel("Close")
        }
        .kairoBackground()
        .sensoryFeedback(.impact(weight: .light), trigger: voice.phase)
        .onDisappear { if voice.phase == .listening { voice.cancel() } }
    }

    private func orb(size: CGFloat) -> some View {
        VStack(spacing: 14) {
            KairoOrb(state: voice.orbState, level: voice.level)
                .frame(width: size, height: size)
                .onTapGesture { tapOrb() }
                .accessibilityAddTraits(.isButton)
                .accessibilityHint("Tap to talk or stop")
            Text(verbatim: voice.statusText)
                .font(.kairo(17, .medium))
                .foregroundStyle(voice.errorMessage != nil && voice.phase == .idle ? KairoColor.danger : KairoColor.muted)
                .contentTransition(.opacity)
                .animation(KairoMotion.base, value: voice.statusText)
        }
    }

    private var conversation: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if let t = voice.turn.transcript, !t.isEmpty {
                        Text(verbatim: "“\(t)”")
                            .font(.kairo(19, .medium))
                            .foregroundStyle(KairoColor.text)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                    ForEach(voice.turn.actions) { a in
                        ActionCard(summary: a.running ? a.label : a.summary, entityType: a.entity?.type, ok: a.ok, running: a.running) {
                            if let e = a.entity, let s = AppSection.from(entity: e.type) {
                                model.perform("section:\(s.rawValue)")
                                dismiss()
                            }
                        }
                        .transition(.asymmetric(insertion: .scale(scale: 0.96).combined(with: .opacity), removal: .opacity))
                    }
                    if !voice.turn.text.isEmpty {
                        MarkdownText(voice.turn.text, font: .kairo(17))
                            .transition(.opacity)
                            .id("reply")
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 8)
                .animation(KairoMotion.spring, value: voice.turn.actions.count)
            }
            .scrollIndicators(.hidden)
            .onChange(of: voice.turn.text) { _, _ in withAnimation { proxy.scrollTo("reply", anchor: .bottom) } }
        }
    }

    private var controls: some View {
        HStack(spacing: 12) {
            if typing {
                TextField("Type to Kairo", text: $text, axis: .vertical)
                    .font(.kBody)
                    .lineLimit(1...4)
                    .focused($focused)
                    .submitLabel(.send)
                    .onSubmit(send)
                    .padding(.horizontal, 16)
                    .frame(minHeight: 50)
                    .glassEffect(.regular, in: .capsule)
                Button(action: send) {
                    Image(systemName: "arrow.up").font(.system(size: 17, weight: .bold)).frame(width: 50, height: 50)
                }
                .buttonStyle(.plain)
                .foregroundStyle(KairoColor.onAccent)
                .background(KairoColor.accent, in: Circle())
                .disabled(text.trimmingCharacters(in: .whitespaces).isEmpty)
            } else {
                Button { voice.reset() } label: {
                    Image(systemName: "plus.bubble").font(.system(size: 17, weight: .medium)).frame(width: 50, height: 50)
                }
                .buttonStyle(.plain)
                .glassEffect(.regular.interactive(), in: .circle)
                .accessibilityLabel("New conversation")
                Spacer()
                Button(action: tapOrb) {
                    Image(systemName: voice.phase == .idle ? "mic.fill" : "stop.fill")
                        .font(.system(size: 24, weight: .semibold))
                        .frame(width: 72, height: 72)
                        .contentTransition(.symbolEffect(.replace))
                }
                .buttonStyle(.plain)
                .foregroundStyle(KairoColor.onAccent)
                .background(KairoColor.accent, in: Circle())
                .accessibilityLabel(voice.phase == .idle ? "Talk" : "Stop")
                Spacer()
                Button { withAnimation(KairoMotion.base) { typing = true }; focused = true } label: {
                    Image(systemName: "keyboard").font(.system(size: 17, weight: .medium)).frame(width: 50, height: 50)
                }
                .buttonStyle(.plain)
                .glassEffect(.regular.interactive(), in: .circle)
                .accessibilityLabel("Type")
            }
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 12)
        .frame(maxWidth: 720)
    }

    private func tapOrb() {
        if voice.phase == .idle { Task { await voice.talk() } } else { voice.cancel() }
    }

    private func send() {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        text = ""
        withAnimation(KairoMotion.base) { typing = false }
        Task { await voice.talk(initialText: t) }
    }
}
