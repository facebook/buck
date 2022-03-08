import Foundation

public func doStuff() -> Double {
    return Double(Date().timeIntervalSince1970)
}


@objc public class BClass : NSObject {
    @objc public func doInstanceStuff() -> Int {
        return 42
    }
}
