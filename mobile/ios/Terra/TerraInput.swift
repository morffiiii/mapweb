import UIKit
final class TerraInput: UITextField {
    override init(frame: CGRect) {
        super.init(frame: frame); borderStyle = .none; backgroundColor = .secondarySystemBackground; textColor = .label
        font = .systemFont(ofSize: 17); layer.cornerRadius = 18; tintColor = TravelMode.walk.color; clearButtonMode = .whileEditing
    }
    required init?(coder: NSCoder) { fatalError() }
    override var intrinsicContentSize: CGSize { CGSize(width: UIView.noIntrinsicMetric,height: 56) }
    override func textRect(forBounds bounds: CGRect) -> CGRect { bounds.insetBy(dx: 18,dy: 14) }
    override func editingRect(forBounds bounds: CGRect) -> CGRect { textRect(forBounds: bounds) }
    override func placeholderRect(forBounds bounds: CGRect) -> CGRect { textRect(forBounds: bounds) }
}
