import UIKit
import CoreNFC

@objc public class AnalyticsManager: NSObject {
  // Forces compiling and linking against CoreNFC,
  // which only exists for iOS + Mac Catalyst targets
  private let readerSession: NFCReaderSessionProtocol? = nil

  public override init() {

  }
}
