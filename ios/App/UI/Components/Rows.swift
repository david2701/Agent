import SwiftUI
import KairoCore
import KairoUI

// Rows shared by Today, Tasks, Calendar and Reminders.

/// Circle to complete, with animated fill and haptics.
struct CheckCircle: View {
    var done: Bool
    var tint: Color = KairoColor.accent
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: done ? "checkmark.circle.fill" : "circle")
                .font(.system(size: 22, weight: .regular))
                .foregroundStyle(done ? tint : KairoColor.faint)
                .contentTransition(.symbolEffect(.replace))
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .sensoryFeedback(.success, trigger: done) { _, new in new }
        .accessibilityLabel(done ? "Mark as not done" : "Complete")
    }
}

/// Priority as a flag (only high and urgent, to avoid noise).
struct PriorityFlag: View {
    var priority: String

    var body: some View {
        switch priority {
        case "urgent":
            Image(systemName: "exclamationmark.2").font(.system(size: 12, weight: .bold)).foregroundStyle(KairoColor.danger)
                .accessibilityLabel("Urgent")
        case "high":
            Image(systemName: "flag.fill").font(.system(size: 11, weight: .semibold)).foregroundStyle(KairoColor.accentStrong)
                .accessibilityLabel("High priority")
        default:
            EmptyView()
        }
    }
}

struct TaskRow: View {
    @Environment(AppModel.self) private var model
    var task: KTask
    var overdue = false
    var onToggle: () -> Void

    private var dueText: String? {
        if let at = KairoDate.parse(task.dueAt) { return KairoDate.humanDateTime(at) }
        if let d = task.dueDate.flatMap({ KairoDate.day($0) }) { return KairoDate.humanDay(d).capitalizedFirst }
        return nil
    }

    var body: some View {
        HStack(alignment: .center, spacing: 6) {
            CheckCircle(done: task.isDone, action: onToggle)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(task.title)
                        .font(.kBody)
                        .foregroundStyle(task.isDone ? KairoColor.muted : KairoColor.text)
                        .strikethrough(task.isDone, color: KairoColor.faint)
                        .lineLimit(2)
                    PriorityFlag(priority: task.isDone ? "" : task.priority)
                }
                let meta = metaLine
                if !meta.isEmpty || task.shared {
                    HStack(spacing: 6) {
                        if !meta.isEmpty {
                            Text(meta)
                                .font(.kFootnote)
                                .foregroundStyle(overdue && !task.isDone ? KairoColor.danger : KairoColor.muted)
                                .lineLimit(1)
                        }
                        if task.shared { SharedBadge(owner: model.ownerName(task.ownerId), compact: !meta.isEmpty) }
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.trailing, 8)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    private var metaLine: String {
        var parts: [String] = []
        if overdue && !task.isDone { parts.append(String(localized: "Overdue")) } else if task.status == "doing" { parts.append(String(localized: "In progress")) }
        if let dueText, !(overdue && !task.isDone) { parts.append(dueText) }
        if let p = task.projectName, !p.isEmpty { parts.append(p) }
        if let a = task.assigneeId, a != model.user?.id, let m = model.family?.member(a) { parts.append(String(localized: "For \(m.firstName)")) }
        if task.assigneeId == model.user?.id, task.ownerId != model.user?.id, let o = model.ownerName(task.ownerId) { parts.append(String(localized: "Assigned to you by \(o)")) }
        return parts.joined(separator: " · ")
    }
}

struct EventRow: View {
    @Environment(AppModel.self) private var model
    var event: Event
    var showDay = false

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            VStack(alignment: .leading, spacing: 2) {
                if event.allDay {
                    Text("All day").font(.kMonoSmall).foregroundStyle(KairoColor.accentStrong)
                } else if let s = event.start {
                    Text(KairoDate.time(s)).font(.kairoMono(15, .medium)).foregroundStyle(KairoColor.accentStrong).lineLimit(1).minimumScaleFactor(0.7)
                    if let e = event.end { Text(KairoDate.time(e)).font(.kMonoSmall).foregroundStyle(KairoColor.faint).lineLimit(1).minimumScaleFactor(0.7) }
                }
            }
            .frame(width: 76, alignment: .leading)
            RoundedRectangle(cornerRadius: 2)
                .fill(event.color.flatMap(Color.init(hexString:)) ?? (event.shared ? KairoColor.accent : KairoColor.border))
                .frame(width: 3)
                .frame(minHeight: 34)
            VStack(alignment: .leading, spacing: 3) {
                Text(event.title).font(.kBody).foregroundStyle(KairoColor.text).lineLimit(2)
                let meta = [showDay ? event.start.map { KairoDate.humanDay($0).capitalizedFirst } : nil,
                            event.location.isEmpty ? nil : event.location,
                            event.source == "google" ? "Google" : nil].compactMap { $0 }.joined(separator: " · ")
                if !meta.isEmpty || event.shared {
                    HStack(spacing: 6) {
                        if !meta.isEmpty { Text(meta).font(.kFootnote).foregroundStyle(KairoColor.muted).lineLimit(1) }
                        if event.shared { SharedBadge(owner: model.ownerName(event.ownerId), compact: !meta.isEmpty) }
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 6)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

struct ReminderRow: View {
    @Environment(AppModel.self) private var model
    var reminder: Reminder
    var highlight = false
    var onDone: (() -> Void)?
    var onSnooze: (() -> Void)?

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: reminder.placeId != nil ? "mappin.and.ellipse" : (highlight ? "bell.badge.fill" : "bell"))
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(highlight ? KairoColor.accent : KairoColor.muted)
                .symbolEffect(.wiggle, options: .nonRepeating, isActive: highlight)
                .frame(width: 30)
            VStack(alignment: .leading, spacing: 3) {
                Text(reminder.text).font(.kBody).foregroundStyle(KairoColor.text).lineLimit(3)
                Text(subtitle).font(.kFootnote).foregroundStyle(KairoColor.muted).lineLimit(1)
            }
            Spacer(minLength: 0)
            if let onSnooze {
                Button(action: onSnooze) { Image(systemName: "zzz").frame(width: 36, height: 36) }
                    .buttonStyle(.plain).foregroundStyle(KairoColor.muted)
                    .accessibilityLabel("Snooze 10 minutes")
            }
            if let onDone {
                Button(action: onDone) { Image(systemName: "checkmark").font(.system(size: 14, weight: .bold)).frame(width: 36, height: 36) }
                    .buttonStyle(.plain)
                    .foregroundStyle(KairoColor.onAccent)
                    .background(KairoColor.accent, in: Circle())
                    .accessibilityLabel("Done")
            }
        }
        .padding(.vertical, 6)
        .accessibilityElement(children: .combine)
    }

    private var subtitle: String {
        var parts: [String] = []
        if let p = reminder.placeName {
            parts.append(reminder.placeTrigger == "leave" ? String(localized: "When leaving \(p)") : String(localized: "When arriving at \(p)"))
        } else if let at = KairoDate.parse(reminder.nextFireAt ?? reminder.dueAt) {
            parts.append(KairoDate.humanDateTime(at).capitalizedFirst)
        }
        if let r = reminder.rrule, !r.isEmpty { parts.append(RecurrenceText.describe(r)) }
        if let me = model.user?.id {
            if let owner = reminder.ownerId, owner != me, let m = model.family?.member(owner) { parts.append(String(localized: "For \(m.firstName)")) }
            if let by = reminder.createdBy, by != me, reminder.ownerId == me, let m = model.family?.member(by) { parts.append(String(localized: "From \(m.firstName)")) }
        }
        return parts.joined(separator: " · ")
    }
}

extension String {
    /// "today" → "Today".
    var capitalizedFirst: String { prefix(1).uppercased() + dropFirst() }
}

extension Color {
    /// "#E0901F" or "E0901F".
    init?(hexString: String) {
        let s = hexString.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "#", with: "")
        guard s.count == 6, let v = UInt32(s, radix: 16) else { return nil }
        self.init(hex: v)
    }
}

/// Block header with an optional action ("Agenda · Calendar ›").
struct BlockHeader: View {
    var title: LocalizedStringKey
    var count: Int?
    var action: LocalizedStringKey?
    var onAction: (() -> Void)?

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title).font(.kTitle3).foregroundStyle(KairoColor.text)
            if let count, count > 0 {
                Text("\(count)").font(.kMonoSmall).foregroundStyle(KairoColor.faint)
                    .contentTransition(.numericText())
            }
            Spacer()
            if let action, let onAction {
                Button(action: onAction) {
                    HStack(spacing: 3) {
                        Text(action)
                        Image(systemName: "chevron.right").font(.system(size: 11, weight: .semibold))
                    }
                    .font(.kairo(14, .medium))
                    .foregroundStyle(KairoColor.accentStrong)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.top, 8)
    }
}
