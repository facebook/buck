package com.facebook.sample;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.Test;

public class BinaryResourcesTest {
  @Test
  public void testConfigExists() {
    InputStream resourceAsStream =
        getClass().getResourceAsStream("/com/android/tools/test_config.properties");
    assertTrue(resourceAsStream != null);
    Properties props = new Properties();
    try {
      props.load(resourceAsStream);
      assertTrue(props.getProperty("android_resource_apk") != null);
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      try {
        resourceAsStream.close();
      } catch (IOException ignored) {
      }
    }
  }
}
