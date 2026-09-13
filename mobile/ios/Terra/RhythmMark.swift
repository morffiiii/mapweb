import UIKit
final class RhythmMark: UIView {
    override init(frame: CGRect) { super.init(frame: frame); isOpaque = false; accessibilityLabel = "Ритм прогулок" }
    required init?(coder: NSCoder) { fatalError() }
    override func draw(_ rect: CGRect) {
        let path = UIBezierPath(); path.move(to: CGPoint(x: 10,y: 62)); path.addLine(to: CGPoint(x: 27,y: 41)); path.addLine(to: CGPoint(x: 17,y: 31)); path.addLine(to: CGPoint(x: 51,y: 10)); path.lineWidth = 7; path.lineCapStyle = .round; path.lineJoinStyle = .round; TravelMode.walk.color.setStroke(); path.stroke()
        let arrow = UIBezierPath(); arrow.move(to: CGPoint(x: 34,y: 9)); arrow.addLine(to: CGPoint(x: 52,y: 9)); arrow.addLine(to: CGPoint(x: 53,y: 27)); arrow.lineWidth = 7; arrow.lineCapStyle = .round; arrow.lineJoinStyle = .round; arrow.stroke()
        UIColor.systemPurple.setFill(); UIBezierPath(ovalIn: CGRect(x: 4,y: 56,width: 12,height: 12)).fill()
    }
}
