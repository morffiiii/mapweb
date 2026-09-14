import UIKit
final class MetricPanel: UIStackView {
    private var values: [String: UILabel] = [:]
    override init(frame: CGRect) {
        super.init(frame: frame); axis = .vertical; spacing = 16
        for group in [[("km","КМ","point.topleft.down.curvedto.point.bottomright.up"),("time","ВРЕМЯ","clock"),("speed","КМ/Ч","speedometer")],[("steps","ШАГИ","shoeprints.fill"),("energy","ККАЛ ≈","flame.fill")]] {
            let row = UIStackView(); row.distribution = .fillEqually; row.spacing = 10
            for (key,unit,icon) in group {
                let cell = UIStackView(); cell.axis = .vertical; cell.spacing = 6
                let caption = UIStackView(); caption.spacing = 5; caption.alignment = .center
                let image = UIImageView(image: UIImage(systemName: icon)); image.tintColor = TravelMode.walk.color; image.contentMode = .scaleAspectFit; image.widthAnchor.constraint(equalToConstant: 15).isActive = true; image.heightAnchor.constraint(equalToConstant: 15).isActive = true
                let label = UILabel(); label.text = unit; label.textColor = .secondaryLabel; label.font = .systemFont(ofSize: 10,weight: .semibold); caption.addArrangedSubview(image); caption.addArrangedSubview(label)
                let value = UILabel(); value.text = "—"; value.font = .monospacedDigitSystemFont(ofSize: 24,weight: .semibold); value.adjustsFontSizeToFitWidth = true; value.minimumScaleFactor = 0.65; value.accessibilityIdentifier = "metric-"+key; values[key] = value
                cell.addArrangedSubview(value); cell.addArrangedSubview(caption); row.addArrangedSubview(cell)
            }; addArrangedSubview(row)
        }
    }
    required init(coder: NSCoder) { fatalError() }
    func update(_ session: TrackSession,speed: Double?,stepsAvailable: Bool) {
        let seconds = Int(session.duration())
        values["km"]?.text = String(format: "%.2f",session.distance/1000)
        values["time"]?.text = seconds >= 3600 ? String(format: "%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60) : String(format: "%02d:%02d",seconds/60,seconds%60)
        values["speed"]?.text = speed.map { String(format: "%.1f",$0) } ?? "—"
        values["steps"]?.text = session.mode == .walk && stepsAvailable ? String(session.steps ?? 0) : "—"
        values["energy"]?.text = String(format: "%.0f",ActivityMetrics.calories(session,weight: PersonalStore.shared.data.weight))
    }
}
