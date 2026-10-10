import ActivityKit
import WidgetKit
import SwiftUI
import KairoCore
import KairoUI

struct VoiceLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: VoiceActivityAttributes.self) { context in
            HStack(spacing: 12) {
                KairoOrb(state: .listening).frame(width: 40, height: 40)
                Text(context.state.reply.isEmpty ? context.state.transcript : context.state.reply).font(.kCallout).lineLimit(2)
            }
            .padding(16)
            .activityBackgroundTint(KairoColor.bg)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) { KairoOrb(state: .listening).frame(width: 36, height: 36) }
                DynamicIslandExpandedRegion(.bottom) { Text(context.state.reply).font(.kCallout).lineLimit(2) }
            } compactLeading: {
                KairoOrb(state: .listening).frame(width: 20, height: 20)
            } compactTrailing: {
                Image(systemName: "waveform")
            } minimal: {
                KairoOrb(state: .listening).frame(width: 18, height: 18)
            }
        }
    }
}
