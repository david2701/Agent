// swift-tools-version: 6.2
import PackageDescription

// Code shared by the iOS/iPadOS app, its extensions (widgets, Live Activities, share) and the Apple Watch app.
// KairoCore only uses Foundation: it also builds and is tested on Linux. English is the development language;
// Spanish translations live in each target's `Resources/Localizable.xcstrings` (Apple platforms only).

#if os(Linux)
// String catalogs are compiled by Apple's toolchain; on Linux the English keys are used as is.
let coreResources: [Resource] = []
let coreExclude: [String] = ["Resources"]
#else
let coreResources: [Resource] = [.process("Resources")]
let coreExclude: [String] = []
#endif

var targets: [Target] = [
    .target(
        name: "KairoCore",
        exclude: coreExclude,
        resources: coreResources,
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
    defaultLocalization: "en",
    platforms: [.iOS("27.0"), .watchOS("27.0"), .macOS("26.0")],
    products: products,
    targets: targets
)
