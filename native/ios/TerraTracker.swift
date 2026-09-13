// Appended to AppDelegate.swift by scripts/prepare-ios.mjs, so no manual Xcode edits are needed.
import CoreLocation

struct TerraPoint: Codable {
    var lat: Double
    var lng: Double
    var t: Double
    var accuracy: Double
    var `break`: Bool?
}
struct TerraSession: Codable {
    var id: String
    var mode: String
    var startedAt: Double
    var endedAt: Double?
    var pausedAt: Double?
    var pausedMs: Double
    var breakNext: Bool?
    var points: [TerraPoint]
    var lastRecordedAt: Double?
}
struct TerraState: Codable {
    var sessions: [TerraSession] = []
    var active: TerraSession?
}

class TerraViewController: CAPBridgeViewController {
    override func capacitorDidLoad() {
        bridge?.registerPluginInstance(TerraTracker())
    }
}

@objc(TerraTracker)
public class TerraTracker: CAPPlugin, CAPBridgedPlugin, CLLocationManagerDelegate {
    public let identifier = "TerraTracker"
    public let jsName = "TerraTracker"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "snapshot", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "start", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pause", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "resume", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "finish", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "importSessions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "exportBackup", returnType: CAPPluginReturnPromise)
    ]
    private var manager: CLLocationManager!
    private var state = TerraState()
    private var durableState = TerraState()
    private var readFailure: String?
    private var pendingStart: CAPPluginCall?
    private var pendingMode = "walk"
    private let modes: [String: Double] = ["walk": 12, "bike": 35, "car": 90, "moto": 90, "other": 100]
    private var storeURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("terra-state.json")
    }
    private func now() -> Double { Date().timeIntervalSince1970 * 1000 }

    public override func load() {
        DispatchQueue.main.async {
            self.manager = CLLocationManager()
            self.manager.delegate = self
            self.manager.desiredAccuracy = kCLLocationAccuracyBest
            self.manager.distanceFilter = 5
            self.manager.pausesLocationUpdatesAutomatically = false
            self.manager.allowsBackgroundLocationUpdates = true
            self.manager.showsBackgroundLocationIndicator = true
            do {
                if FileManager.default.fileExists(atPath: self.storeURL.path) {
                    self.state = try JSONDecoder().decode(TerraState.self, from: Data(contentsOf: self.storeURL))
                    self.durableState = self.state
                    // Process termination is a recording gap. Never silently count it as recorded time.
                    if var s = self.state.active, s.pausedAt == nil {
                        s.pausedAt = s.lastRecordedAt ?? s.points.last?.t ?? s.startedAt
                        s.breakNext = true
                        self.state.active = s
                        try self.save()
                    }
                }
            } catch { self.readFailure = "Не удалось прочитать маршруты. Сохранение оставлено без изменений." }
        }
    }
    private func payload() throws -> JSObject {
        var object = try JSValueEncoder().encodeJSObject(state)
        if state.active == nil { object["active"] = NSNull() }
        return object
    }
    private func save() throws {
        let parent = storeURL.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: true)
        // Accessible after first unlock, including while the screen is locked.
        try JSONEncoder().encode(state).write(to: storeURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        durableState = state
    }
    private func publish(_ call: CAPPluginCall? = nil) {
        do {
            try save()
            let data = try payload()
            notifyListeners("updated", data: data)
            call?.resolve(data)
        } catch {
            manager.stopUpdatingLocation()
            state = durableState
            if var s = state.active, s.pausedAt == nil { s.pausedAt = now(); state.active = s }
            if let data = try? payload() { notifyListeners("updated", data: data) }
            call?.reject("Не удалось сохранить маршрут. Проверь свободное место.")
            notifyListeners("failure", data: ["message": "Запись остановлена: не удалось сохранить координаты."])
        }
    }
    private func perform(_ call: CAPPluginCall, _ block: @escaping () -> Void) {
        DispatchQueue.main.async {
            if let failure = self.readFailure { call.reject(failure); return }
            block()
        }
    }
    @objc func snapshot(_ call: CAPPluginCall) {
        perform(call) { do { call.resolve(try self.payload()) } catch { call.reject(error.localizedDescription) } }
    }
    @objc func start(_ call: CAPPluginCall) {
        perform(call) {
            guard self.state.active == nil, self.pendingStart == nil else { call.reject("Маршрут уже запущен"); return }
            let mode = call.getString("mode") ?? "walk"
            guard self.modes[mode] != nil else { call.reject("Неизвестный режим"); return }
            guard CLLocationManager.locationServicesEnabled() else { call.reject("Включи службы геолокации в настройках iPhone"); return }
            self.pendingStart = call
            self.pendingMode = mode
            if self.manager.authorizationStatus == .notDetermined { self.manager.requestWhenInUseAuthorization() }
            else { self.completeStart() }
        }
    }
    private func completeStart() {
        guard let call = pendingStart else { return }
        let status = manager.authorizationStatus
        if status == .notDetermined { return }
        pendingStart = nil
        guard status == .authorizedAlways || status == .authorizedWhenInUse else {
            call.reject("Разреши геолокацию для Terra в настройках iPhone"); return
        }
        state.active = TerraSession(id: UUID().uuidString, mode: pendingMode, startedAt: now(), pausedMs: 0, points: [])
        manager.activityType = pendingMode == "walk" || pendingMode == "bike" ? .fitness : .automotiveNavigation
        manager.startUpdatingLocation()
        publish(call)
    }
    @objc func pause(_ call: CAPPluginCall) {
        perform(call) {
            guard var s = self.state.active else { call.reject("Нет активного маршрута"); return }
            self.manager.stopUpdatingLocation()
            if s.pausedAt == nil { s.pausedAt = self.now(); s.lastRecordedAt = s.pausedAt }
            self.state.active = s; self.publish(call)
        }
    }
    @objc func resume(_ call: CAPPluginCall) {
        perform(call) {
            guard var s = self.state.active, let paused = s.pausedAt else { call.reject("Маршрут не на паузе"); return }
            let auth = self.manager.authorizationStatus
            guard auth == .authorizedAlways || auth == .authorizedWhenInUse else { call.reject("Разреши геолокацию в настройках iPhone"); return }
            s.pausedMs += self.now() - paused; s.pausedAt = nil; s.breakNext = true
            s.lastRecordedAt = self.now()
            self.state.active = s
            self.manager.activityType = s.mode == "walk" || s.mode == "bike" ? .fitness : .automotiveNavigation
            self.manager.startUpdatingLocation(); self.publish(call)
        }
    }
    @objc func finish(_ call: CAPPluginCall) {
        perform(call) {
            guard var s = self.state.active else { call.reject("Нет активного маршрута"); return }
            self.manager.stopUpdatingLocation()
            let end = self.now()
            if let paused = s.pausedAt { s.pausedMs += end - paused }
            s.pausedAt = nil; s.endedAt = end
            self.state.sessions.append(s); self.state.active = nil; self.publish(call)
        }
    }
    public func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        completeStart()
        if manager.authorizationStatus == .denied || manager.authorizationStatus == .restricted {
            if var s = state.active, s.pausedAt == nil {
                manager.stopUpdatingLocation(); s.pausedAt = now(); state.active = s; publish()
                notifyListeners("failure", data: ["message": "Геолокация отключена. Запись поставлена на паузу."])
            }
        }
    }
    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard var s = state.active, s.pausedAt == nil else { return }
        var changed = false
        for location in locations {
            let t = location.timestamp.timeIntervalSince1970 * 1000
            guard location.horizontalAccuracy >= 0, location.horizontalAccuracy <= 60,
                  abs(location.coordinate.latitude) <= 85, t >= s.startedAt, t <= now() + 5000, now() - t < 30000 else { continue }
            if let prev = s.points.last {
                let dt = (t - prev.t) / 1000
                let d = location.distance(from: CLLocation(latitude: prev.lat, longitude: prev.lng))
                guard dt > 0, d >= 4, d / dt <= (modes[s.mode] ?? 12) else { continue }
            }
            let point = TerraPoint(lat: location.coordinate.latitude, lng: location.coordinate.longitude, t: t, accuracy: location.horizontalAccuracy, break: s.breakNext == true ? true : nil)
            s.points.append(point); s.lastRecordedAt = t; s.breakNext = false; changed = true
        }
        if changed { state.active = s; publish() }
    }
    public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        notifyListeners("failure", data: ["message": "Нет точной геопозиции. Запись продолжится после восстановления GPS."])
    }
    @objc func importSessions(_ call: CAPPluginCall) {
        perform(call) {
            guard self.state.active == nil else { call.reject("Сначала заверши прогулку"); return }
            do {
                guard let raw = call.getArray("sessions") else { call.reject("Нет маршрутов"); return }
                let data = try JSONSerialization.data(withJSONObject: raw)
                let incoming = try JSONDecoder().decode([TerraSession].self, from: data)
                guard incoming.count <= 10000, incoming.reduce(0, { $0 + $1.points.count }) <= 200000,
                      incoming.allSatisfy({ self.modes[$0.mode] != nil && $0.endedAt != nil }) else { call.reject("Некорректный файл"); return }
                var ids = Set(self.state.sessions.map { $0.id })
                for s in incoming where !ids.contains(s.id) { self.state.sessions.append(s); ids.insert(s.id) }
                self.publish(call)
            } catch { call.reject("Не удалось прочитать резервную копию") }
        }
    }
    @objc func exportBackup(_ call: CAPPluginCall) {
        perform(call) {
            do {
                let sessions = try JSONSerialization.jsonObject(with: JSONEncoder().encode(self.state.sessions))
                let data = try JSONSerialization.data(withJSONObject: ["version": 1, "sessions": sessions], options: [.prettyPrinted])
                let url = FileManager.default.temporaryDirectory.appendingPathComponent("terra-backup.json")
                try data.write(to: url, options: .atomic)
                let share = UIActivityViewController(activityItems: [url], applicationActivities: nil)
                guard let vc = self.bridge?.viewController else { call.reject("Не удалось открыть экспорт"); return }
                share.popoverPresentationController?.sourceView = vc.view
                share.popoverPresentationController?.sourceRect = CGRect(x: vc.view.bounds.midX, y: vc.view.bounds.midY, width: 0, height: 0)
                vc.present(share, animated: true) { call.resolve() }
            } catch { call.reject("Не удалось создать резервную копию") }
        }
    }
}
