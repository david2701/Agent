import SwiftUI
import KairoCore
import KairoUI

/// Interruptor "Familiar" de los formularios. Sólo quien lo creó puede dejar de compartirlo.
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
                        Label("Familiar", systemImage: "house")
                        Text(shared ? "La ven y editan todos en \(f.household.name)." : "Sólo tú la ves.")
                            .font(.kFootnote).foregroundStyle(KairoColor.muted)
                    }
                }
                .disabled(!isMine && shared)
                .tint(KairoColor.accent)
                if !isMine, let owner = model.ownerName(ownerId) {
                    Text("La compartió \(owner). Sólo \(owner) puede dejar de compartirla.")
                        .font(.kFootnote).foregroundStyle(KairoColor.muted)
                }
            }
        }
    }
}

/// Elegir un miembro de la familia (responsable de una tarea, a quién avisar…).
struct MemberPicker: View {
    @Environment(AppModel.self) private var model
    var title: String
    var noneLabel: String = "Nadie"
    @Binding var memberId: String?

    var body: some View {
        if let f = model.family, model.hasFamily {
            Picker(selection: $memberId) {
                Text(noneLabel).tag(String?.none)
                ForEach(f.members) { m in
                    Text(m.id == f.me ? "\(m.displayName) (tú)" : m.displayName).tag(Optional(m.id))
                }
            } label: {
                Label(title, systemImage: "person")
            }
        }
    }
}

/// Chips de filtro "Todas · Mías · Familiares" encima de las listas.
struct OwnershipFilter<T: Hashable>: View {
    var options: [(T, String, Int?)]
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
