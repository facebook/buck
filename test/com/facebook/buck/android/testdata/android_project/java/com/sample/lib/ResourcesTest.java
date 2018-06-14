package com.facebook.sample;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class ResourcesTest {
  @Test
  public void testResources() {
    assertTrue(com.sample2.R.string.title > 0);
    assertTrue(com.sample.R.layout.top_layout > 0);
  }
}
