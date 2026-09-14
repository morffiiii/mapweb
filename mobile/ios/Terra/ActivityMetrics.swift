import Foundation

enum ActivityMetrics {
    static func speed(_ session: TrackSession, now: Double = Date().timeIntervalSince1970*1000) -> Double {
        guard session.pausedAt == nil, session.endedAt == nil, let b = session.points.last, session.points.count > 1, now-b.t <= 15000 else { return 0 }
        let a = session.points[session.points.count-2]
        return a.connects(to: b) ? a.distance(to: b)/((b.t-a.t)/1000)*3.6 : 0
    }
    // Approximate active energy above rest. MET categories: 2024 Adult Compendium.
    // Only recorded moving intervals count; pauses and missing GPS do not.
    static func calories(_ session: TrackSession, weight: Double?) -> Double {
        guard session.mode.category == .walk || session.mode.category == .bike else { return 0 }
        let kg = weight.flatMap { $0.isFinite && (10...400).contains($0) ? $0 : nil } ?? 70
        return zip(session.points,session.points.dropFirst()).reduce(0) { total,pair in
            let (a,b) = pair; guard a.excludeDiscovery != true, b.excludeDiscovery != true, a.connects(to: b) else { return total }
            let seconds = (b.t-a.t)/1000, kmh = a.distance(to: b)/seconds*3.6
            guard kmh >= 0.5 else { return total }
            let met: Double
            if session.mode.category == .walk { met = kmh < 3.2 ? 2.3 : kmh < 4.0 ? 2.8 : kmh < 4.8 ? 3.5 : kmh < 5.6 ? 3.8 : kmh < 6.4 ? 4.8 : 5.5 }
            else { met = kmh < 16 ? 4 : kmh < 19.3 ? 6.8 : kmh < 22.5 ? 8 : kmh < 25.7 ? 10 : 12 }
            return total+(met-1)*kg*seconds/3600
        }
    }
}
