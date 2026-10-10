import SwiftUI
import KairoCore
import KairoUI

@main
struct KairoWatchApp: App {
    init() { KairoFonts.register() }

    var body: some Scene {
        WindowGroup {
            VStack {
                KairoOrb(state: .idle).frame(width: 60, height: 60)
                Text("Kairo").font(.kHeadline)
            }
        }
    }
}
