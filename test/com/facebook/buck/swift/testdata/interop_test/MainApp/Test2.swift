import UIKit

public class Test2 : NSObject {
    public class func func2(_ str: String) {
        let alert = UIAlertView()
        alert.title = "From Test2.swift"
        alert.message = str
        alert.addButton(withTitle: "Yes")
        alert.addButton(withTitle: "No")
        alert.show()
    }
}
