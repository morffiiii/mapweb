import UIKit

extension TravelMode {
    var color: UIColor { switch category { case .walk: return UIColor(red: 1, green: 0.36, blue: 0.12, alpha: 1); case .bike: return .systemCyan; case .car: return .systemPurple; default: return .systemPink } }
}

// App-owned page: no system action sheets or navigation bars.
class TerraPage: UIViewController {
    let content = UIStackView()
    let heading: String
    private var keyboardObserver: NSObjectProtocol?
    var dismissible = true
    init(_ title: String, dismissible: Bool = true) { heading = title; self.dismissible = dismissible; super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { fatalError() }
    override func viewDidLoad() {
        super.viewDidLoad()
        view.accessibilityViewIsModal = true
        view.backgroundColor = UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: 0.075, green: 0.08, blue: 0.1, alpha: 1) : UIColor(red: 0.97, green: 0.96, blue: 0.94, alpha: 1) }
        view.tintColor = TravelMode.walk.color
        let scroll = UIScrollView(); scroll.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(scroll)
        scroll.keyboardDismissMode = .interactive
        keyboardObserver = NotificationCenter.default.addObserver(forName: UIResponder.keyboardWillChangeFrameNotification,object: nil,queue: .main) { [weak self,weak scroll] notice in
            guard let self,let scroll,let frame = notice.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else { return }
            let overlap = self.view.bounds.intersection(self.view.convert(frame,from: nil))
            let inset = overlap.isNull ? 0 : max(0,overlap.height-self.view.safeAreaInsets.bottom)
            scroll.contentInset.bottom = inset; scroll.verticalScrollIndicatorInsets.bottom = inset
        }
        content.axis = .vertical; content.spacing = 18; content.translatesAutoresizingMaskIntoConstraints = false; scroll.addSubview(content)
        NSLayoutConstraint.activate([scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor), scroll.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor), scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor), scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor), content.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 20), content.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -30), content.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor, constant: 24), content.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor, constant: -24), content.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor, constant: -48)])
        let top = UIStackView(); top.alignment = .center
        let title = UILabel(); title.text = heading; title.font = .systemFont(ofSize: 32, weight: .bold); title.numberOfLines = 2; top.addArrangedSubview(title)
        let close = UIButton(type: .system); close.setImage(UIImage(systemName: "xmark"), for: .normal); close.accessibilityLabel = "Закрыть"; close.widthAnchor.constraint(equalToConstant: 48).isActive = true; close.heightAnchor.constraint(equalToConstant: 48).isActive = true; close.addAction(UIAction { [weak self] _ in self?.close() }, for: .touchUpInside); top.addArrangedSubview(close); content.addArrangedSubview(top)
        close.isHidden = !dismissible
    }
    deinit { if let keyboardObserver { NotificationCenter.default.removeObserver(keyboardObserver) } }
    func close() { willMove(toParent: nil); UIView.animate(withDuration: 0.2, animations: { self.view.alpha = 0 }) { _ in self.view.removeFromSuperview(); self.removeFromParent() } }
    @discardableResult func text(_ value: String, large: Bool = false) -> UILabel {
        loadViewIfNeeded(); let label = UILabel(); label.text = value; label.numberOfLines = 0; label.font = .systemFont(ofSize: large ? 29 : 15, weight: large ? .bold : .regular); label.textColor = large ? .label : .secondaryLabel; content.addArrangedSubview(label); return label
    }
    @discardableResult func action(_ title: String, detail: String = "", icon: String = "arrow.up.right", color: UIColor = TravelMode.walk.color, run: @escaping () -> Void) -> UIButton {
        loadViewIfNeeded(); let b = UIButton(type: .system); var c = UIButton.Configuration.filled(); c.title = title; c.subtitle = detail; c.image = UIImage(systemName: icon); c.imagePadding = 16; c.titleAlignment = .leading; c.baseBackgroundColor = .secondarySystemBackground; c.baseForegroundColor = color; c.cornerStyle = .large; c.contentInsets = NSDirectionalEdgeInsets(top: 20, leading: 18, bottom: 20, trailing: 18)
        c.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { incoming in var out = incoming; out.foregroundColor = .label; out.font = .systemFont(ofSize: 17, weight: .semibold); return out }
        c.subtitleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { incoming in var out = incoming; out.foregroundColor = .secondaryLabel; out.font = .systemFont(ofSize: 13); return out }
        b.configuration = c; b.contentHorizontalAlignment = .leading; b.accessibilityIdentifier = title; b.addAction(UIAction { _ in run() }, for: .touchUpInside); content.addArrangedSubview(b); return b
    }
}
