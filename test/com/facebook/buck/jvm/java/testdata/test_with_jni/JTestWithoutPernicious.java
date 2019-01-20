package jlib;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

import junit.framework.Assert;
import org.junit.Test;

public class JTestWithoutPernicious {
  @Test // getValue
  public void testGetValue() {
    assertThat(JLib.getValue(), equalTo(3));
  }

  @Test
  public void testNoPernicious() {
    try {
      System.loadLibrary("pernicious");
      Assert.fail("libpernicious should not have been available.");
    } catch (UnsatisfiedLinkError e) {
      // Expected.
    }
  }

  // @Test//noTestLib
  public void testNoTestLib() {
    try {
      JLib.getValue();
      Assert.fail("libjtestlib should not have been available.");
    } catch (UnsatisfiedLinkError e) {
      // Expected.
    }
  }
}
