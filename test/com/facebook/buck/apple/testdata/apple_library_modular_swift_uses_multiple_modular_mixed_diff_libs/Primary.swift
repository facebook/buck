import DepA
import DepB
import ExternalHeaders

class Primary {
  func foo() {
    let a = DepA()
    a.hello()

    let b = DepB()
    b.hello()
  }
}
