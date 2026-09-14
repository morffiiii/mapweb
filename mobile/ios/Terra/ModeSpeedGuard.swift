import Foundation
struct ModeSpeedGuard {
    private var highSince: Double?
    private var lastTime: Double = 0
    private var safeSince: Double?
    private(set) var allowsDiscovery = true
    private(set) var warning = false
    mutating func update(kmh: Double,time: Double,mode: TravelMode) {
        guard kmh.isFinite,kmh >= 0,time > lastTime else { return }
        let limit: Double = mode.category == .walk ? 22 : mode.category == .bike ? 65 : .infinity
        if time-lastTime > 5000 { highSince = nil; safeSince = nil }; lastTime = time
        if kmh > limit { safeSince = nil; if highSince == nil { highSince = time }; allowsDiscovery = false; warning = time-(highSince ?? time) >= 8000 }
        else { highSince = nil; if safeSince == nil { safeSince = time }; if allowsDiscovery || time-(safeSince ?? time) >= 3000 { allowsDiscovery = true; warning = false } }
    }
}
