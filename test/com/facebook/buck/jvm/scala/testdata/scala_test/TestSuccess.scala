package buck

import org.junit.runner.RunWith
import org.scalatest._

@RunWith(classOf[junit.JUnitRunner])
class TestSuccess extends FlatSpec with Matchers {
  "Success" should "succeed" in {
    1 should be(1)
  }
}
