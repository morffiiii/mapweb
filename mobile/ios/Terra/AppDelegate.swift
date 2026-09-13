import UIKit

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    func application(_ application: UIApplication, didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        let ready = UserDefaults.standard.bool(forKey: "onboardingComplete") && (LocalAccount.record() == nil || UserDefaults.standard.bool(forKey: "localSession"))
        window.rootViewController = ready ? MapController() : WelcomeController()
        window.makeKeyAndVisible(); self.window = window
        return true
    }
    func applicationDidEnterBackground(_ application: UIApplication) { TrackingEngine.shared.foreground(false) }
    func applicationWillEnterForeground(_ application: UIApplication) { TrackingEngine.shared.foreground(true) }
}
