import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Collections;

import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.Handler;

public class LoggingTest {

  private static final Logger LOG = Logger.getLogger("LoggingTest");

  @Test
  public void passingTestWithLogMessages() {
    LOG.severe("This is an error in a passing test");
    LOG.warning("This is a warning in a passing test");
    LOG.info("This is an info message in a passing test");
    LOG.fine("This is a debug message in a passing test");
    LOG.finer("This is a verbose message in a passing test");
    LOG.finest("This is a super verbose message in a passing test");
  }

  @Test
  public void failingTestWithLogMessages() {
    LOG.severe("This is an error in a failing test");
    LOG.warning("This is a warning in a failing test");
    LOG.info("This is an info message in a failing test");
    LOG.fine("This is a debug message in a failing test");
    LOG.finer("This is a verbose message in a failing test");
    LOG.finest("This is a super verbose message in a failing test");

    fail("Intentionally failing test to get log output in a failing test");
  }
}
