package net.starlark.java.eval;

import static org.junit.Assert.*;

import org.junit.Test;

public class BcStrFormatTest {
  @Test
  public void indexOfSinglePercentS() {
    assertEquals(0, BcStrFormat.indexOfSinglePercentS("%s"));
    assertEquals(1, BcStrFormat.indexOfSinglePercentS("a%sb"));
    assertEquals(1, BcStrFormat.indexOfSinglePercentS("a%s"));
    assertEquals(-1, BcStrFormat.indexOfSinglePercentS("a%"));
    assertEquals(-1, BcStrFormat.indexOfSinglePercentS("a%%"));
    assertEquals(-1, BcStrFormat.indexOfSinglePercentS("a%s %"));
    assertEquals(-1, BcStrFormat.indexOfSinglePercentS("a%d"));
  }
}
