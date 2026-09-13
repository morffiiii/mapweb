import UIKit
import MapKit
import PhotosUI

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

final class MapController: UIViewController, MKMapViewDelegate, PHPickerViewControllerDelegate {
    private let map = MKMapView()
    private let engine = TrackingEngine.shared
    private let activity = UILabel()
    private let status = UILabel(), metric = UILabel(), start = UIButton(type: .system), finish = UIButton(type: .system)
    private let modes = UISegmentedControl(items: TravelMode.allCases.map(\.title))
    private let accent = UIColor(red: 1, green: 0.36, blue: 0.12, alpha: 1)
    private var layer: TravelMode?, centered = false, fog: Fog?, signature = "", timer: Timer?
    private weak var bottomPanel: UIView?
    private var following = false, wasRecording = false
    private var showFog: Bool { UserDefaults.standard.object(forKey: "showFog") as? Bool ?? true }
    private var showPlaces: Bool { UserDefaults.standard.object(forKey: "showPlaces") as? Bool ?? true }
    private var photoHandler: ((UIImage) -> Void)?
    private var selectedMode: TravelMode { TravelMode.allCases[max(0, modes.selectedSegmentIndex)] }
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground; view.tintColor = accent
        applyTheme()
        map.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(map)
        NSLayoutConstraint.activate([map.topAnchor.constraint(equalTo: view.topAnchor), map.bottomAnchor.constraint(equalTo: view.bottomAnchor), map.leadingAnchor.constraint(equalTo: view.leadingAnchor), map.trailingAnchor.constraint(equalTo: view.trailingAnchor)])
        map.delegate = self; map.showsUserLocation = true; map.pointOfInterestFilter = .excludingAll
        map.accessibilityIdentifier = "nativeMap"
        map.isAccessibilityElement = true; map.accessibilityLabel = "Карта открытий"; map.accessibilityTraits = .allowsDirectInteraction
        map.addGestureRecognizer(UILongPressGestureRecognizer(target: self, action: #selector(markPlace(_:))))
        refreshPlaces()
        let top = UIStackView(); top.axis = .horizontal; top.distribution = .equalSpacing
        let title = UILabel(); title.text = "TERRA ↗"; title.font = .systemFont(ofSize: 27, weight: .black)
        let tools = UIStackView(); tools.spacing = 4
        tools.addArrangedSubview(button("Слои", "square.3.layers.3d", #selector(layers)))
        tools.addArrangedSubview(button("Настройки", "slider.horizontal.3", #selector(settings)))
        top.addArrangedSubview(tools); top.addArrangedSubview(title)
        place(top, top: true)
        let panel = UIStackView(); panel.axis = .vertical; panel.spacing = 14
        status.font = .systemFont(ofSize: 12, weight: .medium); status.textColor = .secondaryLabel; status.numberOfLines = 2
        status.accessibilityIdentifier = "gpsStatus"
        metric.font = .monospacedDigitSystemFont(ofSize: 27, weight: .semibold)
        metric.accessibilityIdentifier = "sessionMetrics"
        modes.selectedSegmentIndex = 0; modes.accessibilityIdentifier = "travelModes"
        activity.font = .monospacedDigitSystemFont(ofSize: 14, weight: .medium); activity.textColor = .secondaryLabel; activity.accessibilityIdentifier = "activityMetrics"
        panel.addArrangedSubview(status); panel.addArrangedSubview(metric); panel.addArrangedSubview(activity); panel.addArrangedSubview(modes)
        let actions = UIStackView(); actions.spacing = 10; actions.distribution = .fillEqually
        start.addTarget(self, action: #selector(toggleRecording), for: .touchUpInside); start.accessibilityIdentifier = "startRecording"
        var c = UIButton.Configuration.filled(); c.baseBackgroundColor = accent; c.baseForegroundColor = .white; c.cornerStyle = .large; c.contentInsets = NSDirectionalEdgeInsets(top: 16, leading: 12, bottom: 16, trailing: 12); start.configuration = c
        finish.configuration = .tinted(); finish.setTitle("Завершить", for: .normal); finish.addTarget(self, action: #selector(endRecording), for: .touchUpInside)
        actions.addArrangedSubview(start); actions.addArrangedSubview(finish); panel.addArrangedSubview(actions)
        let nav = UIStackView(); nav.distribution = .equalSpacing
        nav.addArrangedSubview(button("История", "clock", #selector(history)))
        nav.addArrangedSubview(button("Где я", "location.fill", #selector(locate)))
        nav.addArrangedSubview(button("Профиль", "person.crop.circle", #selector(profile)))
        panel.addArrangedSubview(nav); place(panel, top: false)
        NotificationCenter.default.addObserver(self, selector: #selector(refresh), name: TrackingEngine.changed, object: nil)
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.refreshMetrics() }
        refresh()
    }
    override func viewDidAppear(_ animated: Bool) { super.viewDidAppear(animated); engine.foreground(true); engine.locate() }
    deinit { timer?.invalidate(); NotificationCenter.default.removeObserver(self) }
    private func button(_ title: String, _ icon: String, _ action: Selector) -> UIButton {
        let b = UIButton(type: .system); var c = UIButton.Configuration.plain(); c.image = UIImage(systemName: icon); c.preferredSymbolConfigurationForImage = UIImage.SymbolConfiguration(pointSize: 22, weight: .medium); c.contentInsets = NSDirectionalEdgeInsets(top: 12, leading: 12, bottom: 12, trailing: 12); b.configuration = c; b.accessibilityLabel = title; b.accessibilityIdentifier = title; b.addTarget(self, action: action, for: .touchUpInside); return b
    }
    private func place(_ stack: UIStackView, top: Bool) {
        let bg = UIVisualEffectView(effect: UIBlurEffect(style: .systemMaterial)); bg.layer.cornerRadius = 24; bg.clipsToBounds = true
        bg.translatesAutoresizingMaskIntoConstraints = false; stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(bg); bg.contentView.addSubview(stack)
        if !top { bottomPanel = bg }
        NSLayoutConstraint.activate([bg.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 14), bg.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -14),
            stack.topAnchor.constraint(equalTo: bg.contentView.topAnchor, constant: 16), stack.bottomAnchor.constraint(equalTo: bg.contentView.bottomAnchor, constant: -16), stack.leadingAnchor.constraint(equalTo: bg.contentView.leadingAnchor, constant: 16), stack.trailingAnchor.constraint(equalTo: bg.contentView.trailingAnchor, constant: -16),
            top ? bg.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8) : bg.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -8)])
    }
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        map.layoutMargins = UIEdgeInsets(top: 110, left: 16, bottom: bottomPanel.map { view.bounds.height-$0.frame.minY+8 } ?? 320, right: 16)
    }
    private func applyTheme() {
        let theme = UserDefaults.standard.integer(forKey: "nativeTheme")
        overrideUserInterfaceStyle = theme == 1 ? .light : theme == 2 ? .dark : .unspecified
        signature = ""
    }
    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) { super.traitCollectionDidChange(previousTraitCollection); signature = ""; if isViewLoaded { refresh() } }
    @objc private func refresh() {
        refreshMetrics()
        if engine.recording && !wasRecording { following = true }
        wasRecording = engine.recording
        if !centered, let p = engine.position { centered = true; map.setRegion(MKCoordinateRegion(center: p.coordinate, latitudinalMeters: 1200, longitudinalMeters: 1200), animated: true) }
        if following, engine.recording, let p = engine.position { map.setRegion(MKCoordinateRegion(center: p.coordinate, latitudinalMeters: 1000, longitudinalMeters: 1000), animated: true) }
        let sessions = (engine.state.sessions + [engine.state.active].compactMap { $0 }).filter { layer == nil || $0.mode.category == layer }
        let key = "\(layer?.rawValue ?? "all"):\(sessions.map { "\($0.id):\($0.points.count)" }.joined(separator: ",")):\(traitCollection.userInterfaceStyle.rawValue)"
        if signature != key {
            signature = key
            if let fog { map.removeOverlay(fog) }
            let next = Fog(sessions); fog = next; if showFog { map.addOverlay(next, level: .aboveLabels) }
        }
        if let error = engine.takeError(), presentedViewController == nil { alert("Сохранение", error) }
    }
    private func refreshMetrics() {
        status.text = engine.message
        activity.isHidden = engine.state.active == nil
        if let s = engine.state.active { activity.text = String(format: "%.1f км/ч · ≈ %.0f активных ккал", ActivityMetrics.speed(s), ActivityMetrics.calories(s, weight: PersonalStore.shared.data.weight)) }
        modes.isEnabled = engine.state.active == nil && engine.pendingMode == nil
        if let s = engine.state.active {
            let seconds = Int(s.duration()); metric.text = String(format: "%.2f км   %02d:%02d", s.distance/1000, seconds/60, seconds%60)
            modes.selectedSegmentIndex = TravelMode.allCases.firstIndex(of: s.mode.category) ?? 0
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
    @objc private func endRecording() { let session = engine.state.active; engine.finish(); if engine.state.active == nil, let session { replay(session) } }
    @objc private func locate() { following = true; engine.locate(); if let p = engine.position { map.setRegion(MKCoordinateRegion(center: p.coordinate, latitudinalMeters: 1000, longitudinalMeters: 1000), animated: true) }; if !engine.authorized { settingsPermission() } }

    @discardableResult private func page(_ title: String) -> TerraPage {
        let p = TerraPage(title); addChild(p); p.view.frame = view.bounds; p.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]; view.addSubview(p.view); p.didMove(toParent: self)
        p.view.alpha = 0; UIView.animate(withDuration: 0.22) { p.view.alpha = 1 }; return p
    }
    private func closePages() {
        for child in children where child is TerraPage { child.willMove(toParent: nil); child.view.removeFromSuperview(); child.removeFromParent() }
    }
    private func settingsPermission() {
        let p = page("Геопозиция")
        p.text("Для записи нужна точная геопозиция. Включи её в настройках Terra на iPhone.")
        p.action("Открыть настройки", icon: "location") { if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) } }
    }
    @objc private func settings() {
        let p = page("Настройки")
        p.text("Внешний вид")
        for (name,value,icon) in [("Как на телефоне",0,"circle.lefthalf.filled"),("Светлая",1,"sun.max"),("Тёмная",2,"moon")] {
            p.action(name, detail: UserDefaults.standard.integer(forKey: "nativeTheme") == value ? "Выбрана" : "", icon: icon) { [weak self, weak p] in
                UserDefaults.standard.set(value, forKey: "nativeTheme"); self?.applyTheme(); self?.refresh(); p?.close()
            }
        }
        p.text("Маршрут начинает записываться сразу. Если GPS ещё уточняется, первые точные координаты добавятся автоматически. Не закрывай Terra смахиванием во время записи.")
        p.action("Геопозиция", icon: "location.fill") { [weak self] in self?.settingsPermission() }
        p.text("Карта: Apple Maps. Подключение Яндекс Карт требует ключа MapKit.\nTerra · 2.1\nМаршруты и личные места хранятся на этом телефоне.")
    }
    @objc private func layers() {
        let p = page("Слои")
        p.text("Выбери, открытия каких способов показывать на карте.")
        for (title,key,value) in [("Неоткрытые участки","showFog",showFog),("Мои места","showPlaces",showPlaces)] {
            let row = UIStackView(); row.alignment = .center; let label = UILabel(); label.text = title; label.textColor = .label; row.addArrangedSubview(label)
            let toggle = UISwitch(); toggle.isOn = value; toggle.accessibilityLabel = title; toggle.addAction(UIAction { [weak self,weak toggle] _ in UserDefaults.standard.set(toggle?.isOn ?? true,forKey: key); self?.signature = ""; self?.refresh(); self?.refreshPlaces() },for: .valueChanged); row.addArrangedSubview(toggle); p.content.addArrangedSubview(row)
        }
        p.action("Неизведанное рядом",icon: "sparkle.magnifyingglass") { [weak self] in self?.nearby() }
        p.action("Все способы", detail: layer == nil ? "Выбран общий слой" : "Показать всё", icon: "square.3.layers.3d") { [weak self, weak p] in self?.layer = nil; self?.refresh(); p?.close() }
        for mode in TravelMode.allCases { p.action(mode.title, detail: layer == mode ? "Выбран" : "", icon: mode.symbol, color: mode.color) { [weak self, weak p] in self?.layer = mode; self?.refresh(); p?.close() } }
    }
    @objc private func history() { showHistory(mode: nil) }
    private func showHistory(mode: TravelMode?) {
        let p = page("История")
        if engine.state.sessions.isEmpty { p.text("Первый маршрут ещё впереди.", large: true); p.text("Начни прогулку — здесь останутся её путь, время и открытые места.") }
        for s in engine.state.sessions.reversed() where mode == nil || s.mode.category == mode {
            let date = Date(timeIntervalSince1970: s.startedAt/1000).formatted(date: .abbreviated, time: .shortened)
            p.action("\(s.mode.title) · \(String(format: "%.2f", s.distance/1000)) км", detail: "\(date) · \(Int(s.duration()/60)) мин · Открыть карту", icon: "play.circle", color: s.mode.color) { [weak self] in self?.replay(s) }
        }
    }
    @objc private func profile() {
        let p = page("Твой профиль"), personal = PersonalStore.shared
        let avatar = UIImageView(); avatar.contentMode = .scaleAspectFill; avatar.clipsToBounds = true; avatar.layer.cornerRadius = 44; avatar.tintColor = accent
        avatar.image = personal.data.avatar.flatMap { UIImage(contentsOfFile: personal.directory.appendingPathComponent($0).path) } ?? UIImage(systemName: "person.crop.circle.fill")
        let avatarRow = UIStackView(); avatarRow.addArrangedSubview(avatar); avatar.widthAnchor.constraint(equalToConstant: 88).isActive = true; avatar.heightAnchor.constraint(equalToConstant: 88).isActive = true;  p.content.addArrangedSubview(avatarRow)
        let name = UILabel(); name.text = personal.data.name; name.font = .systemFont(ofSize: 28, weight: .bold); name.numberOfLines = 2
        name.setContentCompressionResistancePriority(.defaultLow,for: .horizontal); avatarRow.spacing = 18; avatarRow.alignment = .center; avatarRow.addArrangedSubview(name)
        let sessions = engine.state.sessions, km = sessions.reduce(0) { $0+$1.distance }/1000
        p.text(String(format: "%.2f км", km), large: true)
        p.text("\(sessions.count) маршрутов · \(Int(sessions.reduce(0) { $0+$1.duration() }/60)) минут")
        let streak = WalkingStreak.status(walked: WalkingStreak.days(sessions), restores: personal.data.restores ?? [])
        let rhythm = UIStackView(); rhythm.axis = .horizontal; rhythm.spacing = 18; rhythm.alignment = .center; rhythm.isLayoutMarginsRelativeArrangement = true; rhythm.layoutMargins = UIEdgeInsets(top: 20,left: 18,bottom: 20,right: 18); rhythm.backgroundColor = .secondarySystemBackground; rhythm.layer.cornerRadius = 24
        let mark = RhythmMark(); mark.widthAnchor.constraint(equalToConstant: 64).isActive = true; mark.heightAnchor.constraint(equalToConstant: 72).isActive = true; rhythm.addArrangedSubview(mark)
        let rhythmText = UILabel(); rhythmText.text = "РИТМ\n\(streak.count) дней подряд"; rhythmText.font = .systemFont(ofSize: 24,weight: .bold); rhythmText.numberOfLines = 2; rhythm.addArrangedSubview(rhythmText); p.content.addArrangedSubview(rhythm)
        let weekRow = UIStackView(); weekRow.distribution = .fillEqually; weekRow.spacing = 5
        let walked = WalkingStreak.days(sessions), restored = Set((personal.data.restores ?? []).map(\.day))
        let dayFormatter = DateFormatter(); dayFormatter.dateFormat = "EE"
        for offset in -6...0 { let date = Calendar.current.date(byAdding: .day,value: offset,to: Date())!, key = WalkingStreak.key(date)
            let day = UILabel(); day.numberOfLines = 2; day.textAlignment = .center; day.font = .systemFont(ofSize: 12,weight: .semibold); day.text = dayFormatter.string(from: date)+"\n"+(walked.contains(key) ? "●" : restored.contains(key) ? "↻" : "○"); day.textColor = walked.contains(key) ? accent : restored.contains(key) ? .systemPurple : .secondaryLabel; day.accessibilityLabel = key+(walked.contains(key) ? " — прогулка" : restored.contains(key) ? " — восстановлен" : " — нет прогулки"); weekRow.addArrangedSubview(day)
        }; p.content.addArrangedSubview(weekRow)
        p.text((streak.walkedToday ? "Сегодня прогулка засчитана." : "Прогулка от 5 минут с движением продолжит серию.")+"\nВосстановлений в этом месяце: \(streak.remaining) из 3.")
        if streak.canRestore { p.action("Восстановить серию", detail: "Закрыть вчерашний пропуск · 1 восстановление", icon: "arrow.counterclockwise") { [weak self, weak p] in
            let current = WalkingStreak.status(walked: WalkingStreak.days(self?.engine.state.sessions ?? []), restores: personal.data.restores ?? [])
            guard current.canRestore else { return }
            do { try personal.update { if $0.restores == nil { $0.restores = [] }; $0.restores?.append(StreakRestore(day: current.yesterday, usedOn: current.today)) }; p?.close(); self?.profile() } catch { self?.alert("Серия", error.localizedDescription) }
        } }
        p.text("ТВОИ РАЗДЕЛЫ")
        p.action("Статистика", detail: "Все маршруты и способы передвижения", icon: "chart.bar") { [weak self] in self?.statistics() }
        p.action("Мои места", detail: "\(personal.data.places.count) заметок · удерживай карту, чтобы добавить", icon: "mappin.and.ellipse") { [weak self] in self?.places() }
        p.action("Мои цели", detail: "Расстояние, маршруты, открытая площадь", icon: "target") { [weak self] in self?.goals() }
        p.action("Достижения", detail: "Твои открытия в цифрах и наградах", icon: "medal") { [weak self] in self?.achievements() }
        p.action("Личные данные",detail: "Имя, возраст, рост, вес и интересы",icon: "person.text.rectangle") { [weak self] in self?.editProfile() }
        p.action("Выйти",detail: "Маршруты останутся на этом телефоне",icon: "person.crop.circle") { [weak self] in
            guard self?.engine.state.active == nil else { self?.alert("Маршрут","Сначала заверши текущую запись."); return }; UserDefaults.standard.set(false,forKey: "localSession"); self?.engine.foreground(false); self?.view.window?.rootViewController = WelcomeController()
        }
    }
    private func editProfile() {
        let p = page("Личные данные"), store = PersonalStore.shared
        let personal = store
        p.action("Изменить фото",icon: "camera") { [weak self,weak p] in
            var config = PHPickerConfiguration(); config.filter = .images; config.selectionLimit = 1; let picker = PHPickerViewController(configuration: config); picker.delegate = self
            self?.photoHandler = { [weak self,weak p] image in
                do { let scale = min(1,800/max(image.size.width,image.size.height)); let size = CGSize(width: image.size.width*scale,height: image.size.height*scale); let resized = UIGraphicsImageRenderer(size: size).image { _ in image.draw(in: CGRect(origin: .zero,size: size)) }; guard let bytes = resized.jpegData(compressionQuality: 0.85) else { throw TrackError.storage }
                    try FileManager.default.createDirectory(at: personal.directory,withIntermediateDirectories: true); try bytes.write(to: personal.directory.appendingPathComponent("avatar.jpg"),options: .atomic); try personal.update { $0.avatar = "avatar.jpg" }; p?.close(); self?.profile()
                } catch { self?.alert("Фото",error.localizedDescription) }
            }; self?.present(picker,animated: true)
        }

        let values: [(String,String)] = [("Имя",store.data.name),("Возраст",store.data.age.map(String.init) ?? ""),("Рост, см",store.data.height.map { String($0) } ?? ""),("Вес, кг",store.data.weight.map { String($0) } ?? "")]
        var fields: [UITextField] = []
        for (i,value) in values.enumerated() { p.text(value.0); let f = UITextField(); f.text = value.1; f.borderStyle = .roundedRect; f.keyboardType = i == 0 ? .default : i == 1 ? .numberPad : .decimalPad; p.content.addArrangedSubview(f); fields.append(f) }
        p.text("Все поля необязательны. Очисти значение, чтобы удалить его.")
        p.action("Сохранить",icon: "checkmark") { [weak self,weak p] in
            let a = fields[1].text ?? "", h = (fields[2].text ?? "").replacingOccurrences(of: ",",with: "."), w = (fields[3].text ?? "").replacingOccurrences(of: ",",with: ".")
            guard (a.isEmpty || (Int(a).map { (1...120).contains($0) } ?? false)),(h.isEmpty || (Double(h).map { (50...250).contains($0) } ?? false)),(w.isEmpty || (Double(w).map { (10...400).contains($0) } ?? false)) else { self?.alert("Данные","Проверь возраст, рост и вес или оставь поля пустыми."); return }
            do { try store.update { $0.name = fields[0].text?.isEmpty == false ? String(fields[0].text!.prefix(60)) : "Исследователь"; $0.age = Int(a); $0.height = Double(h); $0.weight = Double(w) }; p?.close(); self?.closePages(); self?.profile() } catch { self?.alert("Данные",error.localizedDescription) }
        }
        p.text("Интересы · изменения сохраняются сразу")
        for option in ["Больше гулять","Открывать новые места","Кататься на велосипеде","Следить за активностью"] {
            let b = UIButton(type: .system); var c = UIButton.Configuration.tinted(); c.title = option; c.imagePadding = 12; c.contentInsets = NSDirectionalEdgeInsets(top: 18,leading: 16,bottom: 18,trailing: 16); c.baseForegroundColor = .label; c.cornerStyle = .large; b.configuration = c
            func update(_ b: UIButton) { let active = store.data.intentions?.contains(option) == true; b.configuration?.image = UIImage(systemName: active ? "checkmark.circle.fill" : "circle"); b.configuration?.baseBackgroundColor = active ? TravelMode.walk.color : .secondarySystemBackground; b.accessibilityValue = active ? "Выбрано" : "Не выбрано" }
            update(b); b.addAction(UIAction { [weak self, weak b] _ in
                do { try store.update { var choices = $0.intentions ?? []; if choices.contains(option) { choices.removeAll { $0 == option } } else { choices.append(option) }; $0.intentions = choices }; if let b { update(b) } } catch { self?.alert("Данные",error.localizedDescription) }
            },for: .touchUpInside); p.content.addArrangedSubview(b)
        }
    }
    private func statistics() {
        let p = page("Статистика"), sessions = engine.state.sessions
        p.text(String(format: "%.2f км",sessions.reduce(0) { $0+$1.distance }/1000),large: true)
        p.text("\(sessions.count) маршрутов · \(Int(sessions.reduce(0) { $0+$1.duration() }/60)) минут")
        let area = p.text("Считаем открытую площадь…")
        DispatchQueue.global(qos: .userInitiated).async { let cells = Discovery.cells(sessions); DispatchQueue.main.async { area.text = String(format: "≈ %.3f км² открыто",Double(cells.count)*0.0004) } }
        for m in TravelMode.allCases { let routes = sessions.filter { $0.mode.category == m }; p.action(m.title,detail: String(format: "%.2f км · %d маршрутов",routes.reduce(0) { $0+$1.distance }/1000,routes.count),icon: m.symbol,color: m.color) { [weak self] in self?.showHistory(mode: m) } }
        let calories = sessions.reduce(0) { $0+ActivityMetrics.calories($1,weight: PersonalStore.shared.data.weight) }
        p.text(String(format: "≈ %.0f активных ккал",calories),large: true)
        p.text("Оценка для ходьбы и велосипеда по скорости, времени движения и весу. Без веса используем 70 кг. Возраст и рост в этой формуле не участвуют. Паузы и поездки на авто не добавляют активных калорий; это не измерение расхода энергии.")
    }
    private func goals() {
        let p = page("Мои цели"), store = PersonalStore.shared, sessions = engine.state.sessions
        p.text("Задавай свои ориентиры. Прогресс учитывает все завершённые маршруты.")
        let name = UITextField(); name.placeholder = "Например, исследовать город"; name.borderStyle = .roundedRect; p.content.addArrangedSubview(name)
        let kind = UISegmentedControl(items: ["Километры", "Маршруты", "км²"]); kind.selectedSegmentIndex = 0; p.content.addArrangedSubview(kind)
        let target = UITextField(); target.placeholder = "Цель, например 10"; target.keyboardType = .decimalPad; target.borderStyle = .roundedRect; p.content.addArrangedSubview(target)
        p.action("Добавить цель", icon: "plus") { [weak self, weak p, weak name, weak target, weak kind] in
            guard let value = Double((target?.text ?? "").replacingOccurrences(of: ",", with: ".")), value.isFinite, value > 0, value <= 1_000_000 else { self?.alert("Цель", "Введи положительное число до 1 000 000."); return }
            let goal = PersonalGoal(title: String((name?.text?.isEmpty == false ? name!.text! : "Моя цель").prefix(80)), kind: ["km","routes","area"][kind?.selectedSegmentIndex ?? 0], target: value)
            do { try store.update { if $0.goals == nil { $0.goals = [] }; $0.goals?.append(goal) }; p?.close(); self?.goals() } catch { self?.alert("Цель", error.localizedDescription) }
        }
        let goals = store.data.goals ?? []
        DispatchQueue.global(qos: .userInitiated).async {
            let km = sessions.reduce(0) { $0+$1.distance }/1000, area = Double(Discovery.cells(sessions).count)*0.0004
            DispatchQueue.main.async { [weak self, weak p] in guard let p else { return }
                for goal in goals {
                    let value = goal.kind == "km" ? km : goal.kind == "routes" ? Double(sessions.count) : area
                    let unit = goal.kind == "km" ? "км" : goal.kind == "routes" ? "маршрутов" : "км²"
                    p.text(goal.title, large: true); p.text(String(format: "%.2f / %.2f %@ · %.0f%%", value, goal.target, unit, min(100,value/goal.target*100)))
                    let bar = UIProgressView(progressViewStyle: .default); bar.progressTintColor = self?.accent; bar.progress = Float(min(1,value/goal.target)); p.content.addArrangedSubview(bar)
                    p.action(value >= goal.target ? "Достигнуто · убрать цель" : "Убрать цель", icon: value >= goal.target ? "checkmark.seal" : "minus.circle") { [weak self, weak p] in do { try store.update { $0.goals?.removeAll { $0.id == goal.id } }; p?.close(); self?.goals() } catch { self?.alert("Цель", error.localizedDescription) } }
                }
            }
        }
    }
    private func achievements() {
        let p = page("Достижения"), sessions = engine.state.sessions
        p.text("Открываются по твоим настоящим маршрутам. Каждый шаг остаётся частью истории.")
        let days = Set(sessions.map { Calendar.current.startOfDay(for: Date(timeIntervalSince1970: $0.startedAt/1000)) }).sorted()
        var longest = 0, streak = 0, previous: Date?
        for day in days { streak = previous.flatMap { Calendar.current.dateComponents([.day], from: $0, to: day).day } == 1 ? streak+1 : 1; longest = max(longest,streak); previous = day }
        let walkKm = sessions.filter { $0.mode.category == .walk }.reduce(0) { $0+$1.distance }/1000
        let awards: [(String,String,Bool)] = [
            ("Первый след","Заверши маршрут с координатами",sessions.contains { !$0.points.isEmpty }),
            ("Пешком интереснее","Пройди 10 км пешком",walkKm >= 10),
            ("Исследователь","Заверши 10 маршрутов",sessions.count >= 10),
            ("Неделя открытий","7 дней подряд с маршрутами",longest >= 7),
            ("Коллекционер мест","Сохрани 5 личных мест",PersonalStore.shared.data.places.count >= 5),
            ("Разными путями","Маршруты тремя способами",Set(sessions.filter { !$0.points.isEmpty }.map { $0.mode.category }).count >= 3)]
        for (title,detail,earned) in awards { p.action(title, detail: (earned ? "Получено · " : "Ещё впереди · ")+detail, icon: earned ? "medal.fill" : "lock", color: earned ? accent : .secondaryLabel) {} }
    }
    private func nearby() {
        guard let pos = engine.position else { alert("Геопозиция", "Для поиска нужны текущие координаты."); return }
        let p = page("Рядом с тобой"); p.text("Неоткрытые участки, расстояние по прямой. Доступность прохода проверяй на месте.")
        let sessions = engine.state.sessions+[engine.state.active].compactMap { $0 }
        DispatchQueue.global(qos: .userInitiated).async {
            let found = Explore.nearby(pos.coordinate, cells: Discovery.cells(sessions))
            DispatchQueue.main.async { [weak self, weak p] in
                guard let self, let p else { return }
                if found.isEmpty { p.text("Рядом всё исследовано. Попробуй поиск из другого места.") }
                for point in found { let meters = CLLocation(latitude: point.lat, longitude: point.lng).distance(from: pos)
                    p.action("Неоткрытый участок", detail: "\(Int(meters)) м от тебя", icon: "arrow.up.right") { [weak self, weak p] in
                        self?.following = false; self?.map.setRegion(MKCoordinateRegion(center: point.coordinate, latitudinalMeters: 700, longitudinalMeters: 700), animated: true)
                        let pin = MKPointAnnotation(); pin.coordinate = point.coordinate; pin.title = "Неоткрытый участок"; self?.map.addAnnotation(pin); self?.closePages()
                    }
                }
            }
        }
    }
    private func replay(_ session: TrackSession) {
        let p = RoutePage(session); addChild(p); p.view.frame = view.bounds; p.view.autoresizingMask = [.flexibleWidth,.flexibleHeight]; view.addSubview(p.view); p.didMove(toParent: self)
    }
    private func refreshPlaces() {
        map.removeAnnotations(map.annotations.filter { $0 is PlacePin })
        if showPlaces { for place in PersonalStore.shared.data.places { map.addAnnotation(PlacePin(place)) } }
    }
    func mapView(_ mapView: MKMapView, didSelect view: MKAnnotationView) {
        guard let pin = view.annotation as? PlacePin else { return }; mapView.deselectAnnotation(pin,animated: false); placeDetails(pin.place)
    }
    private func placeDetails(_ place: Place) {
        let p = page("Место")
        p.text(place.note.isEmpty ? "Моё место" : place.note,large: true)
        if let path = place.photo, let image = UIImage(contentsOfFile: PersonalStore.shared.directory.appendingPathComponent(path).path) { let v = UIImageView(image: image); v.contentMode = .scaleAspectFit; v.heightAnchor.constraint(equalToConstant: 240).isActive = true; p.content.addArrangedSubview(v) }
        p.action("Показать на карте",icon: "map") { [weak self] in self?.following = false; self?.map.setRegion(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: place.lat,longitude: place.lng),latitudinalMeters: 700,longitudinalMeters: 700),animated: true); self?.closePages() }
        p.action("Редактировать",icon: "pencil") { [weak self] in self?.editPlace(place) }
        p.action("Удалить место",icon: "trash",color: .systemRed) { [weak self] in
            guard let self else { return }; let confirm = self.page("Удалить место?"); confirm.text("Заметка исчезнет из твоих мест и с карты.")
            confirm.action("Удалить",icon: "trash",color: .systemRed) { [weak self] in do { try PersonalStore.shared.update { $0.places.removeAll { $0.id == place.id } }; self?.refreshPlaces(); self?.closePages(); self?.places() } catch { self?.alert("Место",error.localizedDescription) } }
        }
    }
    @objc private func markPlace(_ gesture: UILongPressGestureRecognizer) {
        guard gesture.state == .began else { return }; let coordinate = map.convert(gesture.location(in: map), toCoordinateFrom: map)
        editPlace(Place(lat: coordinate.latitude,lng: coordinate.longitude,note: ""),isNew: true)
    }
    private func editPlace(_ original: Place, isNew: Bool = false) {
        let p = page(isNew ? "Новое место" : "Редактировать место"), text = UITextView(); text.font = .systemFont(ofSize: 18); text.backgroundColor = .secondarySystemBackground; text.layer.cornerRadius = 16; text.heightAnchor.constraint(equalToConstant: 140).isActive = true
        text.text = original.note
        p.text("Что хочется запомнить здесь?"); p.content.addArrangedSubview(text)
        let preview = UIImageView(); preview.contentMode = .scaleAspectFit; preview.heightAnchor.constraint(equalToConstant: 180).isActive = true; preview.isHidden = true; p.content.addArrangedSubview(preview)
        var photo: UIImage?
        var removePhoto = false
        if let path = original.photo { preview.image = UIImage(contentsOfFile: PersonalStore.shared.directory.appendingPathComponent(path).path); preview.isHidden = preview.image == nil }
        p.action("Убрать фото",icon: "photo.badge.minus") { [weak preview] in removePhoto = true; photo = nil; preview?.image = nil; preview?.isHidden = true }
        p.action("Добавить фото", icon: "photo") { [weak self, weak preview] in
            var config = PHPickerConfiguration(); config.filter = .images; config.selectionLimit = 1
            let picker = PHPickerViewController(configuration: config); picker.delegate = self
            self?.photoHandler = { image in removePhoto = false; photo = image; preview?.image = image; preview?.isHidden = false }; self?.present(picker, animated: true)
        }
        p.action("Сохранить место", icon: "mappin") { [weak self, weak p, weak text] in
            let store = PersonalStore.shared; var place = original; place.note = String((text?.text ?? "Моё место").prefix(2000))
            if removePhoto { place.photo = nil }
            do {
                if let photo { let renderer = UIGraphicsImageRenderer(size: CGSize(width: 1200, height: max(1,1200*photo.size.height/photo.size.width))); let resized = renderer.image { _ in photo.draw(in: CGRect(x: 0, y: 0, width: 1200, height: max(1,1200*photo.size.height/photo.size.width))) }; if let bytes = resized.jpegData(compressionQuality: 0.8) { try FileManager.default.createDirectory(at: store.directory, withIntermediateDirectories: true); place.photo = "place-\(place.id).jpg"; try bytes.write(to: store.directory.appendingPathComponent(place.photo!), options: .atomic) } }
                try store.update { if isNew { $0.places.append(place) } else if let index = $0.places.firstIndex(where: { $0.id == place.id }) { $0.places[index] = place } }; self?.refreshPlaces(); self?.closePages(); self?.placeDetails(place)
            } catch { self?.alert("Место", error.localizedDescription) }
        }
    }
    private func places() {
        let p = page("Мои места")
        if PersonalStore.shared.data.places.isEmpty { p.text("Удерживай точку на карте, чтобы оставить заметку или фото.") }
        for place in PersonalStore.shared.data.places {
            if let path = place.photo, let image = UIImage(contentsOfFile: PersonalStore.shared.directory.appendingPathComponent(path).path) { let v = UIImageView(image: image); v.contentMode = .scaleAspectFill; v.clipsToBounds = true; v.layer.cornerRadius = 20; v.heightAnchor.constraint(equalToConstant: 170).isActive = true; p.content.addArrangedSubview(v) }
            p.action(place.note.isEmpty ? "Моё место" : place.note, icon: "mappin") { [weak self] in self?.placeDetails(place) }
        }
    }
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true); guard let provider = results.first?.itemProvider, provider.canLoadObject(ofClass: UIImage.self) else { photoHandler = nil; return }
        provider.loadObject(ofClass: UIImage.self) { [weak self] object, _ in DispatchQueue.main.async { if let image = object as? UIImage { self?.photoHandler?(image) }; self?.photoHandler = nil } }
    }
    private func alert(_ title: String, _ message: String) { page(title).text(message) }
    func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer { let r = FogRenderer(overlay: overlay); r.dark = traitCollection.userInterfaceStyle == .dark; return r }
    func mapView(_ mapView: MKMapView, regionDidChangeAnimated animated: Bool) { mapView.accessibilityValue = String(format: "%.5f, %.5f", mapView.centerCoordinate.latitude, mapView.centerCoordinate.longitude) }
    func mapView(_ mapView: MKMapView, regionWillChangeAnimated animated: Bool) {
        for child in mapView.subviews {
            let gestures: [UIGestureRecognizer] = child.gestureRecognizers ?? []
            for gesture in gestures {
                if gesture.state == UIGestureRecognizer.State.began || gesture.state == UIGestureRecognizer.State.changed { following = false; return }
            }
        }
    }

}

private final class PlacePin: MKPointAnnotation {
    let place: Place
    init(_ place: Place) { self.place = place; super.init(); coordinate = CLLocationCoordinate2D(latitude: place.lat,longitude: place.lng); title = place.note.isEmpty ? "Моё место" : place.note }
}
