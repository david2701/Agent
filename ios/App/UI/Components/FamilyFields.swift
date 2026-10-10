import SwiftUI
import KairoCore
import KairoUI

/// "Family" switch of the forms. Only whoever created it can stop sharing it.
struct FamilyShareToggle: View {
    @Environment(AppModel.self) private var model
    @Binding var shared: Bool
    var ownerId: String?
    var lockedReason: String?

    private var isMine: Bool { ownerId == nil || ownerId == model.user?.id }

    var body: some View {
        if let f = model.family, model.hasFamily {
            if let lockedReason {
                Label(lockedReason, systemImage: "house").font(.kSubheadline).foregroundStyle(KairoColor.muted)
            } else {
                Toggle(isOn: $shared) {
                    VStack(alignment: .leading, spacing: 2) {
                        Label("Family sharing", systemImage: "house")
                        Text(shared ? "Everyone in \(f.household.name) can see and edit it." : "Only you can see it.")
                            .font(.kFootnote).foregroundStyle(KairoColor.muted)
                    }
                }
                .disabled(!isMine && shared)
                .tint(KairoColor.accent)
                if !isMine, let owner = model.ownerName(ownerId) {
                    Text("\(owner) shared it. Only \(owner) can stop sharing it.")
                        .font(.kFootnote).foregroundStyle(KairoColor.muted)
                }
            }
        }
    }
}

/// Pick a family member (assignee of a task, who to notify…).
struct MemberPicker: View {
    @Environment(AppModel.self) private var model
    var title: LocalizedStringKey
    var noneLabel: LocalizedStringKey = "Nobody"
    @Binding var memberId: String?

    var body: some View {
        if let f = model.family, model.hasFamily {
            Picker(selection: $memberId) {
                Text(noneLabel).tag(String?.none)
                ForEach(f.members) { m in
                    memberLabel(m, me: f.me).tag(Optional(m.id))
                }
            } label: {
                Label(title, systemImage: "person")
            }
        }
    }

    private func memberLabel(_ m: Member, me: String) -> Text {
        m.id == me ? Text("\(m.displayName) (you)") : Text(verbatim: m.displayName)
    }
}

/// Filter chips "All · Mine · Family" above the lists.
struct OwnershipFilter<T: Hashable>: View {
    var options: [(T, LocalizedStringKey, Int?)]
    @Binding var selection: T

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(options, id: \.0) { o in
                    FilterChip(o.1, count: o.2, selected: selection == o.0) {
                        withAnimation(KairoMotion.base) { selection = o.0 }
                    }
                }
            }
            .padding(.horizontal, 20)
        }
        .scrollIndicators(.hidden)
    }
}
