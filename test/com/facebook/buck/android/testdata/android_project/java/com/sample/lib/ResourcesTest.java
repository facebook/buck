package com.facebook.sample;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class ResourcesTest {
  @Test
  public void testResources() {
    assertTrue(com.sample2.R.string.title > 0);
    assertTrue(com.sample.R.layout.top_layout > 0);
  }
}
