import SwiftUI
import KairoCore

public enum OrbState: Sendable, Equatable {
    case idle, armed, listening, thinking, speaking, error
}

/// Kairo's orb: open ring + amber dot (the same geometry as the icon), same as on Android.
/// - idle: breathes slowly. - armed: the dot pulses (waiting for the wake phrase).
/// - listening: the ring deforms with the microphone level and the dot grows.
/// - thinking: the gap rotates. - speaking: concentric waves to the rhythm of the voice.
/// With "Reduce Motion" it stays still.
public struct KairoOrb: View {
    public var state: OrbState
    public var level: Double
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var smoothLevel: Double = 0
    @State private var activity: Double = 0

    public init(state: OrbState, level: Double = 0) {
        self.state = state
        self.level = level
    }

    private var targetActivity: Double {
        switch state {
        case .listening, .speaking: 1
        case .thinking: 0.6
        default: 0
        }
    }

    public var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 60, paused: reduceMotion)) { tl in
            let t = tl.date.timeIntervalSinceReferenceDate
            Canvas { ctx, size in
                draw(&ctx, size: size, t: reduceMotion ? 0 : t)
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .onChange(of: level, initial: true) { _, v in
            withAnimation(.spring(response: 0.25, dampingFraction: 0.55)) { smoothLevel = reduceMotion ? 0 : min(1, max(0, v)) }
        }
        .onChange(of: state, initial: true) { _, _ in
            withAnimation(.spring(response: 0.6, dampingFraction: 0.8)) { activity = targetActivity }
        }
        .accessibilityElement()
        .accessibilityLabel(description)
        .accessibilityAddTraits(.updatesFrequently)
    }

    private var description: String {
        switch state {
        case .idle: String(localized: "Kairo is idle", bundle: .module)
        case .armed: String(localized: "Kairo is waiting for \(KairoLanguage.wakePhraseDisplay)", bundle: .module)
        case .listening: String(localized: "Kairo is listening", bundle: .module)
        case .thinking: String(localized: "Kairo is thinking", bundle: .module)
        case .speaking: String(localized: "Kairo is speaking", bundle: .module)
        case .error: String(localized: "Kairo couldn't complete the action", bundle: .module)
        }
    }

    private func draw(_ ctx: inout GraphicsContext, size: CGSize, t: TimeInterval) {
        let minDim = min(size.width, size.height)
        let center = CGPoint(x: size.width / 2, y: size.height / 2)
        let baseR = minDim * 0.34
        let stroke = minDim * 0.075
        let still = reduceMotion
        let ring = KairoColor.orbRing
        let dot = state == .error ? KairoColor.danger : KairoColor.accent

        let breath = still ? 0.5 : (sin(t * .pi / 4.2) + 1) / 2
        let phase = still ? 0 : (t.truncatingRemainder(dividingBy: 2.6) / 2.6) * 2 * .pi
        let spin = still ? 0 : (t.truncatingRemainder(dividingBy: 1.4) / 1.4) * 360
        let ripple = still ? 0 : t.truncatingRemainder(dividingBy: 1.8) / 1.8

        let r = baseR * (still ? 1 : 1 + (breath - 0.5) * 0.03 * (1 - activity))

        if !still && state == .speaking {
            for i in 0..<3 {
                let p = (ripple + Double(i) / 3).truncatingRemainder(dividingBy: 1)
                let rr = r + p * minDim * 0.16
                ctx.stroke(Path(ellipseIn: CGRect(x: center.x - rr, y: center.y - rr, width: rr * 2, height: rr * 2)),
                           with: .color(ring.opacity((1 - p) * 0.18 * (0.4 + smoothLevel))), lineWidth: stroke * 0.35)
            }
        }

        let rotation = (!still && state == .thinking) ? spin : 0
        let gapCenter = -40.0
        let gapHalf = (state == .thinking && !still) ? 30 + 10 * sin(phase) : 30
        let wobble = still ? 0 : smoothLevel * 0.085 * activity

        var c = ctx
        c.translateBy(x: center.x, y: center.y)
        c.rotate(by: .degrees(rotation))

        // Open ring (deformed by the voice while listening)
        let start = gapCenter + gapHalf
        let sweep = 360 - 2 * gapHalf
        var path = Path()
        let steps = 140
        for i in 0...steps {
            let deg = start + sweep * Double(i) / Double(steps)
            let th = deg * .pi / 180
            let k = 1 + wobble * (sin(3 * th + phase) * 0.6 + sin(5 * th - phase * 1.3) * 0.4)
            let p = CGPoint(x: r * k * cos(th), y: r * k * sin(th))
            if i == 0 { path.move(to: p) } else { path.addLine(to: p) }
        }
        c.stroke(path, with: .color(ring), style: StrokeStyle(lineWidth: stroke, lineCap: .round))

        // Amber dot in the gap
        let a = gapCenter * .pi / 180
        let armedPulse = (state == .armed && !still) ? 0.92 + 0.12 * breath : 1
        let dotR = stroke * 0.85 * (1 + smoothLevel * 0.55 * activity) * armedPulse
        let dp = CGPoint(x: r * cos(a), y: r * sin(a))
        if state == .listening || state == .armed {
            let halo = dotR * (2.1 + smoothLevel)
            c.fill(Path(ellipseIn: CGRect(x: dp.x - halo, y: dp.y - halo, width: halo * 2, height: halo * 2)), with: .color(dot.opacity(0.16 + 0.2 * smoothLevel)))
        }
        c.fill(Path(ellipseIn: CGRect(x: dp.x - dotR, y: dp.y - dotR, width: dotR * 2, height: dotR * 2)), with: .color(dot))
    }
}

/// The static mark (icon): for headers and empty states.
public struct KairoMark: View {
    public init() {}
    public var body: some View {
        KairoOrb(state: .idle)
            .accessibilityLabel(Text(verbatim: "Kairo"))
    }
}

#Preview("Orb states") {
    HStack(spacing: 24) {
        KairoOrb(state: .idle).frame(width: 90)
        KairoOrb(state: .listening, level: 0.6).frame(width: 90)
        KairoOrb(state: .thinking).frame(width: 90)
        KairoOrb(state: .speaking, level: 0.5).frame(width: 90)
    }
    .padding()
    .background(KairoColor.bg)
}
