package com.facebook.sample;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
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

  @Test
  public void testAssetExists() throws IOException {
    InputStream resourceAsStream =
        getClass().getResourceAsStream("/com/android/tools/test_config.properties");
    assertTrue(resourceAsStream != null);
    Properties props = new Properties();
    try {
      props.load(resourceAsStream);

      String resourceApkPath = props.getProperty("android_resource_apk");
      assertTrue(resourceApkPath != null);
      File resourceApkFile = new File(resourceApkPath);
      ZipFile zipFile = new ZipFile(resourceApkFile);
      Enumeration<? extends ZipEntry> entries = zipFile.entries();
      ImmutableList.Builder<String> builder = ImmutableList.builder();
      while (entries.hasMoreElements()) {
        builder.add(entries.nextElement().getName());
      }
      assertTrue(builder.build().contains("assets/hilarity.txt"));
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
