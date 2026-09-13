import Foundation
import CoreLocation

enum TravelMode: String, Codable, CaseIterable {
    case walk, bike, car, moto, other
    static var allCases: [TravelMode] { [.walk, .car, .bike, .other] }
    var category: TravelMode { self == .moto ? .other : self }
    var title: String { switch self { case .walk: return "Пешком"; case .bike: return "Вело"; case .car: return "Авто"; case .moto, .other: return "Другое" } }
    var symbol: String { switch self { case .walk: return "figure.walk"; case .bike: return "bicycle"; case .car: return "car"; case .moto: return "scooter"; case .other: return "tram" } }
    var maxSpeed: Double { switch self { case .walk: return 12; case .bike: return 35; case .car, .moto: return 90; case .other: return 100 } }
}
struct TrackPoint: Codable {
    var lat: Double
    var lng: Double
    var t: Double
    var accuracy: Double
    var breakBefore: Bool?
    enum CodingKeys: String, CodingKey { case lat, lng, t, accuracy; case breakBefore = "break" }
    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: lat, longitude: lng) }
    var valid: Bool { lat.isFinite && lng.isFinite && t.isFinite && accuracy.isFinite && abs(lat) <= 85 && abs(lng) <= 180 && accuracy >= 0 && accuracy <= 60 }
    func distance(to p: TrackPoint) -> Double {
        CLLocation(latitude: lat, longitude: lng).distance(from: CLLocation(latitude: p.lat, longitude: p.lng))
    }
    func connects(to next: TrackPoint) -> Bool { next.breakBefore != true && next.t > t && next.t - t <= 60000 }
    func accepts(after previous: TrackPoint?, mode: TravelMode) -> Bool {
        guard valid else { return false }
        guard let previous else { return true }
        let dt = (t - previous.t) / 1000, d = previous.distance(to: self)
        return dt > 0 && d >= 4 && d / dt <= mode.maxSpeed
    }
}
struct TrackSession: Codable {
    var id: String = UUID().uuidString
    var mode: TravelMode
    var startedAt: Double
    var endedAt: Double?
    var pausedAt: Double?
    var pausedMs: Double = 0
    var breakNext: Bool?
    var points: [TrackPoint] = []
    var lastRecordedAt: Double?
    var distance: Double {
        zip(points, points.dropFirst()).reduce(0) { $0 + ($1.0.connects(to: $1.1) ? $1.0.distance(to: $1.1) : 0) }
    }
    func duration(at now: Double = Date().timeIntervalSince1970 * 1000) -> Double { max(0, ((endedAt ?? pausedAt ?? now) - startedAt - pausedMs) / 1000) }
    func validate(finished: Bool = true) throws {
        guard !id.isEmpty, startedAt.isFinite, pausedMs.isFinite, pausedMs >= 0,
              (!finished || endedAt != nil), endedAt == nil || (endedAt!.isFinite && endedAt! >= startedAt),
              pausedAt == nil || (pausedAt!.isFinite && pausedAt! >= startedAt),
              lastRecordedAt == nil || lastRecordedAt!.isFinite else { throw TrackError.invalidBackup }
        if let end = endedAt, pausedMs > end - startedAt { throw TrackError.invalidBackup }
        for (i,p) in points.enumerated() {
            guard p.valid, p.t >= startedAt, p.t <= (endedAt ?? Double.greatestFiniteMagnitude),
                  i == 0 || p.t > points[i-1].t else { throw TrackError.invalidBackup }
        }
    }
}
struct TrackState: Codable { var sessions: [TrackSession] = []; var active: TrackSession? }
struct Backup: Codable {
    var version = 1
    var sessions: [TrackSession]
    func validate() throws {
        guard version == 1, sessions.count <= 10000, Set(sessions.map(\.id)).count == sessions.count,
              sessions.reduce(0, { $0 + $1.points.count }) <= 200000 else { throw TrackError.invalidBackup }
        try sessions.forEach { try $0.validate() }
    }
}
enum TrackError: LocalizedError {
    case invalidBackup, storage, activeSession
    var errorDescription: String? { switch self {
    case .invalidBackup: return "Файл не является корректной резервной копией Terra."
    case .storage: return "Не удалось сохранить маршрут. Проверь свободное место. Запись остановлена."
    case .activeSession: return "Сначала заверши текущий маршрут."
    } }
}
// A local equal-distance grid estimates union area, including overlapping routes only once.
enum Discovery {
    static let radius = 35.0
    static func cells(_ sessions: [TrackSession]) -> Set<String> {
        var result = Set<String>()
        func stamp(_ p: TrackPoint) {
            let row = Int(floor(p.lat * 111195 / 20))
            for y in (row-2)...(row+2) {
                let lat = (Double(y) + 0.5) * 20 / 111195
                let step = 20 / (111195 * cos(lat * .pi / 180)), column = Int(floor(p.lng / step))
                for x in (column-2)...(column+2) {
                    if p.distance(to: TrackPoint(lat: lat, lng: (Double(x)+0.5)*step, t: p.t, accuracy: 0)) <= radius { result.insert("\(y):\(x)") }
                }
            }
        }
        for session in sessions {
            for (i,p) in session.points.enumerated() {
                stamp(p)
                if i > 0, session.points[i-1].connects(to: p) {
                    let a = session.points[i-1], count = min(500, Int(ceil(a.distance(to: p)/15)))
                    var dlng = p.lng - a.lng
                    if dlng > 180 { dlng -= 360 }; if dlng < -180 { dlng += 360 }
                    if count > 1 { for j in 1..<count {
                        let f = Double(j)/Double(count), lng = (a.lng + dlng*f + 540).truncatingRemainder(dividingBy: 360)-180
                        stamp(TrackPoint(lat: a.lat+(p.lat-a.lat)*f, lng: lng, t: p.t, accuracy: 0))
                    } }
                }
            }
        }
        return result
    }
}
