import Foundation
struct LiveSpeed {
    private var samples: [(Double,Double)] = []
    mutating func add(metersPerSecond: Double, accuracy: Double, time: Double, maximum: Double) {
        guard metersPerSecond.isFinite, metersPerSecond >= 0, metersPerSecond <= maximum, accuracy >= 0, accuracy <= 3, time > (samples.last?.0 ?? 0) else { return }
        samples.removeAll { time-$0.0 > 3000 }; samples.append((time,metersPerSecond*3.6))
        if metersPerSecond < 0.3 { samples = [(time,0)] }
        if samples.count > 3 { samples.removeFirst() }
    }
    func value(now: Double) -> Double? {
        guard let last = samples.last, now-last.0 >= -1000, now-last.0 <= 4000 else { return nil }
        let sorted = samples.map { $0.1 }.sorted(); return sorted[sorted.count/2]
    }
    mutating func reset() { samples.removeAll() }
}
