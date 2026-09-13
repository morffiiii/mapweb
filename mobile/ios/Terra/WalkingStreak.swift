import Foundation

enum WalkingStreak {
    struct Status { var count: Int; var remaining: Int; var canRestore: Bool; var yesterday: String; var today: String; var walkedToday: Bool }
    static func key(_ date: Date, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.year,.month,.day], from: date)
        return String(format: "%04d-%02d-%02d", c.year!,c.month!,c.day!)
    }
    static func days(_ sessions: [TrackSession], calendar: Calendar = .current) -> Set<String> {
        Set(sessions.filter { $0.mode.category == .walk && $0.endedAt != nil && $0.duration() >= 300 && $0.distance > 0 }.map { key(Date(timeIntervalSince1970: $0.endedAt!/1000), calendar: calendar) })
    }
    static func status(walked: Set<String>, restores: [StreakRestore], now: Date = Date(), calendar: Calendar = .current) -> Status {
        let today = calendar.startOfDay(for: now), yesterday = calendar.date(byAdding: .day, value: -1, to: today)!
        let todayKey = key(today,calendar: calendar), yesterdayKey = key(yesterday,calendar: calendar)
        let all = walked.union(restores.map(\.day))
        let used = restores.filter { $0.usedOn.prefix(7) == todayKey.prefix(7) }.count
        var cursor = all.contains(todayKey) ? today : yesterday, count = 0
        while all.contains(key(cursor,calendar: calendar)) { count += 1; cursor = calendar.date(byAdding: .day, value: -1, to: cursor)! }
        let before = key(calendar.date(byAdding: .day, value: -1, to: yesterday)!,calendar: calendar)
        return Status(count: count, remaining: max(0,3-used), canRestore: used < 3 && !all.contains(yesterdayKey) && all.contains(before), yesterday: yesterdayKey, today: todayKey, walkedToday: walked.contains(todayKey))
    }
}
