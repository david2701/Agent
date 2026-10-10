// swift-tools-version: 6.2
import PackageDescription

// Código compartido por la app de iOS/iPadOS, sus extensiones (widgets, Live Activities, compartir)
// y la app de Apple Watch. KairoCore sólo usa Foundation: también compila y se prueba en Linux.
var targets: [Target] = [
    .target(
        name: "KairoCore",
        swiftSettings: [.swiftLanguageMode(.v6)]
    ),
    .testTarget(
        name: "KairoCoreTests",
        dependencies: ["KairoCore"],
        swiftSettings: [.swiftLanguageMode(.v6)]
    ),
]

var products: [Product] = [
    .library(name: "KairoCore", targets: ["KairoCore"]),
]

#if !os(Linux)
targets.append(
    .target(
        name: "KairoUI",
        dependencies: ["KairoCore"],
        resources: [.process("Resources")],
        swiftSettings: [.swiftLanguageMode(.v6)]
    )
)
products.append(.library(name: "KairoUI", targets: ["KairoUI"]))
#endif

let package = Package(
    name: "KairoKit",
    defaultLocalization: "es",
    platforms: [.iOS("27.0"), .watchOS("27.0"), .macOS("26.0")],
    products: products,
    targets: targets
)
