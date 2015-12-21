package buck

import org.junit.runner.RunWith
import org.scalatest._

@RunWith(classOf[junit.JUnitRunner])
class TestFailure extends FlatSpec with Matchers {
  "Failure" should "not work" in {
    1 should be(0)
  }
}
