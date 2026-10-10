import Foundation

/// Audio format sent to Whisper: 16-bit mono PCM at 16 kHz (same as Android).
public enum AudioSpec {
    public static let sampleRate = 16_000
    /// 32 ms per block.
    public static let frame = 512
}

public enum AudioMath {
    public static func rms(_ samples: UnsafeBufferPointer<Int16>) -> Double {
        guard !samples.isEmpty else { return 0 }
        var sum = 0.0
        for v in samples { let d = Double(v); sum += d * d }
        return (sum / Double(samples.count)).squareRoot()
    }

    public static func rms(_ samples: [Int16]) -> Double { samples.withUnsafeBufferPointer { rms($0) } }

    /// Level 0...1 for the orb animation, on a roughly logarithmic scale.
    public static func level(_ rms: Double) -> Double {
        if rms <= 30 { return 0 }
        let db = 20 * log10(rms / 32768.0)
        return min(1, max(0, (db + 55) / 45))
    }

    /// 16-bit mono PCM WAV (Whisper accepts it directly).
    public static func wav(_ pcm: [Int16], sampleRate: Int = AudioSpec.sampleRate) -> Data {
        var d = Data(capacity: 44 + pcm.count * 2)
        func u32(_ v: UInt32) { var x = v.littleEndian; withUnsafeBytes(of: &x) { d.append(contentsOf: $0) } }
        func u16(_ v: UInt16) { var x = v.littleEndian; withUnsafeBytes(of: &x) { d.append(contentsOf: $0) } }
        let bytes = UInt32(pcm.count * 2)
        d.append(contentsOf: Array("RIFF".utf8)); u32(36 + bytes); d.append(contentsOf: Array("WAVE".utf8))
        d.append(contentsOf: Array("fmt ".utf8)); u32(16); u16(1); u16(1)
        u32(UInt32(sampleRate)); u32(UInt32(sampleRate * 2)); u16(2); u16(16)
        d.append(contentsOf: Array("data".utf8)); u32(bytes)
        pcm.withUnsafeBufferPointer { p in
            for v in p { var x = v.littleEndian; withUnsafeBytes(of: &x) { d.append(contentsOf: $0) } }
        }
        return d
    }
}

/// End-of-speech detection by energy, with a threshold that adapts to the background noise.
/// Finishes after ~0.9 s of silence once speech was heard, or after 15 s. Same parameters as Android.
public struct EndOfSpeech: Sendable {
    public enum Verdict: Sendable { case `continue`, done, noSpeech }

    private var noiseFloor: Double
    private var speechFrames = 0
    private var silenceFrames = 0
    private var totalFrames = 0
    public private(set) var heardSpeech = false
    private let framesPerSecond: Double

    public init(noiseFloor: Double = 220, framesPerSecond: Double = Double(AudioSpec.sampleRate) / Double(AudioSpec.frame)) {
        self.noiseFloor = noiseFloor
        self.framesPerSecond = framesPerSecond
    }

    public mutating func reset() {
        speechFrames = 0; silenceFrames = 0; totalFrames = 0; heardSpeech = false
    }

    public mutating func push(_ rms: Double) -> Verdict {
        totalFrames += 1
        let threshold = max(noiseFloor * 2.6, 420)
        if rms > threshold {
            speechFrames += 1
            silenceFrames = 0
            if speechFrames >= 3 { heardSpeech = true }
        } else {
            silenceFrames += 1
            if !heardSpeech {
                speechFrames = 0
                noiseFloor = noiseFloor * 0.97 + rms * 0.03
            }
        }
        if heardSpeech && Double(silenceFrames) > framesPerSecond * 0.9 { return .done }
        if Double(totalFrames) > framesPerSecond * 15 { return heardSpeech ? .done : .noSpeech }
        if !heardSpeech && Double(totalFrames) > framesPerSecond * 6 { return .noSpeech }
        return .continue
    }
}

/// Wake phrase ("Hey Kairo" in English, "Oye Kairo" in Spanish). Both are always accepted, whatever the
/// configured phrase is, and recognizers often write "Cairo": both spellings are accepted too.
public enum WakePhrase {
    public static func normalize(_ s: String) -> String {
        let folded = s.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil)
        let cleaned = folded.unicodeScalars.map { CharacterSet.alphanumerics.contains($0) ? Character($0) : " " }
        return String(cleaned).split(separator: " ").joined(separator: " ")
    }

    /// Accepted variants for the configured phrase ("hey kairo" -> "hey cairo", "oye kairo"...).
    public static func variants(_ phrase: String) -> [String] {
        let p = normalize(phrase)
        guard let name = p.split(separator: " ").last.map(String.init), !name.isEmpty else { return [] }
        let names = Set([name, name.replacingOccurrences(of: "k", with: "c"), name.replacingOccurrences(of: "c", with: "k")])
        var out: [String] = []
        for n in names.sorted() {
            for lead in ["oye", "hey", "ey", "ok", "oiga"] { out.append("\(lead) \(n)") }
        }
        return out
    }

    /// true if the recognized text contains the phrase. Also returns what was said after it ("hey kairo, what's on today?").
    public static func match(_ transcript: String, phrase: String) -> (hit: Bool, rest: String) {
        let t = normalize(transcript)
        for v in variants(phrase) {
            if let r = t.range(of: v) {
                let rest = t[r.upperBound...].trimmingCharacters(in: .whitespaces)
                return (true, rest)
            }
        }
        return (false, "")
    }
}
