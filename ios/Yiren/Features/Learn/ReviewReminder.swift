import Foundation
import UserNotifications

/// 每日复习本地提醒（与 Android ReviewReminderWorker 对应）。纯本地，不联网。
enum ReviewReminder {
    static let id = "review_reminder"

    static func enable(hour: Int, minute: Int) {
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            guard granted else { return }
            schedule(hour: hour, minute: minute)
        }
    }

    private static func schedule(hour: Int, minute: Int) {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [id])
        let content = UNMutableNotificationContent()
        let zh = (Locale.preferredLanguages.first?.hasPrefix("zh") ?? false)
        content.body = zh ? "📚 该复习单词啦" : "📚 Time to review your words"
        content.sound = .default
        var dc = DateComponents(); dc.hour = hour; dc.minute = minute
        let trigger = UNCalendarNotificationTrigger(dateMatching: dc, repeats: true)
        center.add(UNNotificationRequest(identifier: id, content: content, trigger: trigger))
    }

    static func disable() {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [id])
    }
}
