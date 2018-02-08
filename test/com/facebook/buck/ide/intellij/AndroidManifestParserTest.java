/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.ide.intellij;

import com.facebook.buck.ide.intellij.lang.android.AndroidManifestParser;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AndroidManifestParserTest {
  @Rule public TemporaryPaths temp = new TemporaryPaths();

  private AndroidManifestParser androidManifestParser;

  @Before
  public void setUp() throws Exception {
    androidManifestParser = new AndroidManifestParser(new FakeProjectFilesystem());
  }

  @Test
  public void testMinSdkVersionWhenPresent() {
    Path directory = TestDataHelper.getTestDataDirectory(this);
    Path androidManifestPath =
        directory.resolve("manifests/manifest_with_min_sdk/AndroidManifest.xml");
    Optional<String> minSdkVersion = androidManifestParser.parseMinSdkVersion(androidManifestPath);

    Assert.assertEquals(Optional.of("6"), minSdkVersion);
  }

  @Test
  public void testEmptyMinSdkVersionWhenNodeMissing() {
    Path directory = TestDataHelper.getTestDataDirectory(this);
    Path androidManifestPath = directory.resolve("manifests/missing_uses_sdk/AndroidManifest.xml");
    Optional<String> minSdkVersion = androidManifestParser.parseMinSdkVersion(androidManifestPath);

    Assert.assertFalse(minSdkVersion.isPresent());
  }

  @Test
  public void testEmptyMinSdkVersionWhenAttributeMissing() {
    Path directory = TestDataHelper.getTestDataDirectory(this);
    Path androidManifestPath =
        directory.resolve("manifests/missing_min_sdk_attribute/AndroidManifest.xml");
    Optional<String> minSdkVersion = androidManifestParser.parseMinSdkVersion(androidManifestPath);

    Assert.assertFalse(minSdkVersion.isPresent());
  }

  @Test
  public void testEmptyMinSdkVersionWithMalformedXML() {
    Path directory = TestDataHelper.getTestDataDirectory(this);
    Path androidManifestPath =
        directory.resolve("manifests/manifest_with_malformed_xml/AndroidManifest.xml");
    Optional<String> minSdkVersion = androidManifestParser.parseMinSdkVersion(androidManifestPath);

    Assert.assertFalse(minSdkVersion.isPresent());
  }

  @Test
  public void testEmptyMinSdkVersionWhenFileMissing() throws IOException {
    Path outDir = temp.newFolder().toAbsolutePath();
    Path manifestPath = outDir.resolve("AndroidManifest.xml");

    Optional<String> minSdkVersion = androidManifestParser.parseMinSdkVersion(manifestPath);
    Assert.assertFalse(minSdkVersion.isPresent());
  }

  @Test
  public void testPackageWhenPresent() {
    Path directory = TestDataHelper.getTestDataDirectory(this);
    Path androidManifestPath =
        directory.resolve("manifests/manifest_with_min_sdk/AndroidManifest.xml");
    Optional<String> packageName = androidManifestParser.parsePackage(androidManifestPath);

    Assert.assertEquals(Optional.of("com.test"), packageName);
  }

  @Test
  public void testEmptyPackageWhenNodeMissing() {
    Path directory = TestDataHelper.getTestDataDirectory(this);
    Path androidManifestPath =
        directory.resolve("manifests/missing_manifest_node/AndroidManifest.xml");
    Optional<String> packageName = androidManifestParser.parsePackage(androidManifestPath);

    Assert.assertFalse(packageName.isPresent());
  }

  @Test
  public void testEmptyPackageWhenAttributeMissing() {
    Path directory = TestDataHelper.getTestDataDirectory(this);
    Path androidManifestPath =
        directory.resolve("manifests/missing_package_attribute/AndroidManifest.xml");
    Optional<String> packageName = androidManifestParser.parsePackage(androidManifestPath);

    Assert.assertFalse(packageName.isPresent());
  }

  @Test
  public void testEmptyPackageWithMalformedXML() {
    Path directory = TestDataHelper.getTestDataDirectory(this);
    Path androidManifestPath =
        directory.resolve("manifests/manifest_with_malformed_xml/AndroidManifest.xml");
    Optional<String> packageName = androidManifestParser.parsePackage(androidManifestPath);

    Assert.assertFalse(packageName.isPresent());
  }

  @Test
  public void testEmptyPackageWhenFileMissing() throws IOException {
    Path outDir = temp.newFolder().toAbsolutePath();
    Path manifestPath = outDir.resolve("AndroidManifest.xml");
    Optional<String> packageName = androidManifestParser.parsePackage(manifestPath);

    Assert.assertFalse(packageName.isPresent());
  }
}
