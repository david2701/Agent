import UIKit
import SwiftUI
import KairoCore
import KairoUI

/// "Guardar en Kairo" desde la hoja de compartir.
final class ShareViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        KairoFonts.register()
        let host = UIHostingController(rootView: Text("Kairo").font(.kTitle))
        addChild(host)
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(host.view)
        host.didMove(toParent: self)
    }
}
