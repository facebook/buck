package jlib;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

public class JTest {
  @Test
  public void testGetValue() {
    assertThat(JLib.getValue(), equalTo(3));
  }
}

