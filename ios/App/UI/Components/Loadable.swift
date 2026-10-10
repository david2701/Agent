import SwiftUI
import KairoCore
import KairoUI

/// Estado de una carga: la primera vez muestra esqueleto; luego recarga sin parpadeo.
enum Loadable<T> {
    case loading
    case failed(String)
    case loaded(T)

    var value: T? { if case .loaded(let v) = self { v } else { nil } }
    var isLoading: Bool { if case .loading = self { true } else { false } }
}

extension Loadable: Equatable where T: Equatable {}

/// Carga [load] la primera vez y cada vez que cambia [version] (cambios en vivo del servidor).
struct LoadableView<T, Content: View>: View {
    @Binding var state: Loadable<T>
    var version: Int
    var skeleton: Int = 5
    var load: () async throws -> T
    @ViewBuilder var content: (T) -> Content

    var body: some View {
        Group {
            switch state {
            case .loading:
                ScrollView { SkeletonRows(count: skeleton).padding(.horizontal, 20).frame(maxWidth: .infinity, alignment: .leading) }
            case .failed(let message):
                ErrorStateView(message) { Task { await run() } }
            case .loaded(let v):
                content(v)
            }
        }
        .task(id: version) { await run() }
    }

    private func run() async {
        do {
            let v = try await load()
            withAnimation(KairoMotion.base) { state = .loaded(v) }
        } catch is CancellationError {
        } catch {
            if state.value == nil { state = .failed(error.localizedDescription) }
        }
    }
}

/// Muestra el error de una acción como aviso breve.
@MainActor
func attempt(_ model: AppModel, _ success: String? = nil, _ action: () async throws -> Void) async {
    do {
        try await action()
        if let success { model.show(Toast(title: success)) }
    } catch {
        model.show(Toast(title: "No se pudo completar", message: error.localizedDescription, symbol: "exclamationmark.triangle"))
    }
}

extension View {
    /// Fondo de pantalla de Kairo.
    func kairoBackground() -> some View {
        background(KairoColor.bg.ignoresSafeArea())
    }

    /// Ancho de lectura cómodo en iPad y en la pantalla interior del iPhone Duo.
    func readableWidth(_ max: CGFloat = 760) -> some View {
        frame(maxWidth: max).frame(maxWidth: .infinity)
    }
}
