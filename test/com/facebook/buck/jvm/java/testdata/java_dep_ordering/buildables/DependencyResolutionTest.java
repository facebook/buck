package you.really.care.about.path.mismatch;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class DependencyResolutionTest {

  @Test
  public void testResolutionOrder() {
    StringBuilder resources = new StringBuilder();
    for (int i = 1; i < 6; i++ ) {
      for (int j = i + 1; j < 6; j++ ) {
        String resource = i + "-" + j + ".txt";
        resources.append(resource + ":" + readResolvedRes("/" + resource) + ",");
      }
    }
    resources.deleteCharAt(resources.length() - 1);
    Assert.fail("===+===" + resources.toString() + "===+===");
  }

  private int readResolvedRes(String res) {
    try (InputStream is = DependencyResolutionTest.class.getResourceAsStream(res)) {
      return Integer.parseInt("" + (char)(new InputStreamReader(is)).read());
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }
}
