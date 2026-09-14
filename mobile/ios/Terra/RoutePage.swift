import UIKit
import MapKit

/// A separate map; changing its camera or replay never changes the exploration map.
final class RoutePage: TerraPage, MKMapViewDelegate {
    private let session: TrackSession
    private let routeMap = MKMapView()
    private var yandex: YandexSurface?
    private var timer: Timer?
    init(_ session: TrackSession) { self.session = session; super.init("Твой маршрут") }
    required init?(coder: NSCoder) { fatalError() }
    override func viewDidLoad() {
        super.viewDidLoad()
        routeMap.mapType = [.standard,.satellite,.hybrid][min(2,UserDefaults.standard.integer(forKey: "mapStyle"))]
        text(session.mode.title + " · " + Date(timeIntervalSince1970: session.startedAt/1000).formatted(date: .abbreviated, time: .shortened))
        text(String(format: "%.2f км · %d мин", session.distance/1000, Int(session.duration()/60)), large: true)
        text(String(format: "≈ %.0f активных ккал", ActivityMetrics.calories(session, weight: PersonalStore.shared.data.weight)))
        text("Шаги: \(session.steps.map(String.init) ?? "—")")
        routeMap.delegate = self; routeMap.layer.cornerRadius = 24; routeMap.clipsToBounds = true
        routeMap.accessibilityIdentifier = "historyRouteMap"; routeMap.heightAnchor.constraint(equalToConstant: 420).isActive = true; content.addArrangedSubview(routeMap)
        if YandexSurface.selected {
            routeMap.isHidden = true
            let surface = YandexSurface(frame: .zero); surface.fogVisible = false; surface.route = session; surface.dark = traitCollection.userInterfaceStyle == .dark
            surface.accessibilityIdentifier = "historyRouteMap"; surface.heightAnchor.constraint(equalToConstant: 420).isActive = true
            surface.clipsToBounds = true; surface.layer.cornerRadius = 24; content.addArrangedSubview(surface); yandex = surface
        }
        if session.points.isEmpty { text("В этой записи нет точных координат GPS."); return }
        draw(session.points.count)
        action("Воспроизвести", icon: "play.fill") { [weak self] in
            guard let self else { return }; self.timer?.invalidate(); var frame = 0
            self.timer = Timer.scheduledTimer(withTimeInterval: 0.08, repeats: true) { [weak self] timer in
                guard let self else { timer.invalidate(); return }; frame += 1; self.draw(max(1,self.session.points.count*frame/75)); if frame >= 75 { timer.invalidate() }
            }
        }
    }
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        if let yandex, yandex.tag == 0, yandex.bounds.width > 0, let first = session.points.first {
            yandex.tag = 1
            let latitudes = session.points.map(\.lat), longitudes = session.points.map(\.lng)
            let center = CLLocationCoordinate2D(latitude: ((latitudes.min() ?? first.lat)+(latitudes.max() ?? first.lat))/2,longitude: ((longitudes.min() ?? first.lng)+(longitudes.max() ?? first.lng))/2)
            let width = ((longitudes.max() ?? first.lng)-(longitudes.min() ?? first.lng))*111195*cos(center.latitude * .pi/180)
            let height = ((latitudes.max() ?? first.lat)-(latitudes.min() ?? first.lat))*111195
            yandex.move(center,meters: max(500,max(width,height)*1.4),animated: false)
        }
        if routeMap.tag == 0, routeMap.bounds.width > 0, let first = session.points.first {
            routeMap.tag = 1
            var bounds = MKMapRect.null
            for p in session.points { bounds = bounds.union(MKMapRect(origin: MKMapPoint(p.coordinate),size: MKMapSize(width: 1,height: 1))) }
            if bounds.width < 10 && bounds.height < 10 { routeMap.setRegion(MKCoordinateRegion(center: first.coordinate,latitudinalMeters: 500,longitudinalMeters: 500),animated: false) }
            else { routeMap.setVisibleMapRect(bounds,edgePadding: UIEdgeInsets(top: 40,left: 35,bottom: 40,right: 35),animated: false) }
        }
    }
    private func draw(_ count: Int) {
        if let yandex { yandex.routeCount = count; return }
        routeMap.removeOverlays(routeMap.overlays); routeMap.removeAnnotations(routeMap.annotations)
        var segment: [CLLocationCoordinate2D] = []
        func flush() { if segment.count > 1 { routeMap.addOverlay(MKPolyline(coordinates: segment,count: segment.count)) }; segment.removeAll() }
        for (i,p) in session.points.prefix(count).enumerated() {
            if i > 0 && (!session.points[i-1].connects(to: p) || abs(session.points[i-1].lng-p.lng)>180) { flush() }
            segment.append(p.coordinate)
        }; flush()
        for (title,p) in [("Старт",session.points.first),("Финиш",session.points.prefix(count).last)] { if let p { let pin = MKPointAnnotation(); pin.title = title; pin.coordinate = p.coordinate; routeMap.addAnnotation(pin) } }
    }
    func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
        guard let line = overlay as? MKPolyline else { return MKOverlayRenderer(overlay: overlay) }
        let r = MKPolylineRenderer(polyline: line); r.strokeColor = session.mode.color; r.lineWidth = 8; r.lineCap = .round; r.lineJoin = .round; return r
    }
    override func close() { timer?.invalidate(); super.close() }
    deinit { timer?.invalidate() }
}
