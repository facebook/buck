package com.facebook.buck.demo;

import android.content.Context;
import android.test.InstrumentationTestCase;
import org.junit.Assert;
import org.junit.Test;

// We specifically use this outdated case to avoid a dep on the support lib
public class AppTest extends InstrumentationTestCase {
  @Test
  public void testLoading() throws Exception {
    Context context = getInstrumentation().getContext();
    // Make sure we can load java and jni-native code
    Assert.assertEquals("e2E Test", new Hello().getHelloString());
  }
}
