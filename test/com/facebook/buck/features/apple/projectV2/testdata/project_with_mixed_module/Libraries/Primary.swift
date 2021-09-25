import DepA
import ExternalHeaders

class Primary {
  func foo() {
    let a = DepA()
    a.hello()
  }
}
