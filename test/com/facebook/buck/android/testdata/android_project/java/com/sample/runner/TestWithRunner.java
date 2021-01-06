package com.facebook.sample;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class TestWithRunner {
  @Test
  public void testAlwaysTrue() {
    assertTrue(com.sample.R.layout.top_layout > 0);
  }
}
