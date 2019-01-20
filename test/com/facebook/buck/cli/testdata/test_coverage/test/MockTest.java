import static org.junit.Assert.assertEquals;

import buck.events.Foo;
import org.junit.Test;

public class MockTest {

  @Test
  public void passingTest() {
    System.out.println("passed!");
  }

  @Test
  public void testAdd() {
    assertEquals(3, Foo.add(1, 2));
  }
}
