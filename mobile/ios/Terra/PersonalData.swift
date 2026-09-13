import Foundation
import CoreLocation

struct Place: Codable { var id = UUID().uuidString; var lat: Double; var lng: Double; var note: String; var photo: String? }
struct Territory: Codable { var lat: Double; var lng: Double; var radius: Double = 500 }
struct PersonalGoal: Codable { var id = UUID().uuidString; var title: String; var kind: String; var target: Double }
struct StreakRestore: Codable { var day: String; var usedOn: String }
struct PersonalData: Codable {
    var name = "Исследователь"; var places: [Place] = []; var territory: Territory?; var goals: [PersonalGoal]?; var restores: [StreakRestore]?
    var age: Int?; var height: Double?; var weight: Double?; var intentions: [String]?; var source: String?; var avatar: String?
}
final class PersonalStore {
    static let shared = PersonalStore()
    private(set) var data = PersonalData()
    private var readable = true
    let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    private var file: URL { directory.appendingPathComponent("terra-personal.json") }
    init() { if FileManager.default.fileExists(atPath: file.path) { do { data = try JSONDecoder().decode(PersonalData.self, from: Data(contentsOf: file)) } catch { readable = false } } }
    func update(_ change: (inout PersonalData) -> Void) throws {
        guard readable else { throw TrackError.storage }; var next = data; change(&next)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try JSONEncoder().encode(next).write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]); data = next
    }
}
enum Explore {
    static func coverage(_ territory: Territory, cells: Set<String>) -> Double {
        let center = TrackPoint(lat: territory.lat, lng: territory.lng, t: 0, accuracy: 0)
        var inside = 0
        for key in cells {
            let parts = key.split(separator: ":").compactMap { Double($0) }; guard parts.count == 2 else { continue }
            let lat = (parts[0]+0.5)*20/111195, lng = (parts[1]+0.5)*20/(111195*cos(lat * .pi/180))
            if center.distance(to: TrackPoint(lat: lat, lng: lng, t: 0, accuracy: 0)) <= territory.radius { inside += 1 }
        }
        return min(100, Double(inside)*400/(Double.pi*territory.radius*territory.radius)*100)
    }
    static func nearby(_ p: CLLocationCoordinate2D, cells: Set<String>) -> [TrackPoint] {
        var found: [TrackPoint] = []
        for radius in [200.0, 400, 700, 1000] {
            for i in 0..<12 {
                let angle = Double(i)*Double.pi/6
                let lat = p.latitude+cos(angle)*radius/111195, lng = p.longitude+sin(angle)*radius/(111195*cos(p.latitude * .pi/180))
                let row = floor(lat*111195/20), rowLat = (row+0.5)*20/111195
                let col = floor(lng*111195*cos(rowLat * .pi/180)/20)
                if !cells.contains("\(Int(row)):\(Int(col))") { found.append(TrackPoint(lat: lat, lng: lng, t: 0, accuracy: 0)) }
                if found.count == 6 { return found }
            }
        }; return found
    }
}
