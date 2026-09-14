import UIKit
import PhotosUI
import AVKit
import UniformTypeIdentifiers

final class PlaceEditor: TerraPage, PHPickerViewControllerDelegate, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
    private var place: Place
    private let isNew: Bool
    private let saved: (Place) -> Void
    private let titleInput = TerraInput(), descriptionInput = UITextView(), gallery = UIStackView()
    private var media: [PlaceMedia]
    private var importing = 0
    private var saveButton: UIButton?
    init(_ place: Place,isNew: Bool,saved: @escaping (Place) -> Void) { self.place = place; self.isNew = isNew; self.saved = saved; media = place.attachments; super.init(isNew ? "Новое место" : "Редактировать место") }
    required init?(coder: NSCoder) { fatalError() }
    override func viewDidLoad() {
        super.viewDidLoad(); titleInput.placeholder = "Название места"; titleInput.text = place.title; titleInput.accessibilityIdentifier = "placeTitle"; content.addArrangedSubview(titleInput)
        descriptionInput.text = place.note; descriptionInput.font = .systemFont(ofSize: 17); descriptionInput.backgroundColor = .secondarySystemBackground; descriptionInput.layer.cornerRadius = 18; descriptionInput.textContainerInset = UIEdgeInsets(top: 18,left: 14,bottom: 18,right: 14); descriptionInput.heightAnchor.constraint(equalToConstant: 150).isActive = true; descriptionInput.accessibilityLabel = "Описание места"; text("Описание"); content.addArrangedSubview(descriptionInput)
        let tools = UIStackView(); tools.spacing = 12
        for (icon,label,run) in [("plus","Фото и видео из галереи",{ [weak self] in self?.pick() }),("camera","Сделать фото",{ [weak self] in self?.camera() })] {
            let b = UIButton(type: .system); var c = UIButton.Configuration.tinted(); c.image = UIImage(systemName: icon); c.cornerStyle = .large; c.contentInsets = NSDirectionalEdgeInsets(top: 16,leading: 20,bottom: 16,trailing: 20); b.configuration = c; b.accessibilityLabel = label; b.addAction(UIAction { _ in run() },for: .touchUpInside); tools.addArrangedSubview(b)
        }; tools.addArrangedSubview(UIView()); content.addArrangedSubview(tools)
        text("До 12 фото и видео. Видео — до 100 МБ.")
        gallery.axis = .vertical; gallery.spacing = 14; content.addArrangedSubview(gallery); render()
        saveButton = action("Сохранить место",icon: "bookmark.fill") { [weak self] in self?.save() }
    }
    private func render() {
        for v in gallery.arrangedSubviews { v.removeFromSuperview() }
        for item in media {
            let card = MediaCard(item,presenter: self); gallery.addArrangedSubview(card)
            let remove = UIButton(type: .system); var c = UIButton.Configuration.filled(); c.image = UIImage(systemName: "xmark"); c.baseBackgroundColor = .black.withAlphaComponent(0.7); c.baseForegroundColor = .white; c.cornerStyle = .capsule; remove.configuration = c; remove.accessibilityLabel = "Удалить вложение"; remove.translatesAutoresizingMaskIntoConstraints = false; card.addSubview(remove)
            NSLayoutConstraint.activate([remove.topAnchor.constraint(equalTo: card.topAnchor,constant: 8),remove.trailingAnchor.constraint(equalTo: card.trailingAnchor,constant: -8),remove.widthAnchor.constraint(equalToConstant: 44),remove.heightAnchor.constraint(equalToConstant: 44)])
            remove.addAction(UIAction { [weak self] _ in self?.media.removeAll { $0.id == item.id }; self?.render() },for: .touchUpInside)
        }
    }
    private func pick() {
        guard media.count < 12, importing == 0 else { return }; view.endEditing(true)
        var config = PHPickerConfiguration(); config.filter = .any(of: [.images,.videos]); config.selectionLimit = 12-media.count
        let picker = PHPickerViewController(configuration: config); picker.delegate = self; present(picker,animated: true)
    }
    private func camera() {
        guard media.count < 12, importing == 0 else { return }
        guard UIImagePickerController.isSourceTypeAvailable(.camera) else { text("Камера недоступна на этом устройстве."); return }
        let picker = UIImagePickerController(); picker.sourceType = .camera; picker.delegate = self; present(picker,animated: true)
    }
    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { picker.dismiss(animated: true) }
    func imagePickerController(_ picker: UIImagePickerController,didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
        picker.dismiss(animated: true); if let image = info[.originalImage] as? UIImage { do { media.append(try Self.saveImage(image)); render() } catch { text("Не удалось сохранить фото.") } }
    }
    private static func saveImage(_ image: UIImage) throws -> PlaceMedia {
        let ratio = min(1,1600/max(image.size.width,image.size.height)), size = CGSize(width: image.size.width*ratio,height: image.size.height*ratio)
        let resized = UIGraphicsImageRenderer(size: size).image { _ in image.draw(in: CGRect(origin: .zero,size: size)) }
        guard let bytes = resized.jpegData(compressionQuality: 0.85) else { throw TrackError.storage }
        return try saveBytes(bytes,fileExtension: "jpg",video: false)
    }
    private static func saveBytes(_ bytes: Data,fileExtension ext: String,video: Bool) throws -> PlaceMedia {
        let dir = PersonalStore.shared.directory; try FileManager.default.createDirectory(at: dir,withIntermediateDirectories: true)
        let name = "media-\(UUID().uuidString).\(ext)"; try bytes.write(to: dir.appendingPathComponent(name),options: [.atomic,.completeFileProtectionUntilFirstUserAuthentication]); return PlaceMedia(file: name,video: video)
    }
    func picker(_ picker: PHPickerViewController,didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true); importing = results.count; saveButton?.isEnabled = importing == 0
        for result in results {
            let provider = result.itemProvider
            if provider.hasItemConformingToTypeIdentifier(UTType.movie.identifier) {
                provider.loadFileRepresentation(forTypeIdentifier: UTType.movie.identifier) { [weak self] url,error in
                    var item: PlaceMedia?
                    if let url { do { let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0; guard size <= 100_000_000 else { throw TrackError.storage }; item = try Self.saveBytes(Data(contentsOf: url),fileExtension: url.pathExtension.isEmpty ? "mov" : url.pathExtension,video: true) } catch {} }
                    DispatchQueue.main.async { self?.received(item) }
                }
            } else { provider.loadObject(ofClass: UIImage.self) { [weak self] object,_ in DispatchQueue.main.async { self?.received((object as? UIImage).flatMap { try? Self.saveImage($0) }) } } }
        }
    }
    private func received(_ item: PlaceMedia?) { importing = max(0,importing-1); if let item { media.append(item) } else { text("Файл не добавлен: проверь формат и размер видео (до 100 МБ).") }; render(); saveButton?.isEnabled = importing == 0 }
    private func save() {
        guard importing == 0 else { return }; place.title = String((titleInput.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines).prefix(100)); place.note = String(descriptionInput.text.prefix(4000)); place.media = media; place.photo = nil
        do { try PersonalStore.shared.update { if isNew { $0.places.append(place) } else if let i = $0.places.firstIndex(where: { $0.id == place.id }) { $0.places[i] = place } }; saved(place) } catch { text("Не удалось сохранить место. Проверь свободное место на телефоне.") }
    }
}

final class MediaCard: UIView {
    init(_ item: PlaceMedia,presenter: UIViewController) {
        super.init(frame: .zero); backgroundColor = .secondarySystemBackground; layer.cornerRadius = 20; clipsToBounds = true; heightAnchor.constraint(equalToConstant: 240).isActive = true
        let image = UIImageView(); image.contentMode = .scaleAspectFit; image.translatesAutoresizingMaskIntoConstraints = false; addSubview(image)
        NSLayoutConstraint.activate([image.topAnchor.constraint(equalTo: topAnchor),image.bottomAnchor.constraint(equalTo: bottomAnchor),image.leadingAnchor.constraint(equalTo: leadingAnchor),image.trailingAnchor.constraint(equalTo: trailingAnchor)])
        let url = PersonalStore.shared.directory.appendingPathComponent(item.file)
        if item.video {
            let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url)); generator.appliesPreferredTrackTransform = true; generator.maximumSize = CGSize(width: 800,height: 800)
            DispatchQueue.global(qos: .userInitiated).async { let thumb = try? generator.copyCGImage(at: .zero,actualTime: nil); DispatchQueue.main.async { if let thumb { image.image = UIImage(cgImage: thumb) } } }
            let play = UIButton(type: .system); play.setImage(UIImage(systemName: "play.circle.fill",withConfiguration: UIImage.SymbolConfiguration(pointSize: 54)),for: .normal); play.tintColor = .white; play.backgroundColor = .black.withAlphaComponent(0.25); play.accessibilityLabel = "Смотреть видео"; play.translatesAutoresizingMaskIntoConstraints = false; addSubview(play)
            NSLayoutConstraint.activate([play.centerXAnchor.constraint(equalTo: centerXAnchor),play.centerYAnchor.constraint(equalTo: centerYAnchor),play.widthAnchor.constraint(equalToConstant: 72),play.heightAnchor.constraint(equalToConstant: 72)])
            play.addAction(UIAction { [weak presenter] _ in let vc = AVPlayerViewController(); vc.player = AVPlayer(url: url); presenter?.present(vc,animated: true) { vc.player?.play() } },for: .touchUpInside)
        } else { image.image = UIImage(contentsOfFile: url.path) }
    }
    required init?(coder: NSCoder) { fatalError() }
}
