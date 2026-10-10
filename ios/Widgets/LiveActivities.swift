import ActivityKit
import AlarmKit
import WidgetKit
import SwiftUI
import AppIntents
import KairoCore
import KairoUI

// MARK: - Voice

/// While you talk to Kairo: what it heard, what it is doing and its answer, in the Dynamic Island and on the lock screen.
struct VoiceLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: VoiceActivityAttributes.self) { context in
            HStack(alignment: .center, spacing: 14) {
                KairoOrb(state: orb(context.state.phase)).frame(width: 46, height: 46)
                VStack(alignment: .leading, spacing: 3) {
                    Text(status(context.state)).font(.kairo(13, .semibold)).foregroundStyle(KairoColor.accentStrong)
                    Text(main(context.state)).font(.kairo(15)).foregroundStyle(KairoColor.text).lineLimit(3)
                }
                Spacer(minLength: 0)
            }
            .padding(16)
            .activityBackgroundTint(KairoColor.bg.opacity(0.92))
            .activitySystemActionForegroundColor(KairoColor.accent)
            .widgetURL(URL(string: "kairo://talk"))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    KairoOrb(state: orb(context.state.phase)).frame(width: 40, height: 40).padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(status(context.state)).font(.kairo(12, .semibold)).foregroundStyle(KairoColor.accent).padding(.trailing, 4)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Text(main(context.state)).font(.kairo(14)).lineLimit(3).frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 4)
                }
            } compactLeading: {
                KairoOrb(state: orb(context.state.phase)).frame(width: 22, height: 22)
            } compactTrailing: {
                Image(systemName: icon(context.state.phase)).foregroundStyle(KairoColor.accent).contentTransition(.symbolEffect(.replace))
            } minimal: {
                KairoOrb(state: orb(context.state.phase)).frame(width: 20, height: 20)
            }
            .widgetURL(URL(string: "kairo://talk"))
            .keylineTint(KairoColor.accent)
        }
    }

    private func orb(_ phase: String) -> OrbState {
        switch phase {
        case "listening": .listening
        case "thinking": .thinking
        case "speaking": .speaking
        case "error": .error
        default: .idle
        }
    }

    private func icon(_ phase: String) -> String {
        switch phase {
        case "listening": "mic.fill"
        case "thinking": "ellipsis"
        case "speaking": "speaker.wave.2.fill"
        default: "checkmark"
        }
    }

    private func status(_ s: VoiceActivityAttributes.ContentState) -> String {
        switch s.phase {
        case "listening": String(localized: "I'm listening")
        case "thinking": s.working ?? String(localized: "Thinking")
        case "speaking": "Kairo"
        case "error": String(localized: "Couldn't do it")
        default: String(localized: "All set")
        }
    }

    private func main(_ s: VoiceActivityAttributes.ContentState) -> String {
        if !s.reply.isEmpty { return s.reply }
        if !s.transcript.isEmpty { return "“\(s.transcript)”" }
        return s.phase == "listening" ? String(localized: "Speak whenever you like") : "…"
    }
}

// MARK: - Recording

struct RecordingLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: RecordingActivityAttributes.self) { context in
            HStack(spacing: 14) {
                Image(systemName: context.state.processing ? "waveform.badge.magnifyingglass" : "record.circle")
                    .font(.system(size: 28))
                    .foregroundStyle(context.state.processing ? KairoColor.accent : KairoColor.danger)
                    .symbolEffect(.pulse, isActive: !context.state.processing)
                VStack(alignment: .leading, spacing: 2) {
                    Text(context.attributes.title.isEmpty ? String(localized: "Recording") : context.attributes.title).font(.kairo(15, .semibold)).foregroundStyle(KairoColor.text)
                    Text(context.state.processing ? "Kairo is summarizing" : "\(context.state.segments) parts uploaded").font(.kairo(12)).foregroundStyle(KairoColor.muted)
                }
                Spacer()
                if !context.state.processing {
                    Text(timerInterval: context.state.startedAt...Date.distantFuture, countsDown: false)
                        .font(.kairoMono(22, .medium)).monospacedDigit().foregroundStyle(KairoColor.text)
                        .frame(width: 96, alignment: .trailing)
                }
            }
            .padding(16)
            .activityBackgroundTint(KairoColor.bg.opacity(0.92))
            .widgetURL(URL(string: "kairo://section/recordings"))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label("Recording now", systemImage: "record.circle").font(.kairo(13, .semibold)).foregroundStyle(KairoColor.danger)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(timerInterval: context.state.startedAt...Date.distantFuture, countsDown: false).font(.kairoMono(15, .medium)).monospacedDigit()
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Text(context.attributes.title.isEmpty ? String(localized: "Meeting") : context.attributes.title).font(.kairo(13)).foregroundStyle(.secondary)
                }
            } compactLeading: {
                Image(systemName: "record.circle").foregroundStyle(KairoColor.danger).symbolEffect(.pulse)
            } compactTrailing: {
                Text(timerInterval: context.state.startedAt...Date.distantFuture, countsDown: false).monospacedDigit().frame(width: 52)
            } minimal: {
                Image(systemName: "record.circle").foregroundStyle(KairoColor.danger)
            }
            .widgetURL(URL(string: "kairo://section/recordings"))
        }
    }
}

// MARK: - Next event

struct EventLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: EventActivityAttributes.self) { context in
            HStack(spacing: 14) {
                VStack(alignment: .leading, spacing: 3) {
                    Label(context.attributes.shared ? "Family event" : "Next event", systemImage: context.attributes.shared ? "house" : "calendar")
                        .font(.kairo(12, .semibold)).foregroundStyle(KairoColor.accentStrong)
                    Text(context.attributes.title).font(.kairo(16, .semibold)).foregroundStyle(KairoColor.text).lineLimit(2)
                    if !context.state.location.isEmpty {
                        Label(context.state.location, systemImage: "mappin").font(.kairo(12)).foregroundStyle(KairoColor.muted).lineLimit(1)
                    }
                }
                Spacer()
                if context.state.cancelled {
                    Text("Cancelled").font(.kairo(14, .semibold)).foregroundStyle(KairoColor.danger)
                } else {
                    Text(timerInterval: Date.now...max(Date.now, context.state.startAt), countsDown: true)
                        .font(.kairoMono(24, .medium)).monospacedDigit().foregroundStyle(KairoColor.text)
                        .frame(width: 100, alignment: .trailing)
                }
            }
            .padding(16)
            .activityBackgroundTint(KairoColor.bg.opacity(0.92))
            .widgetURL(URL(string: "kairo://section/calendar"))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Image(systemName: context.attributes.shared ? "house.fill" : "calendar").foregroundStyle(KairoColor.accent).font(.system(size: 22))
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(timerInterval: Date.now...max(Date.now, context.state.startAt), countsDown: true).font(.kairoMono(16, .medium)).monospacedDigit()
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(context.attributes.title).font(.kairo(14, .semibold)).lineLimit(1)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    if !context.state.location.isEmpty { Label(context.state.location, systemImage: "mappin").font(.kairo(12)).foregroundStyle(.secondary) }
                }
            } compactLeading: {
                Image(systemName: "calendar").foregroundStyle(KairoColor.accent)
            } compactTrailing: {
                Text(timerInterval: Date.now...max(Date.now, context.state.startAt), countsDown: true).monospacedDigit().frame(width: 48)
            } minimal: {
                Image(systemName: "calendar").foregroundStyle(KairoColor.accent)
            }
            .widgetURL(URL(string: "kairo://section/calendar"))
        }
    }
}

// MARK: - Timers and alarms (AlarmKit)

/// Countdown of the timers the agent asks for, with pause and stop.
struct AlarmLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: AlarmAttributes<KairoAlarmMetadata>.self) { context in
            HStack(spacing: 14) {
                Image(systemName: context.attributes.metadata?.kind == "alarm" ? "alarm.fill" : "timer")
                    .font(.system(size: 26)).foregroundStyle(KairoColor.accent)
                VStack(alignment: .leading, spacing: 2) {
                    Text(context.attributes.metadata?.label ?? "Kairo").font(.kairo(15, .semibold)).foregroundStyle(KairoColor.text).lineLimit(1)
                    AlarmCountdown(state: context.state, font: .kairoMono(26, .medium))
                }
                Spacer()
                AlarmButtons(state: context.state)
            }
            .padding(16)
            .activityBackgroundTint(KairoColor.bg.opacity(0.92))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label(context.attributes.metadata?.label ?? String(localized: "Timer"), systemImage: "timer").font(.kairo(13, .semibold)).foregroundStyle(KairoColor.accent).lineLimit(1)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    AlarmCountdown(state: context.state, font: .kairoMono(20, .medium))
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack { Spacer(); AlarmButtons(state: context.state) }
                }
            } compactLeading: {
                Image(systemName: "timer").foregroundStyle(KairoColor.accent)
            } compactTrailing: {
                AlarmCountdown(state: context.state, font: .system(size: 14, weight: .semibold)).frame(width: 52)
            } minimal: {
                Image(systemName: "timer").foregroundStyle(KairoColor.accent)
            }
            .keylineTint(KairoColor.accent)
        }
    }
}

private struct AlarmCountdown: View {
    var state: AlarmPresentationState
    var font: Font

    var body: some View {
        switch state.mode {
        case .countdown(let c):
            Text(timerInterval: Date.now...max(Date.now, c.fireDate), countsDown: true).font(font).monospacedDigit().foregroundStyle(KairoColor.text)
        case .paused:
            Text("Paused").font(font).foregroundStyle(KairoColor.muted)
        case .alert:
            Text("Time's up!").font(font).foregroundStyle(KairoColor.accent)
        @unknown default:
            EmptyView()
        }
    }
}

private struct AlarmButtons: View {
    var state: AlarmPresentationState

    var body: some View {
        HStack(spacing: 10) {
            switch state.mode {
            case .countdown:
                Button(intent: PauseAlarmIntent(alarmId: state.alarmID.uuidString)) { Image(systemName: "pause.fill") }
                    .buttonStyle(.plain).frame(width: 40, height: 40).background(KairoColor.surface2, in: Circle())
            case .paused:
                Button(intent: ResumeAlarmIntent(alarmId: state.alarmID.uuidString)) { Image(systemName: "play.fill") }
                    .buttonStyle(.plain).frame(width: 40, height: 40).background(KairoColor.surface2, in: Circle())
            default:
                EmptyView()
            }
            Button(intent: StopAlarmIntent(alarmId: state.alarmID.uuidString)) { Image(systemName: "xmark") }
                .buttonStyle(.plain).foregroundStyle(KairoColor.onAccent).frame(width: 40, height: 40).background(KairoColor.accent, in: Circle())
        }
        .font(.system(size: 15, weight: .bold))
    }
}
