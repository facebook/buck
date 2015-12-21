package buck

import org.junit.runner.RunWith
import org.scalatest._

@RunWith(classOf[junit.JUnitRunner])
class TestSpinning extends FlatSpec with Matchers {
  "Spinning" should "spin" in {
    while (true) { }
  }
}
