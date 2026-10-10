import WidgetKit
import SwiftUI
import KairoCore
import KairoUI

@main
struct KairoWidgetsBundle: WidgetBundle {
    init() { KairoFonts.register() }

    var body: some Widget {
        TodayWidget()
        NextEventWidget()
        ListWidget()
        TalkWidget()
        TalkControl()
        TodayControl()
        VoiceLiveActivity()
        RecordingLiveActivity()
        EventLiveActivity()
        AlarmLiveActivity()
    }
}
