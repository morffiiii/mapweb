import UIKit
import MapKit
import UniformTypeIdentifiers

final class Fog: NSObject, MKOverlay {
    let coordinate = CLLocationCoordinate2D(latitude: 0, longitude: 0)
    let boundingMapRect = MKMapRect.world
    let sessions: [TrackSession]
    init(_ sessions: [TrackSession]) { self.sessions = sessions }
}
final class FogRenderer: MKOverlayRenderer {
    var dark = true
    override func draw(_ mapRect: MKMapRect, zoomScale: MKZoomScale, in context: CGContext) {
        guard let fog = overlay as? Fog else { return }
        context.saveGState()
        context.clip(to: rect(for: mapRect))
        context.beginTransparencyLayer(auxiliaryInfo: nil)
        context.setFillColor((dark ? UIColor(red: 0.09, green: 0.10, blue: 0.13, alpha: 0.89) : UIColor(white: 0.72, alpha: 0.88)).cgColor)
        context.fill(rect(for: mapRect))
        context.setBlendMode(.clear)
        context.setLineCap(.round); context.setLineJoin(.round)
        for s in fog.sessions {
            for (i,p) in s.points.enumerated() {
                let mp = MKMapPoint(p.coordinate), radius = Discovery.radius * MKMapPointsPerMeterAtLatitude(p.lat)
                let area = MKMapRect(x: mp.x-radius, y: mp.y-radius, width: radius*2, height: radius*2)
                if area.intersects(mapRect) { context.fillEllipse(in: rect(for: area)) }
                if i > 0, s.points[i-1].connects(to: p) {
                    let previous = MKMapPoint(s.points[i-1].coordinate)
                    // Do not draw an across-world chord at the antimeridian.
                    if abs(previous.x-mp.x) > MKMapRect.world.width/2 { continue }
                    let bounds = MKMapRect(x: min(mp.x,previous.x)-radius, y: min(mp.y,previous.y)-radius,
                                           width: abs(mp.x-previous.x)+2*radius, height: abs(mp.y-previous.y)+2*radius)
                    if bounds.intersects(mapRect) {
                        context.setLineWidth(radius*2); context.beginPath()
                        context.move(to: point(for: previous)); context.addLine(to: point(for: mp)); context.strokePath()
                    }
                }
            }
        }
        context.endTransparencyLayer(); context.restoreGState()
    }
}

final class MapController: UIViewController, MKMapViewDelegate, UIDocumentPickerDelegate {
    private let map = MKMapView()
    private let engine = TrackingEngine.shared
    private let status = UILabel(), metric = UILabel(), start = UIButton(type: .system), finish = UIButton(type: .system)
    private let modes = UISegmentedControl(items: TravelMode.allCases.map(\.title))
    private let accent = UIColor(red: 1, green: 0.36, blue: 0.12, alpha: 1)
    private var layer: TravelMode?, centered = false, fog: Fog?, signature = "", timer: Timer?
    private var selectedMode: TravelMode { TravelMode.allCases[max(0, modes.selectedSegmentIndex)] }
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground; view.tintColor = accent
        applyTheme()
        map.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(map)
        NSLayoutConstraint.activate([map.topAnchor.constraint(equalTo: view.topAnchor), map.bottomAnchor.constraint(equalTo: view.bottomAnchor), map.leadingAnchor.constraint(equalTo: view.leadingAnchor), map.trailingAnchor.constraint(equalTo: view.trailingAnchor)])
        map.delegate = self; map.showsUserLocation = true; map.pointOfInterestFilter = .excludingAll
        map.accessibilityIdentifier = "nativeMap"
        let top = UIStackView(); top.axis = .horizontal; top.distribution = .equalSpacing
        let title = UILabel(); title.text = "TERRA ↗"; title.font = .systemFont(ofSize: 27, weight: .black)
        top.addArrangedSubview(title)
        top.addArrangedSubview(button("Слои", "square.3.layers.3d", #selector(layers)))
        place(top, top: true)
        let panel = UIStackView(); panel.axis = .vertical; panel.spacing = 14
        status.font = .systemFont(ofSize: 12, weight: .medium); status.textColor = .secondaryLabel; status.numberOfLines = 2
        metric.font = .monospacedDigitSystemFont(ofSize: 27, weight: .semibold)
        metric.accessibilityIdentifier = "sessionMetrics"
        modes.selectedSegmentIndex = 0; modes.accessibilityIdentifier = "travelModes"
        panel.addArrangedSubview(status); panel.addArrangedSubview(metric); panel.addArrangedSubview(modes)
        let actions = UIStackView(); actions.spacing = 10; actions.distribution = .fillEqually
        start.addTarget(self, action: #selector(toggleRecording), for: .touchUpInside); start.accessibilityIdentifier = "startRecording"
        var c = UIButton.Configuration.filled(); c.baseBackgroundColor = accent; c.baseForegroundColor = .white; c.cornerStyle = .large; c.contentInsets = NSDirectionalEdgeInsets(top: 16, leading: 12, bottom: 16, trailing: 12); start.configuration = c
        finish.configuration = .tinted(); finish.setTitle("Завершить", for: .normal); finish.addTarget(self, action: #selector(endRecording), for: .touchUpInside)
        actions.addArrangedSubview(start); actions.addArrangedSubview(finish); panel.addArrangedSubview(actions)
        let nav = UIStackView(); nav.distribution = .equalSpacing
        nav.addArrangedSubview(button("История", "clock", #selector(history)))
        nav.addArrangedSubview(button("Где я", "location.fill", #selector(locate)))
        nav.addArrangedSubview(button("Настройки", "slider.horizontal.3", #selector(settings)))
        panel.addArrangedSubview(nav); place(panel, top: false)
        NotificationCenter.default.addObserver(self, selector: #selector(refresh), name: TrackingEngine.changed, object: nil)
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.refreshMetrics() }
        refresh()
    }
    override func viewDidAppear(_ animated: Bool) { super.viewDidAppear(animated); engine.locate() }
    deinit { timer?.invalidate(); NotificationCenter.default.removeObserver(self) }
    private func button(_ title: String, _ icon: String, _ action: Selector) -> UIButton {
        let b = UIButton(type: .system); var c = UIButton.Configuration.plain(); c.title = title; c.image = UIImage(systemName: icon); c.imagePadding = 6; b.configuration = c; b.addTarget(self, action: action, for: .touchUpInside); return b
    }
    private func place(_ stack: UIStackView, top: Bool) {
        let bg = UIVisualEffectView(effect: UIBlurEffect(style: .systemMaterial)); bg.layer.cornerRadius = 24; bg.clipsToBounds = true
        bg.translatesAutoresizingMaskIntoConstraints = false; stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(bg); bg.contentView.addSubview(stack)
        NSLayoutConstraint.activate([bg.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 14), bg.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -14),
            stack.topAnchor.constraint(equalTo: bg.contentView.topAnchor, constant: 16), stack.bottomAnchor.constraint(equalTo: bg.contentView.bottomAnchor, constant: -16), stack.leadingAnchor.constraint(equalTo: bg.contentView.leadingAnchor, constant: 16), stack.trailingAnchor.constraint(equalTo: bg.contentView.trailingAnchor, constant: -16),
            top ? bg.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8) : bg.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -8)])
    }
    private func applyTheme() {
        let theme = UserDefaults.standard.integer(forKey: "nativeTheme")
        overrideUserInterfaceStyle = theme == 1 ? .light : theme == 2 ? .dark : .unspecified
        signature = ""
    }
    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) { super.traitCollectionDidChange(previousTraitCollection); signature = ""; if isViewLoaded { refresh() } }
    @objc private func refresh() {
        refreshMetrics()
        if !centered, let p = engine.position { centered = true; map.setRegion(MKCoordinateRegion(center: p.coordinate, latitudinalMeters: 1200, longitudinalMeters: 1200), animated: true) }
        let sessions = (engine.state.sessions + [engine.state.active].compactMap { $0 }).filter { layer == nil || $0.mode == layer }
        let key = "\(layer?.rawValue ?? "all"):\(sessions.map { "\($0.id):\($0.points.count)" }.joined(separator: ",")):\(traitCollection.userInterfaceStyle.rawValue)"
        if signature != key {
            signature = key
            if let fog { map.removeOverlay(fog) }
            let next = Fog(sessions); fog = next; map.addOverlay(next, level: .aboveLabels)
        }
        if let error = engine.takeError(), presentedViewController == nil { alert("Сохранение", error) }
    }
    private func refreshMetrics() {
        status.text = engine.message
        modes.isEnabled = engine.state.active == nil && engine.pendingMode == nil
        if let s = engine.state.active {
            let seconds = Int(s.duration()); metric.text = String(format: "%.2f км   %02d:%02d", s.distance/1000, seconds/60, seconds%60)
            modes.selectedSegmentIndex = TravelMode.allCases.firstIndex(of: s.mode) ?? 0
        } else { metric.text = "Твой мир. Твой путь." }
        start.setTitle(engine.pendingMode != nil ? "Отменить ожидание" : engine.state.active == nil ? "Начать прогулку ↗" : engine.recording ? "Пауза" : "Продолжить", for: .normal)
        start.isEnabled = !engine.blocked; finish.isHidden = engine.state.active == nil
    }
    @objc private func toggleRecording() {
        if engine.pendingMode != nil { engine.cancelPending() }
        else if engine.recording { engine.pause() }
        else if engine.state.active != nil { engine.resume() }
        else { engine.begin(selectedMode) }
    }
    @objc private func endRecording() { engine.finish() }
    @objc private func locate() { engine.locate(); map.setUserTrackingMode(.follow, animated: true); if !engine.authorized { settingsPermission() } }
    private func settingsPermission() {
        let a = UIAlertController(title: "Геопозиция", message: "В настройках Terra включи доступ к геопозиции и точную геопозицию.", preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "Настройки iPhone", style: .default) { _ in if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) } }); a.addAction(UIAlertAction(title: "Закрыть", style: .cancel)); present(a, animated: true)
    }
    private func sheet(_ title: String) -> UIAlertController { UIAlertController(title: title, message: nil, preferredStyle: .actionSheet) }
    private func show(_ a: UIAlertController) { a.addAction(UIAlertAction(title: "Закрыть", style: .cancel)); a.popoverPresentationController?.sourceView = view; a.popoverPresentationController?.sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.midY, width: 1, height: 1); present(a, animated: true) }
    @objc private func layers() {
        let a = sheet("Открытые территории")
        a.addAction(UIAlertAction(title: "Все способы", style: .default) { _ in self.layer = nil; self.refresh() })
        for m in TravelMode.allCases { a.addAction(UIAlertAction(title: m.title, style: .default) { _ in self.layer = m; self.refresh() }) }; show(a)
    }
    @objc private func history() {
        let controller = HistoryController(sessions: engine.state.sessions) { [weak self] s in
            guard let self, let first = s.points.first else { return }
            self.layer = s.mode; self.refresh()
            var bounds = MKMapRect(origin: MKMapPoint(first.coordinate), size: MKMapSize(width: 1, height: 1))
            for p in s.points { bounds = bounds.union(MKMapRect(origin: MKMapPoint(p.coordinate), size: MKMapSize(width: 1, height: 1))) }
            self.map.setVisibleMapRect(bounds, edgePadding: UIEdgeInsets(top: 140, left: 50, bottom: 340, right: 50), animated: true)
        }
        present(UINavigationController(rootViewController: controller), animated: true)
    }
    @objc private func settings() {
        let a = sheet("Terra · 2.0")
        for (name,value) in [("Тема системы",0),("Светлая тема",1),("Тёмная тема",2)] { a.addAction(UIAlertAction(title: name, style: .default) { _ in UserDefaults.standard.set(value, forKey: "nativeTheme"); self.applyTheme(); self.refresh() }) }
        a.addAction(UIAlertAction(title: "Разрешения геопозиции", style: .default) { _ in self.settingsPermission() })
        a.addAction(UIAlertAction(title: "Экспорт маршрутов", style: .default) { _ in
            do { let c = UIActivityViewController(activityItems: [try self.engine.exportURL()], applicationActivities: nil); c.popoverPresentationController?.sourceView = self.view; self.present(c, animated: true) } catch { self.alert("Экспорт", error.localizedDescription) }
        })
        a.addAction(UIAlertAction(title: "Импорт маршрутов", style: .default) { _ in let p = UIDocumentPickerViewController(forOpeningContentTypes: [.json]); p.delegate = self; self.present(p, animated: true) })
        a.addAction(UIAlertAction(title: "О записи в фоне", style: .default) { _ in self.alert("Запись маршрута", "Нажми «Начать прогулку» и дождись GPS. После этого можно заблокировать экран. Не закрывай Terra смахиванием из списка приложений: iOS остановит запись. Радиус открытия — 35 м. Маршруты хранятся на этом телефоне; сохраняй резервную копию перед переустановкой.") }); show(a)
    }
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else { return }; let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
        do { try engine.importData(Data(contentsOf: url)) } catch { alert("Импорт", error.localizedDescription) }
    }
    private func alert(_ title: String, _ message: String) { let a = UIAlertController(title: title, message: message, preferredStyle: .alert); a.addAction(UIAlertAction(title: "Понятно", style: .default)); present(a, animated: true) }
    func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer { let r = FogRenderer(overlay: overlay); r.dark = traitCollection.userInterfaceStyle == .dark; return r }
}

final class HistoryController: UITableViewController {
    let sessions: [TrackSession], select: (TrackSession) -> Void
    init(sessions: [TrackSession], select: @escaping (TrackSession) -> Void) { self.sessions = sessions.reversed(); self.select = select; super.init(style: .insetGrouped) }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func viewDidLoad() { super.viewDidLoad(); title = "История"; navigationItem.rightBarButtonItem = UIBarButtonItem(barButtonSystemItem: .done, target: self, action: #selector(close)); if sessions.isEmpty { let l = UILabel(); l.text = "Здесь появятся твои прогулки"; l.textAlignment = .center; tableView.backgroundView = l } }
    @objc private func close() { dismiss(animated: true) }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { sessions.count }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let s = sessions[indexPath.row], c = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
        c.textLabel?.text = "\(s.mode.title) · \(String(format: "%.2f", s.distance/1000)) км"
        c.detailTextLabel?.text = Date(timeIntervalSince1970: s.startedAt/1000).formatted(date: .abbreviated, time: .shortened) + " · \(Int(s.duration()/60)) мин"; c.imageView?.image = UIImage(systemName: s.mode.symbol); c.accessoryType = .disclosureIndicator; return c
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) { let s = sessions[indexPath.row]; dismiss(animated: true) { self.select(s) } }
}
