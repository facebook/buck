import Foundation

@objc public class Greeter : NSObject {
    @objc public class func sayHello(name: String) {
        print("Hello " + name)
    }
}
