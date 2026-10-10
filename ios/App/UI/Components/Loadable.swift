import SwiftUI
import KairoCore
import KairoUI

/// State of a load: the first time it shows a skeleton; afterwards it reloads without flicker.
enum Loadable<T> {
    case loading
    case failed(String)
    case loaded(T)

    var value: T? { if case .loaded(let v) = self { v } else { nil } }
    var isLoading: Bool { if case .loading = self { true } else { false } }
}

extension Loadable: Equatable where T: Equatable {}

/// Runs [load] the first time and every time [version] changes (live changes from the server).
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

/// Shows the error of an action as a brief notice.
@MainActor
func attempt(_ model: AppModel, _ success: LocalizedStringResource? = nil, _ action: () async throws -> Void) async {
    do {
        try await action()
        if let success { model.show(Toast(title: String(localized: success))) }
    } catch {
        model.show(Toast(title: String(localized: "Couldn't complete that"), message: error.localizedDescription, symbol: "exclamationmark.triangle"))
    }
}

extension View {
    /// Kairo's screen background.
    func kairoBackground() -> some View {
        background(KairoColor.bg.ignoresSafeArea())
    }

    /// Comfortable reading width on iPad and on the inner screen of a foldable.
    func readableWidth(_ max: CGFloat = 760) -> some View {
        frame(maxWidth: max).frame(maxWidth: .infinity)
    }
}
