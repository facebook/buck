import UIKit

public class Test0 : NSObject {
    public class func func0(_ str: String) {
        let alert = UIAlertView()
        alert.title = "From Test0.swift"
        alert.message = str
        alert.addButton(withTitle: "Yes")
        alert.addButton(withTitle: "No")
        alert.show()
    }
}
