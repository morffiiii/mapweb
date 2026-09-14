import UIKit
import MapKit
import YandexMapsMobile

/// Native map with a noninteractive exploration layer; GPS remains owned by TrackingEngine.
final class YandexSurface: UIView, YMKMapInputListener, YMKMapCameraListener {
    static var selected: Bool { UserDefaults.standard.string(forKey: "mapProvider") != "apple" }
    let native = YMKMapView(frame: .zero)!
    private let ink = YandexInk()
    var onLongPress: ((CLLocationCoordinate2D) -> Void)?
    var onPlace: ((Place) -> Void)?
    var onGesture: (() -> Void)?
    var sessions: [TrackSession] = [] { didSet { ink.setNeedsDisplay() } }
    var places: [Place] = [] { didSet { ink.setNeedsDisplay() } }
    var nearby: [CLLocationCoordinate2D] = [] { didSet { ink.setNeedsDisplay() } }
    var position: CLLocationCoordinate2D? { didSet { ink.setNeedsDisplay() } }
    var fogVisible = true { didSet { ink.setNeedsDisplay() } }
    var route: TrackSession? { didSet { ink.setNeedsDisplay() } }
    var routeCount = Int.max { didSet { ink.setNeedsDisplay() } }
    var dark = false { didSet { native.mapWindow.map.isNightModeEnabled = dark; ink.setNeedsDisplay() } }
    override init(frame: CGRect) {
        super.init(frame: frame)
        addSubview(native); addSubview(ink); ink.owner = self
        native.mapWindow.map.addInputListener(with: self)
        native.mapWindow.map.addCameraListener(with: self)
        native.mapWindow.map.isTiltGesturesEnabled = false
        accessibilityIdentifier = "nativeMap"; isAccessibilityElement = true
        accessibilityLabel = "Карта открытий"; accessibilityTraits = .allowsDirectInteraction
    }
    required init?(coder: NSCoder) { fatalError() }
    override func layoutSubviews() { super.layoutSubviews(); native.frame = bounds; ink.frame = bounds; ink.setNeedsDisplay() }
    func move(_ coordinate: CLLocationCoordinate2D, meters: Double = 1000, animated: Bool = true) {
        let zoom = Float(max(2,min(19,log2(156543.03392 * cos(coordinate.latitude * .pi/180) * max(200,Double(bounds.width)) / meters))))
        native.mapWindow.map.move(with: YMKCameraPosition(target: YMKPoint(latitude: coordinate.latitude,longitude: coordinate.longitude),zoom: zoom,azimuth: 0,tilt: 0),animation: YMKAnimation(type: .smooth,duration: animated ? 0.35 : 0),cameraCallback: nil)
    }
    func screen(_ coordinate: CLLocationCoordinate2D) -> CGPoint? {
        guard let point = native.mapWindow.worldToScreen(withWorldPoint: YMKPoint(latitude: coordinate.latitude,longitude: coordinate.longitude)) else { return nil }
        let scale = window?.screen.scale ?? UIScreen.main.scale
        return CGPoint(x: CGFloat(point.x)/scale,y: CGFloat(point.y)/scale)
    }
    func radius(_ point: TrackPoint) -> CGFloat {
        guard let a = screen(point.coordinate), let b = screen(CLLocationCoordinate2D(latitude: point.lat+0.0001,longitude: point.lng)) else { return 0 }
        return hypot(a.x-b.x,a.y-b.y) * CGFloat(Discovery.radius/11.1195)
    }
    func onMapTap(with map: YMKMap, point: YMKPoint) {
        guard let tap = screen(CLLocationCoordinate2D(latitude: point.latitude,longitude: point.longitude)) else { return }
        let candidates = places.compactMap { place -> (Place,CGFloat)? in
            guard let p = screen(CLLocationCoordinate2D(latitude: place.lat,longitude: place.lng)) else { return nil }
            return (place,hypot(p.x-tap.x,p.y-14-tap.y))
        }
        if let found = candidates.min(by: { $0.1 < $1.1 }), found.1 < 32 { onPlace?(found.0) }
    }
    func onMapLongTap(with map: YMKMap, point: YMKPoint) { onLongPress?(CLLocationCoordinate2D(latitude: point.latitude,longitude: point.longitude)) }
    func onCameraPositionChanged(with map: YMKMap?, cameraPosition: YMKCameraPosition, cameraUpdateReason: YMKCameraUpdateReason, finished: Bool) {
        ink.setNeedsDisplay()
        accessibilityValue = String(format: "%.5f, %.5f",cameraPosition.target.latitude,cameraPosition.target.longitude)
        if cameraUpdateReason == .gestures { onGesture?() }
    }
}

private final class YandexInk: UIView {
    weak var owner: YandexSurface?
    override init(frame: CGRect) { super.init(frame: frame); isOpaque = false; isUserInteractionEnabled = false; backgroundColor = .clear }
    required init?(coder: NSCoder) { fatalError() }
    override func draw(_ rect: CGRect) {
        guard let owner, let c = UIGraphicsGetCurrentContext() else { return }
        c.setLineCap(.round); c.setLineJoin(.round)
        if owner.fogVisible {
            c.saveGState(); c.beginTransparencyLayer(auxiliaryInfo: nil)
            c.setFillColor((owner.dark ? UIColor(red: 0.09,green: 0.10,blue: 0.13,alpha: 0.89) : UIColor(white: 0.72,alpha: 0.88)).cgColor); c.fill(bounds)
            c.setBlendMode(.clear)
            // Keep the SDK's own attribution/logo unobscured.
            c.fill(CGRect(x: 0,y: bounds.height-50,width: 150,height: 50))
            for session in owner.sessions {
                for (i,p) in session.points.enumerated() where p.excludeDiscovery != true {
                    guard let xy = owner.screen(p.coordinate) else { continue }
                    let r = owner.radius(p)
                    c.fillEllipse(in: CGRect(x: xy.x-r,y: xy.y-r,width: 2*r,height: 2*r))
                    if i > 0, session.points[i-1].excludeDiscovery != true, session.points[i-1].connects(to: p), abs(session.points[i-1].lng-p.lng)<180, let a = owner.screen(session.points[i-1].coordinate) {
                        c.setLineWidth(2*r); c.beginPath(); c.move(to: a); c.addLine(to: xy); c.strokePath()
                    }
                }
            }
            c.endTransparencyLayer(); c.restoreGState()
        }
        if let route = owner.route {
            c.setStrokeColor(route.mode.color.cgColor); c.setLineWidth(8)
            for (i,p) in route.points.prefix(owner.routeCount).enumerated() where i > 0 {
                let previous = route.points[i-1]
                if previous.connects(to: p), abs(previous.lng-p.lng)<180, let a = owner.screen(previous.coordinate), let b = owner.screen(p.coordinate) { c.beginPath(); c.move(to: a); c.addLine(to: b); c.strokePath() }
            }
            for p in [route.points.first,route.points.prefix(owner.routeCount).last].compactMap({ $0 }) { dot(p.coordinate,color: route.mode.color,owner: owner,c: c) }
        }
        for place in owner.places { symbol("bookmark.fill",at: CLLocationCoordinate2D(latitude: place.lat,longitude: place.lng),color: .systemOrange,owner: owner) }
        for point in owner.nearby { symbol("sparkles",at: point,color: .systemPurple,owner: owner) }
        if let position = owner.position { dot(position,color: .systemOrange,owner: owner,c: c) }
    }
    private func symbol(_ name: String, at point: CLLocationCoordinate2D, color: UIColor, owner: YandexSurface) {
        guard let xy = owner.screen(point) else { return }
        UIImage(systemName: name)?.withTintColor(color,renderingMode: .alwaysOriginal).draw(in: CGRect(x: xy.x-13,y: xy.y-28,width: 26,height: 28))
    }
    private func dot(_ point: CLLocationCoordinate2D,color: UIColor,owner: YandexSurface,c: CGContext) {
        guard let xy = owner.screen(point) else { return }
        c.setFillColor(UIColor.white.cgColor); c.fillEllipse(in: CGRect(x: xy.x-9,y: xy.y-9,width: 18,height: 18))
        c.setFillColor(color.cgColor); c.fillEllipse(in: CGRect(x: xy.x-6,y: xy.y-6,width: 12,height: 12))
    }
}
