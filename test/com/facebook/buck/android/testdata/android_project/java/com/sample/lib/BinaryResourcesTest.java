package com.facebook.sample;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
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

      String resourceApkPath = props.getProperty("android_resource_apk");
      assertTrue(resourceApkPath != null);
      File resourceApkFile = new File(resourceApkPath);
      assertTrue(resourceApkFile.exists());
      assertFalse(resourceApkFile.isAbsolute());

      String robolectricManifestPath = props.getProperty("android_merged_manifest");
      assertTrue(robolectricManifestPath != null);
      File robolectricManifestFile = new File(robolectricManifestPath);
      assertTrue(robolectricManifestFile.exists());
      assertFalse(robolectricManifestFile.isAbsolute());
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
