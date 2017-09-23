import Foundation

@objc public class Bar: NSObject {
  @objc public class func printSwiftStruct() {
    Swift.print(TestLib(version: 0, name: "TestLib"))
  }
}