import UIKit
import SwiftUI
import UniformTypeIdentifiers
import KairoCore
import KairoUI

/// "Guardar en Kairo" desde la hoja de compartir: textos, enlaces e imágenes.
/// Kairo decide qué hacer (nota, tarea, evento, lista…) y muestra lo que hizo.
final class ShareViewController: UIViewController {
    private let model = ShareModel()

    override func viewDidLoad() {
        super.viewDidLoad()
        KairoFonts.register()
        model.close = { [weak self] in self?.extensionContext?.completeRequest(returningItems: nil) }
        let host = UIHostingController(rootView: ShareView(model: model).tint(KairoColor.accent))
        addChild(host)
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        host.view.backgroundColor = .clear
        view.addSubview(host.view)
        host.didMove(toParent: self)
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        Task { await model.load(providers) }
    }
}

@MainActor @Observable
final class ShareModel {
    enum Phase { case loading, ready, sending, done, signedOut }

    var phase: Phase = .loading
    var text = ""
    var url: String?
    var image: Data?
    var note = ""
    var turn = AgentTurn()
    var close: () -> Void = {}

    func load(_ providers: [NSItemProvider]) async {
        guard await ExtensionAPI.isReady() else { phase = .signedOut; return }
        for p in providers {
            if image == nil, p.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
                image = await Self.data(p, .image).flatMap(Self.jpeg)
            } else if url == nil, p.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
                url = await Self.transferable(p, URL.self)?.absoluteString
            } else if text.isEmpty, p.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                text = await Self.transferable(p, String.self) ?? ""
            }
        }
        phase = .ready
    }

    func send() async {
        phase = .sending
        turn = AgentTurn()
        let combined = [text, note].filter { !$0.isEmpty }.joined(separator: "\n\n")
        let part = image.map { KairoAPI.FilePart(filename: "compartido.jpg", mime: "image/jpeg", data: $0) }
        do {
            for try await ev in ExtensionAPI.shared.capture(text: combined.isEmpty ? nil : combined, url: url, image: part) {
                withAnimation(KairoMotion.base) { turn.apply(ev) }
            }
        } catch {
            turn.error = error.localizedDescription
        }
        withAnimation(KairoMotion.spring) { phase = .done }
    }

    nonisolated private static func data(_ p: NSItemProvider, _ type: UTType) async -> Data? {
        await withCheckedContinuation { c in
            _ = p.loadDataRepresentation(for: type) { data, _ in c.resume(returning: data) }
        }
    }

    nonisolated private static func transferable<T: Transferable & Sendable>(_ p: NSItemProvider, _ t: T.Type) async -> T? {
        await withCheckedContinuation { c in
            _ = p.loadTransferable(type: t) { r in c.resume(returning: try? r.get()) }
        }
    }

    nonisolated private static func jpeg(_ data: Data) -> Data? {
        guard let img = UIImage(data: data) else { return nil }
        let scale = min(1, 2048 / max(img.size.width, img.size.height))
        let size = CGSize(width: img.size.width * scale, height: img.size.height * scale)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in img.draw(in: CGRect(origin: .zero, size: size)) }.jpegData(compressionQuality: 0.82)
    }
}

struct ShareView: View {
    @Bindable var model: ShareModel

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    switch model.phase {
                    case .loading:
                        ProgressView().frame(maxWidth: .infinity).padding(40)
                    case .signedOut:
                        EmptyStateView(symbol: "person.crop.circle.badge.exclamationmark", title: "Inicia sesión en Kairo",
                                       message: "Abre Kairo en este iPhone e inicia sesión; luego podrás guardar desde aquí.")
                    case .ready, .sending:
                        preview
                        TextField("Agrega una indicación (opcional): \"agéndalo\", \"para la lista del súper\"…", text: $model.note, axis: .vertical)
                            .lineLimit(2...5)
                            .padding(14)
                            .background(KairoColor.surface2, in: RoundedRectangle(cornerRadius: KairoRadius.control, style: .continuous))
                            .disabled(model.phase == .sending)
                        if model.phase == .sending { result }
                    case .done:
                        result
                    }
                }
                .padding(20)
            }
            .background(KairoColor.bg)
            .navigationTitle("Guardar en Kairo")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(model.phase == .done ? "Listo" : "Cancelar") { model.close() }
                }
                if model.phase == .ready {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Guardar") { Task { await model.send() } }
                            .disabled(model.text.isEmpty && model.url == nil && model.image == nil)
                    }
                }
            }
        }
    }

    @ViewBuilder private var preview: some View {
        if let data = model.image, let img = UIImage(data: data) {
            Image(uiImage: img).resizable().scaledToFit().frame(maxHeight: 260).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        if let url = model.url {
            Label(url, systemImage: "link").font(.kCallout).foregroundStyle(KairoColor.accentStrong).lineLimit(2)
        }
        if !model.text.isEmpty {
            Text(model.text).font(.kBody).lineLimit(10)
                .padding(14).frame(maxWidth: .infinity, alignment: .leading)
                .background(KairoColor.surface, in: RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous))
        }
    }

    private var result: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                KairoOrb(state: model.phase == .sending ? .thinking : (model.turn.error == nil ? .idle : .error)).frame(width: 34, height: 34)
                Text(model.phase == .sending ? (model.turn.working ?? "Kairo lo está guardando") : (model.turn.error == nil ? "Listo" : "No se pudo guardar"))
                    .font(.kHeadline)
            }
            ForEach(model.turn.actions) { a in
                ActionCard(summary: a.running ? a.label : a.summary, entityType: a.entity?.type, ok: a.ok, running: a.running)
            }
            if !model.turn.text.isEmpty { MarkdownText(model.turn.text, font: .kCallout) }
            if let e = model.turn.error { Text(e).font(.kFootnote).foregroundStyle(KairoColor.danger) }
        }
        .padding(16)
        .background(KairoColor.surface, in: RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous))
        .transition(.opacity.combined(with: .move(edge: .bottom)))
    }
}
