import SwiftUI
import CoreText
#if canImport(UIKit)
import UIKit
#endif

/// Kairo colors (design/DESIGN.md): cool neutrals and a single amber accent.
public enum KairoColor {
    public static let bg = dynamic(light: 0xF3F4F5, dark: 0x0E1013)
    public static let surface = dynamic(light: 0xFCFCFD, dark: 0x15181C)
    public static let surface2 = dynamic(light: 0xEEF0F2, dark: 0x1C2025)
    public static let border = dynamic(light: 0xDFE2E6, dark: 0x262B31)
    public static let text = dynamic(light: 0x14161A, dark: 0xECEDEE)
    public static let muted = dynamic(light: 0x5B616A, dark: 0x9BA1A9)
    public static let faint = dynamic(light: 0x858B94, dark: 0x6B717A)
    public static let accent = dynamic(light: 0xE0901F, dark: 0xF0A23B)
    /// Amber as text or link (AA contrast in both modes).
    public static let accentStrong = dynamic(light: 0x8F5300, dark: 0xF5B65E)
    public static let onAccent = Color(hex: 0x1A1206)
    public static let danger = dynamic(light: 0xC2412F, dark: 0xF0705F)
    public static let success = dynamic(light: 0x2E8A57, dark: 0x5BC48A)
    /// Orb ring (bone color).
    public static let orbRing = dynamic(light: 0x2A2E34, dark: 0xE9E4DA)

    static func dynamic(light: UInt32, dark: UInt32) -> Color {
        #if os(watchOS)
        return Color(hex: dark)
        #elseif canImport(UIKit)
        return Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
        #else
        return Color(hex: dark)
        #endif
    }
}

public extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255, opacity: opacity)
    }
}

#if canImport(UIKit)
extension UIColor {
    convenience init(hex: UInt32) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }
}
#endif

/// Corner radii (single product rule): controls 10, cards 16, pills.
public enum KairoRadius {
    public static let control: CGFloat = 10
    public static let card: CGFloat = 16
}

/// Motion: Kairo's base curve and moderate springs.
public enum KairoMotion {
    public static let base = Animation.timingCurve(0.16, 1, 0.3, 1, duration: 0.28)
    public static let spring = Animation.spring(response: 0.42, dampingFraction: 0.82)
    public static let snappy = Animation.snappy(duration: 0.24)
}

// MARK: - Typography (bundled Geist and Geist Mono)

public enum KairoFonts {
    nonisolated(unsafe) private static var registered = false

    /// Registers the package fonts. Call once when the app, the widgets or the watch start.
    public static func register() {
        guard !registered else { return }
        registered = true
        for name in ["Geist-Regular", "Geist-Medium", "Geist-SemiBold", "Geist-Bold", "GeistMono-Regular", "GeistMono-Medium"] {
            guard let url = Bundle.module.url(forResource: name, withExtension: "ttf", subdirectory: "Fonts")
                ?? Bundle.module.url(forResource: name, withExtension: "ttf") else { continue }
            CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        }
    }
}

public extension Font {
    /// Geist with Dynamic Type: scales with the system text size.
    static func kairo(_ size: CGFloat, _ weight: Font.Weight = .regular, relativeTo style: Font.TextStyle = .body) -> Font {
        let name = switch weight {
        case .bold, .heavy, .black: "Geist-Bold"
        case .semibold: "Geist-SemiBold"
        case .medium: "Geist-Medium"
        default: "Geist-Regular"
        }
        return .custom(name, size: size, relativeTo: style)
    }

    /// Geist Mono for times, dates and numbers.
    static func kairoMono(_ size: CGFloat, _ weight: Font.Weight = .regular, relativeTo style: Font.TextStyle = .body) -> Font {
        .custom(weight == .regular ? "GeistMono-Regular" : "GeistMono-Medium", size: size, relativeTo: style)
    }

    static let kLargeTitle = kairo(32, .semibold, relativeTo: .largeTitle)
    static let kTitle = kairo(26, .semibold, relativeTo: .title)
    static let kTitle2 = kairo(21, .semibold, relativeTo: .title2)
    static let kTitle3 = kairo(19, .semibold, relativeTo: .title3)
    static let kHeadline = kairo(16, .semibold, relativeTo: .headline)
    static let kBody = kairo(16, .regular, relativeTo: .body)
    static let kCallout = kairo(15, .regular, relativeTo: .callout)
    static let kSubheadline = kairo(14, .regular, relativeTo: .subheadline)
    static let kFootnote = kairo(13, .regular, relativeTo: .footnote)
    static let kCaption = kairo(12, .medium, relativeTo: .caption)
    static let kMono = kairoMono(14, .regular, relativeTo: .body)
    static let kMonoSmall = kairoMono(12, .regular, relativeTo: .caption)
}
