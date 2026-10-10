import SwiftUI
import KairoCore
import KairoUI

// MARK: - Memory

/// What Kairo knows about you to help you better. It can be edited or deleted.
struct MemoryScreen: View {
    @Environment(AppModel.self) private var model
    @State private var state: Loadable<[Memory]> = .loading
    @State private var editing: MemoryDraft?

    /// (API category id, label, symbol). The ids are part of the API and stay as the server defines them.
    static let categories: [(String, LocalizedStringKey, String)] = [
        ("perfil", "About you", "person"), ("preferencias", "Preferences", "heart"), ("personas", "People", "person.2"),
        ("salud", "Health", "cross.case"), ("trabajo", "Work", "briefcase"), ("hogar", "Home", "house"), ("general", "General", "sparkles"),
    ]

    var body: some View {
        LoadableView(state: $state, version: model.version("memory"), load: { try await model.api.memories() }) { memories in
            List {
                Section {
                    Text("What Kairo knows about you to help you better. You can edit or delete it whenever you want.")
                        .font(.kSubheadline).foregroundStyle(KairoColor.muted)
                        .listRowBackground(Color.clear)
                }
                if memories.isEmpty {
                    EmptyStateView(symbol: "brain", title: "No memories yet", message: "Tell Kairo things like \"I'm allergic to penicillin\" or \"my daughter's name is Mia\".")
                        .listRowBackground(Color.clear)
                }
                ForEach(Self.categories, id: \.0) { cat in
                    let items = memories.filter { $0.category == cat.0 }
                    if !items.isEmpty {
                        Section {
                            ForEach(items) { m in
                                Text(m.content).font(.kBody)
                                    .listRowBackground(KairoColor.surface)
                                    .contentShape(Rectangle())
                                    .onTapGesture { editing = MemoryDraft(m) }
                                    .swipeActions {
                                        Button(role: .destructive) {
                                            Task {
                                                await attempt(model, "Forgotten") { try await model.api.deleteMemory(m.id) }
                                                model.bump("memory")
                                            }
                                        } label: { Label("Forget", systemImage: "trash") }
                                    }
                            }
                        } header: {
                            Label(cat.1, systemImage: cat.2).font(.kairo(13, .semibold)).foregroundStyle(KairoColor.muted).textCase(nil)
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .refreshable { model.bump("memory") }
        }
        .navigationTitle("Memory")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { editing = MemoryDraft() } label: { Label("New memory", systemImage: "plus") }
            }
        }
        .sheet(item: $editing) { d in MemoryEditor(draft: d) }
        .kairoBackground()
    }
}

struct MemoryDraft: Identifiable {
    var id = UUID()
    var original: Memory?
    var content = ""
    var category = "general"
    init() {}
    init(_ m: Memory) { original = m; content = m.content; category = m.category }
}

private struct MemoryEditor: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State var draft: MemoryDraft

    var body: some View {
        NavigationStack {
            Form {
                TextField("Fact", text: $draft.content, axis: .vertical).lineLimit(2...6)
                Picker("Category", selection: $draft.category) {
                    ForEach(MemoryScreen.categories, id: \.0) { Label($0.1, systemImage: $0.2).tag($0.0) }
                }
            }
            .scrollContentBackground(.hidden)
            .kairoBackground()
            .navigationTitle(draft.original == nil ? "New memory" : "Edit memory")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(draft.original == nil ? "Add" : "Save") {
                        Task {
                            let text = draft.content.trimmingCharacters(in: .whitespacesAndNewlines)
                            await attempt(model, draft.original == nil ? "Kairo will remember it" : "Updated") {
                                if let m = draft.original { _ = try await model.api.updateMemory(m.id, content: text, category: draft.category) }
                                else { _ = try await model.api.createMemory(text, category: draft.category) }
                            }
                            model.bump("memory")
                            dismiss()
                        }
                    }
                    .disabled(draft.content.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
        .presentationDetents([.medium])
    }
}

// MARK: - Recordings

/// Meetings and long ideas: Kairo transcribes, summarizes and suggests tasks.
struct RecordingsScreen: View {
    @Environment(AppModel.self) private var model
    @State private var state: Loadable<[Recording]> = .loading
    @State private var title = ""

    private var recorder: MeetingRecorder { model.recorder }

    var body: some View {
        LoadableView(state: $state, version: model.version("recording"), load: { try await model.api.recordings() }) { recs in
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    recorderCard
                    if recs.isEmpty {
                        EmptyStateView(symbol: "waveform", title: "No recordings", message: "Your summarized meetings will appear here.")
                    } else {
                        SectionLabel("Previous", count: recs.count)
                        ForEach(recs) { r in RecordingCard(recording: r) }
                    }
                }
                .padding(16)
                .readableWidth()
            }
            .refreshable { model.bump("recording") }
        }
        .navigationTitle("Recordings")
        .kairoBackground()
    }

    private var recorderCard: some View {
        VStack(spacing: 14) {
            if recorder.isRecording, let start = recorder.startedAt {
                HStack(spacing: 10) {
                    Circle().fill(KairoColor.danger).frame(width: 10, height: 10)
                        .phaseAnimator([1.0, 0.3]) { v, p in v.opacity(p) } animation: { _ in .easeInOut(duration: 0.8) }
                    Text(start, style: .timer).font(.kairoMono(28, .medium)).monospacedDigit()
                    Spacer()
                    Text("Recording · \(recorder.segments) parts").font(.kFootnote).foregroundStyle(KairoColor.muted)
                }
                LevelBars(level: recorder.level)
                Button { Task { await recorder.stop(title: title.isEmpty ? nil : title) ; title = ""; model.bump("recording") } } label: {
                    Label("Stop", systemImage: "stop.fill").frame(maxWidth: .infinity)
                }
                .buttonStyle(KairoButtonStyle(kind: .danger, fullWidth: true))
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Record a meeting or a long idea").font(.kHeadline)
                    Text("Kairo transcribes, summarizes and suggests tasks. The audio is deleted once uploaded.").font(.kSubheadline).foregroundStyle(KairoColor.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                TextField("Title (optional)", text: $title)
                    .padding(.horizontal, 14).frame(height: 44)
                    .background(KairoColor.surface2, in: RoundedRectangle(cornerRadius: KairoRadius.control, style: .continuous))
                Button { Task { await recorder.start(title: title) } } label: {
                    Label("Record", systemImage: "record.circle").frame(maxWidth: .infinity)
                }
                .buttonStyle(KairoButtonStyle(kind: .primary, fullWidth: true))
                if let e = recorder.lastError { Text(e).font(.kFootnote).foregroundStyle(KairoColor.danger) }
            }
        }
        .padding(16)
        .background(KairoColor.surface, in: RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous).strokeBorder(recorder.isRecording ? KairoColor.danger.opacity(0.5) : KairoColor.border))
        .animation(KairoMotion.spring, value: recorder.isRecording)
    }
}

/// Microphone level bars.
private struct LevelBars: View {
    var level: Double
    @State private var history: [Double] = Array(repeating: 0, count: 36)

    var body: some View {
        HStack(alignment: .center, spacing: 3) {
            ForEach(history.indices, id: \.self) { i in
                Capsule().fill(KairoColor.accent.opacity(0.35 + history[i] * 0.65))
                    .frame(width: 4, height: max(4, history[i] * 40))
            }
        }
        .frame(height: 42)
        .animation(.easeOut(duration: 0.12), value: history)
        .onChange(of: level) { _, l in
            history.removeFirst()
            history.append(l)
        }
        .accessibilityHidden(true)
    }
}

private struct RecordingCard: View {
    @Environment(AppModel.self) private var model
    var recording: Recording
    @State private var showTranscript = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Text(recording.title.isEmpty ? String(localized: "Recording") : recording.title).font(.kHeadline)
                Spacer()
                Text(KairoDate.duration(recording.durationS)).font(.kMonoSmall).foregroundStyle(KairoColor.faint)
            }
            if let d = KairoDate.parse(recording.createdAt) { Text(KairoDate.humanDateTime(d).capitalizedFirst).font(.kFootnote).foregroundStyle(KairoColor.muted) }
            switch recording.status {
            case "processing", "recording":
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text(recording.status == "recording" ? "Transcribing in parts · \(recording.segments) uploaded" : "Summarizing...").font(.kSubheadline).foregroundStyle(KairoColor.muted)
                }
            case "failed":
                Label(recording.error ?? String(localized: "Couldn't process it"), systemImage: "exclamationmark.triangle").font(.kSubheadline).foregroundStyle(KairoColor.danger)
            default:
                if !recording.summary.isEmpty { MarkdownText(recording.summary, font: .kCallout) }
                if !recording.keyPoints.isEmpty {
                    Text("Key points").font(.kairo(13, .semibold)).foregroundStyle(KairoColor.muted)
                    ForEach(recording.keyPoints, id: \.self) { p in Label(p, systemImage: "circle.fill").labelStyle(BulletLabel()) }
                }
                if !recording.actionItems.isEmpty {
                    Text("Actions").font(.kairo(13, .semibold)).foregroundStyle(KairoColor.muted)
                    ForEach(recording.actionItems, id: \.self) { p in Label(p, systemImage: "checkmark.circle").labelStyle(BulletLabel()) }
                }
                HStack(spacing: 10) {
                    if !recording.actionItems.isEmpty {
                        Button("Create tasks") {
                            Task {
                                do {
                                    let n = try await model.api.recordingTasks(recording.id)
                                    model.show(Toast(title: String(localized: "\(n) tasks created"), destination: .tasks))
                                    model.bump("task")
                                } catch { model.show(Toast(title: String(localized: "Couldn't complete that"), message: error.localizedDescription, symbol: "exclamationmark.triangle")) }
                            }
                        }
                        .buttonStyle(KairoButtonStyle(kind: .secondary))
                    }
                    if recording.noteId != nil {
                        Button("View note") { model.perform("section:notes") }.buttonStyle(KairoButtonStyle(kind: .quiet))
                    }
                    Spacer()
                    if !recording.transcript.isEmpty {
                        Button(showTranscript ? "Hide transcript" : "Show transcript") { withAnimation(KairoMotion.base) { showTranscript.toggle() } }
                            .font(.kairo(13, .medium)).foregroundStyle(KairoColor.accentStrong)
                    }
                }
                if showTranscript {
                    Text(recording.transcript).font(.kFootnote).foregroundStyle(KairoColor.muted).selectableText().transition(.opacity)
                }
            }
        }
        .padding(16)
        .background(KairoColor.surface, in: RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous).strokeBorder(KairoColor.border))
        .contextMenu {
            Button(role: .destructive) {
                Task {
                    await attempt(model, "Recording deleted") { try await model.api.deleteRecording(recording.id) }
                    model.bump("recording")
                }
            } label: { Label("Delete", systemImage: "trash") }
        }
    }
}

private struct BulletLabel: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            configuration.icon.font(.system(size: 6)).foregroundStyle(KairoColor.faint)
            configuration.title.font(.kCallout)
        }
    }
}

// MARK: - Activity

/// Everything Kairo and you changed. Creations and deletions can be undone.
struct ActivityScreen: View {
    @Environment(AppModel.self) private var model
    @State private var state: Loadable<[ActivityEntry]> = .loading

    private var version: Int {
        ["note", "task", "reminder", "list", "event", "memory", "place", "activity"].reduce(0) { $0 + model.version($1) }
    }

    var body: some View {
        LoadableView(state: $state, version: version, load: { try await model.api.activity(limit: 80) }) { entries in
            List {
                Section {
                    Text("Everything Kairo and you changed. Creations and deletions can be undone.")
                        .font(.kSubheadline).foregroundStyle(KairoColor.muted).listRowBackground(Color.clear)
                }
                if entries.isEmpty {
                    EmptyStateView(symbol: "clock.arrow.circlepath", title: "No activity", message: "You'll see every Kairo action here.").listRowBackground(Color.clear)
                }
                ForEach(groups(entries), id: \.0) { g in
                    Section {
                        ForEach(g.1) { a in row(a) }
                    } header: { SectionLabel(g.0) }
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .refreshable { model.bump("activity") }
        }
        .navigationTitle("Activity")
        .kairoBackground()
    }

    private func groups(_ entries: [ActivityEntry]) -> [(String, [ActivityEntry])] {
        var out: [(String, [ActivityEntry])] = []
        for a in entries {
            let key = KairoDate.parse(a.createdAt).map { KairoDate.humanDay($0).capitalizedFirst } ?? ""
            if let i = out.firstIndex(where: { $0.0 == key }) { out[i].1.append(a) } else { out.append((key, [a])) }
        }
        return out
    }

    private func row(_ a: ActivityEntry) -> some View {
        HStack(spacing: 12) {
            Image(systemName: KairoSymbol.entity(a.entityType))
                .font(.system(size: 14, weight: .medium))
                .frame(width: 32, height: 32)
                .background(KairoColor.surface2, in: Circle())
                .foregroundStyle(a.kind == "deleted" ? KairoColor.danger : KairoColor.muted)
            VStack(alignment: .leading, spacing: 2) {
                Text(a.summary).font(.kBody).foregroundStyle(a.undoneAt != nil ? KairoColor.muted : KairoColor.text).strikethrough(a.undoneAt != nil)
                HStack(spacing: 6) {
                    if let d = KairoDate.parse(a.createdAt) { Text(KairoDate.time(d)).font(.kMonoSmall) }
                    Text(a.source == "agent" || a.source == "voice" ? "Kairo" : "You").font(.kCaption)
                }
                .foregroundStyle(KairoColor.faint)
            }
            Spacer()
            if a.undoneAt != nil {
                Text("Undone").font(.kCaption).foregroundStyle(KairoColor.muted)
            } else if a.undoable {
                Button("Undo") {
                    Task {
                        await attempt(model, "Undone") { try await model.api.undo(a.id) }
                        model.bump(a.entityType)
                        model.bump("activity")
                    }
                }
                .font(.kairo(13, .medium)).foregroundStyle(KairoColor.accentStrong).buttonStyle(.borderless)
            }
        }
        .listRowBackground(KairoColor.surface)
    }
}
