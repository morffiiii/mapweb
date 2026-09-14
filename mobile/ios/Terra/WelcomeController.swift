import UIKit

final class WelcomeController: UIViewController {
    override func viewDidLoad() { super.viewDidLoad(); view.backgroundColor = .systemBackground; welcome() }
    private func page(_ title: String) -> TerraPage {
        for c in children { c.willMove(toParent: nil); c.view.removeFromSuperview(); c.removeFromParent() }
        let p = TerraPage(title,dismissible: false); addChild(p); p.view.frame = view.bounds; p.view.autoresizingMask = [.flexibleWidth,.flexibleHeight]; view.addSubview(p.view); p.didMove(toParent: self); return p
    }
    private func input(_ p: TerraPage,_ hint: String,keyboard: UIKeyboardType = .default,secure: Bool = false) -> UITextField {
        let f = TerraInput(); f.placeholder = hint; f.accessibilityIdentifier = hint; f.keyboardType = keyboard; f.isSecureTextEntry = secure; f.autocapitalizationType = .none; f.autocorrectionType = .no; f.borderStyle = .none; f.heightAnchor.constraint(equalToConstant: 56).isActive = true; p.content.addArrangedSubview(f); return f
    }
    private func welcome() {
        let p = page("TERRA ↗"); p.text("Мир становится твоим шаг за шагом.",large: true); p.text("Открывай карту прогулками, сохраняй места и находи свой ритм.")
        if LocalAccount.record() == nil {
            p.action("Создать профиль",icon: "person.crop.circle.badge.plus") { [weak self] in self?.credentials(register: true) }

        }
        p.action("Войти",icon: "person.crop.circle") { [weak self] in self?.credentials(register: false) }
        p.text("Пока профиль работает только на этом телефоне. Облачного переноса и восстановления по почте ещё нет.")
    }
    private func credentials(register: Bool) {
        let p = page(register ? "Твой профиль" : "С возвращением"); p.text("Локальный вход на этом устройстве. Почта служит логином и пока не подтверждается через сервер.")
        let email = input(p,"Почта",keyboard: .emailAddress), password = input(p,"Пароль · от 8 символов",secure: true), feedback = p.text("")
        p.action(register ? "Зарегистрироваться" : "Войти",icon: "arrow.right") { [weak self,weak p,weak email,weak password,weak feedback] in
            guard let p else { return }
            let mail = (email?.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines), secret = password?.text ?? ""
            guard mail.contains("@"),mail.contains("."),mail.count <= 254,secret.count >= 8,secret.count <= 256 else { feedback?.text = "Проверь почту и пароль: от 8 до 256 символов."; return }
            p.view.endEditing(true); p.view.isUserInteractionEnabled = false
            DispatchQueue.global(qos: .userInitiated).async {
                var message: String?
                if register { do { try LocalAccount.register(email: mail,password: secret) } catch { message = error.localizedDescription } }
                else if !LocalAccount.login(email: mail,password: secret) { message = "Почта или пароль не совпадают с профилем на этом телефоне." }
                DispatchQueue.main.async { [weak self,weak feedback] in
                    p.view.isUserInteractionEnabled = true
                    if let message { feedback?.text = message; return }; UserDefaults.standard.set(true,forKey: "localSession")
                    if UserDefaults.standard.bool(forKey: "onboardingComplete") { self?.greeting() } else { self?.details() }
                }
            }
        }
        p.action("Назад",icon: "arrow.left") { [weak self] in self?.welcome() }
    }
    private func details() {
        let p = page("Познакомимся?"); p.text("01 / 03 · Всё по желанию. Эти данные можно изменить в профиле.")
        let name = input(p,"Как тебя зовут?"), age = input(p,"Возраст",keyboard: .numberPad), height = input(p,"Рост, см",keyboard: .decimalPad), weight = input(p,"Вес, кг",keyboard: .decimalPad), feedback = p.text("")
        p.action("Продолжить",icon: "arrow.right") { [weak self,weak name,weak age,weak height,weak weight,weak feedback] in
            let a = age?.text ?? "", h = (height?.text ?? "").replacingOccurrences(of: ",",with: "."), w = (weight?.text ?? "").replacingOccurrences(of: ",",with: ".")
            guard (a.isEmpty || (Int(a).map { (1...120).contains($0) } ?? false)),(h.isEmpty || (Double(h).map { (50...250).contains($0) } ?? false)),(w.isEmpty || (Double(w).map { (10...400).contains($0) } ?? false)) else { feedback?.text = "Проверь значения или пропусти этот шаг."; return }
            do { try PersonalStore.shared.update { $0.name = name?.text?.isEmpty == false ? String(name!.text!.prefix(60)) : "Исследователь"; $0.age = Int(a); $0.height = Double(h); $0.weight = Double(w) }; self?.intentions() } catch { feedback?.text = error.localizedDescription }
        }
        p.action("Пропустить",icon: "arrow.right") { [weak self] in self?.intentions() }
    }
    private func choices(_ title: String,subtitle: String,options: [String],multiple: Bool,done: @escaping ([String]) -> Void) {
        let p = page(title); p.text(subtitle); var selected = Set<String>()
        for option in options {
            let b = UIButton(type: .system); var c = UIButton.Configuration.filled(); c.title = option; c.baseBackgroundColor = .secondarySystemBackground; c.baseForegroundColor = .label; c.cornerStyle = .large; c.contentInsets = NSDirectionalEdgeInsets(top: 22,leading: 18,bottom: 22,trailing: 18); b.configuration = c; b.tag = 42
            b.addAction(UIAction { [weak p] _ in
                if selected.contains(option) { selected.remove(option) } else { if !multiple { selected.removeAll() }; selected.insert(option) }
                for case let button as UIButton in p?.content.arrangedSubviews ?? [] where button.tag == 42 { let active = selected.contains(button.configuration?.title ?? ""); button.configuration?.baseBackgroundColor = active ? TravelMode.walk.color : .secondarySystemBackground; button.configuration?.baseForegroundColor = active ? .white : .label }
            },for: .touchUpInside); p.content.addArrangedSubview(b)
        }
        p.action("Продолжить",icon: "arrow.right") { done(Array(selected)) }; p.action("Пропустить",icon: "arrow.right") { done([]) }
    }
    private func intentions() {
        choices("Что тебя зовёт?",subtitle: "02 / 03 · Можно выбрать несколько целей",options: ["Больше гулять","Открывать новые места","Кататься на велосипеде","Следить за активностью"],multiple: true) { [weak self] values in
            do { try PersonalStore.shared.update { $0.intentions = values }; self?.source() } catch { self?.page("Сохранение").text(error.localizedDescription) }
        }
    }
    private func source() {
        choices("Как ты нас нашёл?",subtitle: "03 / 03 · Ответ необязателен",options: ["Друзья","Социальные сети","Поиск","Другое"],multiple: false) { [weak self] values in
            do { try PersonalStore.shared.update { $0.source = values.first }; self?.greeting() } catch { self?.page("Сохранение").text(error.localizedDescription) }
        }
    }
    private func greeting() {
        let p = page("Привет,\n\(PersonalStore.shared.data.name)!"); p.text("Первое открытие — выйти за дверь.",large: true); p.text("Начни свой маршрут. Карта запомнит путь, а «Ритм» поможет гулять регулярно.")
        p.action("Открыть мой мир",icon: "arrow.up.right") { [weak self] in
            UserDefaults.standard.set(true,forKey: "onboardingComplete"); guard let window = self?.view.window else { return }
            UIView.transition(with: window,duration: 0.3,options: .transitionCrossDissolve) { window.rootViewController = MapController() }
        }
    }
}
