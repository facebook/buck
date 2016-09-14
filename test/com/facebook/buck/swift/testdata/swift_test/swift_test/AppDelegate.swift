import UIKit

@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {

    var window: UIWindow?


    func application(application: UIApplication, didFinishLaunchingWithOptions launchOptions: [NSObject: AnyObject]?) -> Bool {
        let alert = UIAlertView()
        alert.title = "From iosdep1.swift"
        alert.message = "Message from a dependency: "
        alert.show()
        return true
    }
}
