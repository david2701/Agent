import SwiftUI
import KairoCore

// MARK: - Icons by entity type (SF Symbols)

public enum KairoSymbol {
    public static func entity(_ type: String) -> String {
        switch type {
        case "note": "note.text"
        case "task": "checkmark.circle"
        case "reminder": "bell"
        case "list": "checklist"
        case "event": "calendar"
        case "memory": "brain"
        case "place": "mappin.and.ellipse"
        case "project": "folder"
        case "recording": "waveform"
        case "family": "house"
        default: "sparkles"
        }
    }

    public static func notice(_ kind: String) -> String {
        switch kind {
        case "assigned": "person.crop.circle.badge.checkmark"
        case "reminder": "bell.badge"
        case "message": "bubble.left"
        case "updated": "arrow.triangle.2.circlepath"
        default: "house"
        }
    }
}

// MARK: - Agent action card ("Note saved: Wifi")

public struct ActionCard: View {
    public var summary: String
    public var entityType: String?
    public var ok: Bool
    public var running: Bool
    public var onOpen: (() -> Void)?

    public init(summary: String, entityType: String?, ok: Bool = true, running: Bool = false, onOpen: (() -> Void)? = nil) {
        self.summary = summary; self.entityType = entityType; self.ok = ok; self.running = running; self.onOpen = onOpen
    }

    public var body: some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 8, style: .continuous).fill(KairoColor.surface2)
                if running {
                    ProgressView().controlSize(.small).tint(KairoColor.accent)
                } else {
                    Image(systemName: ok ? KairoSymbol.entity(entityType ?? "") : "exclamationmark.triangle")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(ok ? KairoColor.text : KairoColor.danger)
                        .contentTransition(.symbolEffect(.replace))
                }
            }
            .frame(width: 34, height: 34)
            Text(summary)
                .font(.kCallout)
                .foregroundStyle(ok ? KairoColor.text : KairoColor.danger)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
            if onOpen != nil, ok, !running {
                Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold)).foregroundStyle(KairoColor.faint)
            }
        }
        .padding(10)
        .background(KairoColor.surface, in: RoundedRectangle(cornerRadius: KairoRadius.control + 2, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: KairoRadius.control + 2, style: .continuous).strokeBorder(KairoColor.border))
        .contentShape(Rectangle())
        .onTapGesture { if ok, !running { onOpen?() } }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(onOpen != nil ? .isButton : [])
    }
}

// MARK: - Family

/// "Family" (with the little house) or "Valentina's".
public struct SharedBadge: View {
    public var owner: String?
    public var compact: Bool

    public init(owner: String? = nil, compact: Bool = false) {
        self.owner = owner
        self.compact = compact
    }

    public var body: some View {
        HStack(spacing: 4) {
            Image(systemName: "house").font(.system(size: compact ? 10 : 11, weight: .semibold))
            if !compact { label.font(.kCaption) }
        }
        .foregroundStyle(KairoColor.muted)
        .padding(.horizontal, compact ? 5 : 7)
        .padding(.vertical, 3)
        .background(KairoColor.surface2, in: Capsule())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(owner.map { String(localized: "Family, \($0)'s", bundle: .module) } ?? String(localized: "Family", bundle: .module))
    }

    private var label: Text {
        guard let owner else { return Text("Family", bundle: .module) }
        return Text(verbatim: String(localized: "\(owner)'s", bundle: .module))
    }
}

/// Initials in a neutral circle (no new colors).
public struct MemberAvatar: View {
    public var initials: String
    public var size: CGFloat
    public var highlighted: Bool

    public init(initials: String, size: CGFloat = 32, highlighted: Bool = false) {
        self.initials = initials; self.size = size; self.highlighted = highlighted
    }

    public var body: some View {
        Text(initials.isEmpty ? "?" : initials)
            .font(.kairo(size * 0.4, .semibold))
            .foregroundStyle(highlighted ? KairoColor.onAccent : KairoColor.text)
            .frame(width: size, height: size)
            .background(highlighted ? KairoColor.accent : KairoColor.surface2, in: Circle())
            .overlay(Circle().strokeBorder(KairoColor.border.opacity(highlighted ? 0 : 1)))
    }
}

// MARK: - States

public struct EmptyStateView: View {
    public var symbol: String
    public var title: Text
    public var message: Text
    public var actionTitle: Text?
    public var action: (() -> Void)?

    /// Texts are looked up in the main bundle (the app's own string catalog).
    public init(symbol: String, title: LocalizedStringKey, message: LocalizedStringKey, actionTitle: LocalizedStringKey? = nil, action: (() -> Void)? = nil) {
        self.init(symbol: symbol, title: Text(title), message: Text(message), actionTitle: actionTitle.map { Text($0) }, action: action)
    }

    public init(symbol: String, title: Text, message: Text, actionTitle: Text? = nil, action: (() -> Void)? = nil) {
        self.symbol = symbol; self.title = title; self.message = message; self.actionTitle = actionTitle; self.action = action
    }

    public var body: some View {
        VStack(spacing: 10) {
            Image(systemName: symbol)
                .font(.system(size: 30, weight: .regular))
                .foregroundStyle(KairoColor.faint)
                .symbolEffect(.bounce, options: .nonRepeating)
                .padding(.bottom, 4)
            title.font(.kHeadline).foregroundStyle(KairoColor.text)
            message.font(.kSubheadline).foregroundStyle(KairoColor.muted).multilineTextAlignment(.center)
            if let actionTitle, let action {
                Button(action: action) { actionTitle }
                    .buttonStyle(KairoButtonStyle(kind: .secondary))
                    .padding(.top, 6)
            }
        }
        .frame(maxWidth: 360)
        .padding(32)
        .frame(maxWidth: .infinity)
    }
}

public struct ErrorStateView: View {
    public var message: String
    public var retry: () -> Void

    public init(_ message: String, retry: @escaping () -> Void) {
        self.message = message
        self.retry = retry
    }

    public var body: some View {
        EmptyStateView(symbol: "wifi.exclamationmark", title: Text("Couldn't load", bundle: .module), message: Text(verbatim: message),
                       actionTitle: Text("Try again", bundle: .module), action: retry)
    }
}

/// Gray blocks while loading (the sizes of the real content are respected).
public struct SkeletonRows: View {
    public var count: Int
    @State private var pulse = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    public init(count: Int = 5) { self.count = count }

    public var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            ForEach(0..<count, id: \.self) { i in
                VStack(alignment: .leading, spacing: 8) {
                    RoundedRectangle(cornerRadius: 5).fill(KairoColor.surface2).frame(width: [220, 180, 260, 150, 200][i % 5], height: 13)
                    RoundedRectangle(cornerRadius: 5).fill(KairoColor.surface2).frame(width: [120, 90, 140, 100, 80][i % 5], height: 10)
                }
            }
        }
        .opacity(pulse ? 0.55 : 1)
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) { pulse = true }
        }
        .padding(.vertical, 8)
        .accessibilityLabel(Text("Loading", bundle: .module))
    }
}

// MARK: - Buttons

public struct KairoButtonStyle: ButtonStyle {
    public enum Kind: Sendable { case primary, secondary, quiet, danger }
    public var kind: Kind
    public var fullWidth: Bool

    public init(kind: Kind = .primary, fullWidth: Bool = false) {
        self.kind = kind
        self.fullWidth = fullWidth
    }

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.kairo(15, .semibold))
            .padding(.horizontal, 16)
            .frame(minHeight: 44)
            .frame(maxWidth: fullWidth ? .infinity : nil)
            .foregroundStyle(foreground)
            .background(background, in: RoundedRectangle(cornerRadius: KairoRadius.control, style: .continuous))
            .overlay {
                if kind == .secondary || kind == .danger {
                    RoundedRectangle(cornerRadius: KairoRadius.control, style: .continuous).strokeBorder(KairoColor.border)
                }
            }
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .opacity(configuration.isPressed ? 0.9 : 1)
            .animation(KairoMotion.snappy, value: configuration.isPressed)
    }

    private var foreground: Color {
        switch kind {
        case .primary: KairoColor.onAccent
        case .danger: KairoColor.danger
        default: KairoColor.text
        }
    }

    private var background: Color {
        switch kind {
        case .primary: KairoColor.accent
        case .secondary, .danger: KairoColor.surface
        case .quiet: KairoColor.surface2
        }
    }
}

/// Filter chip ("All", "Mine", "Family").
public struct FilterChip: View {
    public var title: Text
    public var count: Int?
    public var selected: Bool
    public var action: () -> Void

    /// A literal title is looked up in the main bundle (the app's own string catalog).
    public init(_ title: LocalizedStringKey, count: Int? = nil, selected: Bool, action: @escaping () -> Void) {
        self.init(text: Text(title), count: count, selected: selected, action: action)
    }

    /// A dynamic title (a name, for example) is shown as is.
    @_disfavoredOverload
    public init<S: StringProtocol>(_ title: S, count: Int? = nil, selected: Bool, action: @escaping () -> Void) {
        self.init(text: Text(verbatim: String(title)), count: count, selected: selected, action: action)
    }

    private init(text: Text, count: Int?, selected: Bool, action: @escaping () -> Void) {
        self.title = text; self.count = count; self.selected = selected; self.action = action
    }

    public var body: some View {
        Button(action: action) {
            HStack(spacing: 5) {
                title.font(.kairo(14, .medium))
                if let count { Text(verbatim: "\(count)").font(.kMonoSmall).foregroundStyle(selected ? KairoColor.accentStrong : KairoColor.faint) }
            }
            .padding(.horizontal, 12)
            .frame(height: 34)
            .foregroundStyle(selected ? KairoColor.accentStrong : KairoColor.text)
            .background(selected ? KairoColor.accent.opacity(0.14) : KairoColor.surface, in: Capsule())
            .overlay(Capsule().strokeBorder(selected ? KairoColor.accent.opacity(0.5) : KairoColor.border))
        }
        .buttonStyle(.plain)
        .sensoryFeedback(.selection, trigger: selected)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

// MARK: - Card

public struct KairoCard<Content: View>: View {
    public var title: Text?
    public var trailing: AnyView?
    @ViewBuilder public var content: Content

    public init(_ title: LocalizedStringKey? = nil, trailing: AnyView? = nil, @ViewBuilder content: () -> Content) {
        self.title = title.map { Text($0) }
        self.trailing = trailing
        self.content = content()
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if title != nil || trailing != nil {
                HStack {
                    if let title { title.font(.kHeadline).foregroundStyle(KairoColor.text) }
                    Spacer()
                    trailing
                }
            }
            content
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(KairoColor.surface, in: RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous).strokeBorder(KairoColor.border))
    }
}

// MARK: - Light Markdown (bold, italics, links, lists, headings)

public struct MarkdownText: View {
    public var text: String
    public var font: Font

    public init(_ text: String, font: Font = .kBody) {
        self.text = text
        self.font = font
    }

    private struct Block: Identifiable {
        enum Kind { case paragraph, bullet, numbered(String), heading }
        let id: Int
        let kind: Kind
        let text: String
    }

    private var blocks: [Block] {
        var out: [Block] = []
        var para: [String] = []
        func flush() {
            if !para.isEmpty { out.append(Block(id: out.count, kind: .paragraph, text: para.joined(separator: "\n"))); para.removeAll() }
        }
        for raw in text.components(separatedBy: "\n") {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.isEmpty { flush(); continue }
            if line.hasPrefix("- ") || line.hasPrefix("* ") || line.hasPrefix("• ") {
                flush(); out.append(Block(id: out.count, kind: .bullet, text: String(line.dropFirst(2))))
            } else if let dot = line.firstIndex(of: "."), line[..<dot].allSatisfy(\.isNumber), !line[..<dot].isEmpty, line[line.index(after: dot)...].hasPrefix(" ") {
                flush(); out.append(Block(id: out.count, kind: .numbered(String(line[..<dot])), text: String(line[line.index(dot, offsetBy: 2)...])))
            } else if line.hasPrefix("#") {
                flush(); out.append(Block(id: out.count, kind: .heading, text: line.drop(while: { $0 == "#" }).trimmingCharacters(in: .whitespaces)))
            } else {
                para.append(line)
            }
        }
        flush()
        return out
    }

    private func inline(_ s: String) -> AttributedString {
        (try? AttributedString(markdown: s, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(s)
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(blocks) { b in
                switch b.kind {
                case .paragraph:
                    Text(inline(b.text)).font(font)
                case .heading:
                    Text(inline(b.text)).font(.kHeadline)
                case .bullet:
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Circle().fill(KairoColor.faint).frame(width: 5, height: 5).alignmentGuide(.firstTextBaseline) { $0[.bottom] - 1 }
                        Text(inline(b.text)).font(font)
                    }
                case .numbered(let n):
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(verbatim: "\(n).").font(.kMono).foregroundStyle(KairoColor.muted)
                        Text(inline(b.text)).font(font)
                    }
                }
            }
        }
        .foregroundStyle(KairoColor.text)
        .tint(KairoColor.accentStrong)
        .selectableText()
    }
}

public extension View {
    /// Selectable text where the system allows it (not on Apple Watch).
    @ViewBuilder func selectableText() -> some View {
        #if os(watchOS)
        self
        #else
        self.textSelection(.enabled)
        #endif
    }
}

// MARK: - Section header

public struct SectionLabel: View {
    public var title: Text
    public var count: Int?
    public var tint: Color

    /// A literal title is looked up in the main bundle (the app's own string catalog).
    public init(_ title: LocalizedStringKey, count: Int? = nil, tint: Color = KairoColor.muted) {
        self.init(text: Text(title), count: count, tint: tint)
    }

    /// A dynamic title is shown as is.
    @_disfavoredOverload
    public init<S: StringProtocol>(_ title: S, count: Int? = nil, tint: Color = KairoColor.muted) {
        self.init(text: Text(verbatim: String(title)), count: count, tint: tint)
    }

    private init(text: Text, count: Int?, tint: Color) {
        self.title = text; self.count = count; self.tint = tint
    }

    public var body: some View {
        HStack(spacing: 6) {
            title.font(.kairo(13, .semibold))
            if let count { Text(verbatim: "\(count)").font(.kMonoSmall) }
        }
        .foregroundStyle(tint)
        .textCase(nil)
    }
}
