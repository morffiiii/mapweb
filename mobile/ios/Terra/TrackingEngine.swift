import UIKit
import CoreLocation

final class TrackingEngine: NSObject, CLLocationManagerDelegate {
    static let shared = TrackingEngine()
    static let changed = Notification.Name("TerraTrackingChanged")
    private(set) var state = TrackState()
    private(set) var position: CLLocation?
    private(set) var message = "Определяем местоположение…"
    private(set) var blocked = false
    private(set) var pendingMode: TravelMode?
    private var resumePending = false
    private let manager = CLLocationManager()
    private let stepRecorder = StepRecorder()
    private var liveSpeed = LiveSpeed()
    private var modeGuard = ModeSpeedGuard()
    var modeWarning: String? { !modeGuard.allowsDiscovery && recording ? (modeGuard.warning ? "Скорость не соответствует режиму · открытие приостановлено" : "Проверяем скорость · открытие приостановлено") : nil }
    var speed: Double? { recording ? liveSpeed.value(now: now) : 0 }
    var stepsAvailable: Bool { stepRecorder.available }
    private var durableState = TrackState()
    private var visible = true
    private var errorMessage: String?
    private var file: URL { FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("terra-state.json") }
    private var now: Double { Date().timeIntervalSince1970*1000 }
    var recording: Bool { state.active != nil && state.active?.pausedAt == nil }
    var authorized: Bool { manager.authorizationStatus == .authorizedAlways || manager.authorizationStatus == .authorizedWhenInUse }

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = kCLDistanceFilterNone
        manager.pausesLocationUpdatesAutomatically = false
        manager.showsBackgroundLocationIndicator = true
        do {
            if FileManager.default.fileExists(atPath: file.path) {
                let data = try Data(contentsOf: file)
                state = try JSONDecoder().decode(TrackState.self, from: data)
                try Backup(sessions: state.sessions).validate()
                try state.active?.validate(finished: false)
                durableState = state
                if var session = state.active, session.pausedAt == nil {
                    session.pausedAt = session.lastRecordedAt ?? session.points.last?.t ?? session.startedAt
                    session.breakNext = true; state.active = session
                    message = "Предыдущая запись прервалась. Нажми «Продолжить»."
                    try persist()
                }
            }
        } catch { blocked = true; message = "Не удалось прочитать сохранение. Исходный файл не изменён." }
    }
    private func notify() { NotificationCenter.default.post(name: Self.changed, object: self) }
    private func persist() throws {
        guard !blocked else { throw TrackError.storage }
        do {
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try JSONEncoder().encode(state).write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            durableState = state
        } catch {
            state = durableState
            if state.active != nil { state.active?.pausedAt = now }
            pendingMode = nil; resumePending = false
            manager.stopUpdatingLocation(); manager.allowsBackgroundLocationUpdates = false
            errorMessage = TrackError.storage.localizedDescription; message = errorMessage!; notify()
            throw TrackError.storage
        }
    }
    func takeError() -> String? { defer { errorMessage = nil }; return errorMessage }
    func foreground(_ isVisible: Bool) { visible = isVisible; configureLocation() }
    func locate() {
        switch manager.authorizationStatus {
        case .notDetermined: manager.requestWhenInUseAuthorization()
        case .denied, .restricted: message = "Разреши Terra доступ к геопозиции в настройках."; notify()
        default: configureLocation()
        }
    }
    private func configureLocation() {
        guard authorized, !blocked else { return }
        manager.allowsBackgroundLocationUpdates = recording
        manager.distanceFilter = kCLDistanceFilterNone
        manager.activityType = (state.active?.mode == .car || state.active?.mode == .moto) ? .automotiveNavigation : .fitness
        if visible || recording { manager.startUpdatingLocation() } else { manager.stopUpdatingLocation() }
    }
    func begin(_ mode: TravelMode) {
        guard !blocked, state.active == nil else { return }
        pendingMode = mode; resumePending = false; message = "Ждём точный GPS для начала записи…"
        locate(); fulfillPending(); notify()
    }
    func resume() {
        guard !blocked, let session = state.active, session.pausedAt != nil else { return }
        pendingMode = session.mode; resumePending = true
        message = "Ждём точный GPS для продолжения…"; locate(); fulfillPending(); notify()
    }
    func cancelPending() { pendingMode = nil; resumePending = false; message = "Начало записи отменено"; notify() }
    private func fulfillPending() {
        guard let mode = pendingMode, authorized else { return }
        if resumePending, var session = state.active, let paused = session.pausedAt {
            session.pausedMs += now-paused; session.pausedAt = nil; session.breakNext = true
            session.lastRecordedAt = now; state.active = session
        } else if state.active == nil {
            let time = now
            var points: [TrackPoint] = []
            if let p = position, abs(p.timestamp.timeIntervalSinceNow) < 20, p.horizontalAccuracy >= 0, p.horizontalAccuracy <= 60 {
                points = [TrackPoint(lat: p.coordinate.latitude, lng: p.coordinate.longitude, t: time, accuracy: p.horizontalAccuracy)]
            }
            state.active = TrackSession(mode: mode, startedAt: time, points: points)
        }
        pendingMode = nil; resumePending = false
        do { try persist(); liveSpeed.reset(); modeGuard = ModeSpeedGuard(); startSteps(); configureLocation(); message = state.active?.points.isEmpty == true ? "Запись начата · уточняем GPS…" : "Маршрут записывается" } catch { return }
    }
    private func startSteps() {
        guard let session = state.active, session.mode == .walk else { return }
        let id = session.id
        stepRecorder.start { [weak self] delta in
            guard let self else { return }
            if var session = self.state.active, session.id == id { session.steps = (session.steps ?? 0)+delta; self.state.active = session }
            else if let i = self.state.sessions.firstIndex(where: { $0.id == id }) { var session = self.state.sessions[i]; session.steps = (session.steps ?? 0)+delta; self.state.sessions[i] = session }
            do { try self.persist(); self.notify() } catch { self.stepRecorder.stop() }
        }
    }
    func pause() {
        stepRecorder.stop(); liveSpeed.reset()
        cancelPending()
        guard recording else { return }
        state.active?.pausedAt = now; state.active?.lastRecordedAt = now
        do { try persist(); message = "Запись на паузе" } catch { }
        configureLocation(); notify()
    }
    func finish() {
        stepRecorder.stop(); liveSpeed.reset()
        guard var session = state.active else { return }
        pendingMode = nil; resumePending = false
        if let paused = session.pausedAt { session.pausedMs += now-paused }
        session.endedAt = now; session.pausedAt = nil
        state.sessions.append(session); state.active = nil
        do { try persist(); message = "Маршрут сохранён" } catch { }
        configureLocation(); notify()
    }
    func importData(_ data: Data) throws {
        guard state.active == nil else { throw TrackError.activeSession }
        guard data.count <= 25_000_000 else { throw TrackError.invalidBackup }
        let backup = try JSONDecoder().decode(Backup.self, from: data); try backup.validate()
        var ids = Set(state.sessions.map(\.id))
        var merged = state.sessions
        for session in backup.sessions where !ids.contains(session.id) { merged.append(session); ids.insert(session.id) }
        try Backup(sessions: merged).validate(); state.sessions = merged
        try persist(); message = "Маршруты восстановлены"; notify()
    }
    func exportURL() throws -> URL {
        // Export current file unchanged when recovery is necessary, rather than destroying it.
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("Terra-backup.json")
        if blocked { try Data(contentsOf: file).write(to: url, options: .atomic) }
        else { try JSONEncoder().encode(Backup(sessions: state.sessions)).write(to: url, options: .atomic) }
        return url
    }
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if authorized { message = "Определяем местоположение…"; configureLocation(); fulfillPending() }
        else if manager.authorizationStatus == .denied || manager.authorizationStatus == .restricted {
            if recording { pause() }
            cancelPending(); message = "Нет доступа к геопозиции. Разреши его в настройках."; manager.stopUpdatingLocation()
        }
        notify()
    }
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        for location in locations.sorted(by: { $0.timestamp < $1.timestamp }) {
            guard CLLocationCoordinate2DIsValid(location.coordinate), abs(location.coordinate.latitude) <= 85,
                  location.horizontalAccuracy >= 0, location.timestamp.timeIntervalSinceNow > -30,
                  location.timestamp.timeIntervalSinceNow < 5 else { continue }
            if let previous = position, location.timestamp <= previous.timestamp { continue }
            position = location; fulfillPending()
            if recording { liveSpeed.add(metersPerSecond: location.speed,accuracy: location.speedAccuracy,time: location.timestamp.timeIntervalSince1970*1000,maximum: 100)
                if location.speed >= 0, location.speedAccuracy >= 0, location.speedAccuracy <= 3, let mode = state.active?.mode { modeGuard.update(kmh: location.speed*3.6,time: location.timestamp.timeIntervalSince1970*1000,mode: mode) } }
            if recording, var session = state.active {
                let p = TrackPoint(lat: location.coordinate.latitude, lng: location.coordinate.longitude,
                                   t: location.timestamp.timeIntervalSince1970*1000, accuracy: location.horizontalAccuracy,
                                   breakBefore: session.breakNext,excludeDiscovery: !modeGuard.allowsDiscovery)
                if p.t >= session.startedAt, p.accepts(after: session.points.last, mode: session.mode) {
                    session.points.append(p); session.breakNext = false; session.lastRecordedAt = p.t; state.active = session
                    do { try persist() } catch { break }
                }
            }
            if pendingMode == nil { message = "GPS ±\(Int(location.horizontalAccuracy)) м" + (recording ? " · идёт запись" : "") }
        }
        notify()
    }
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        message = "Нет сигнала GPS. Координаты продолжат поступать после восстановления сигнала."; notify()
    }
}
