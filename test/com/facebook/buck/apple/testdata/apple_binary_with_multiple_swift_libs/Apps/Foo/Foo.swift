import Foundation
import Bar

@objc public class Foo: NSObject {
  @objc public class func printSwiftObject() {
    Swift.print(BarVal(i: 15, s: "FooBar"))
  }
}