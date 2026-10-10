import SwiftUI
import KairoCore
import KairoUI

// Pantallas provisionales mientras se completan (se reemplazan una por una).

struct ChatScreen: View { var body: some View { PendingScreen(section: .chat) } }
struct TasksScreen: View { var body: some View { PendingScreen(section: .tasks) } }
struct CalendarScreen: View { var body: some View { PendingScreen(section: .calendar) } }
struct NotesScreen: View { var body: some View { PendingScreen(section: .notes) } }
struct RemindersScreen: View { var body: some View { PendingScreen(section: .reminders) } }
struct ListsScreen: View { var body: some View { PendingScreen(section: .lists) } }
struct MemoryScreen: View { var body: some View { PendingScreen(section: .memory) } }
struct RecordingsScreen: View { var body: some View { PendingScreen(section: .recordings) } }
struct ActivityScreen: View { var body: some View { PendingScreen(section: .activity) } }
struct FamilyScreen: View { var body: some View { PendingScreen(section: .family) } }
struct SettingsScreen: View { var body: some View { PendingScreen(section: .settings) } }

private struct PendingScreen: View {
    let section: AppSection
    var body: some View {
        EmptyStateView(symbol: section.symbol, title: section.title, message: "Muy pronto.")
            .navigationTitle(section.title)
            .kairoBackground()
    }
}
