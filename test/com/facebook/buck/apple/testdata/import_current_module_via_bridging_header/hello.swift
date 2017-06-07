import Foundation

@objc public class Hello : NSObject {
    public class func sayHello(name: String) {
        Greeter.sayHello(name)
    }
}
