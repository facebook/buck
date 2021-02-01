//
//  AppDelegate.swift
//  Clip
//
//  Created by Lucas Marcal on 07/10/20.
//

import UIKit

class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        self.window = UIWindow(frame: UIScreen.main.bounds)
        self.window?.rootViewController = UIViewController()
        self.window?.rootViewController?.view.backgroundColor = .red
        self.window?.makeKeyAndVisible()
        return true
    }
}

