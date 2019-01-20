package jlib;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class JavaEnvTest {
  @Test
  public void testEnv() {
    assertEquals("a", System.getenv("VARA"));
    assertEquals("c", System.getenv("VARC"));
  }
}
