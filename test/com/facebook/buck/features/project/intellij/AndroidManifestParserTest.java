/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.features.project.intellij.lang.android.AndroidManifestParser;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AndroidManifestParserTest {

  @Rule public TemporaryPaths temp = new TemporaryPaths();

  private AndroidManifestParser androidManifestParser;

  @Before
  public void setUp() {
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
    AbsPath outDir = temp.newFolder();
    AbsPath manifestPath = outDir.resolve("AndroidManifest.xml");

    Optional<String> minSdkVersion =
        androidManifestParser.parseMinSdkVersion(manifestPath.getPath());
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
    AbsPath outDir = temp.newFolder();
    AbsPath manifestPath = outDir.resolve("AndroidManifest.xml");
    Optional<String> packageName = androidManifestParser.parsePackage(manifestPath.getPath());

    Assert.assertFalse(packageName.isPresent());
  }

  @Test
  public void testPermissionsWhenPresent() {
    Path directory = TestDataHelper.getTestDataDirectory(this);
    Path androidManifestPath =
        directory.resolve("manifests/manifest_with_permissions/AndroidManifest.xml");
    ImmutableSet<String> permissions = androidManifestParser.parsePermissions(androidManifestPath);

    Assert.assertEquals(2, permissions.size());
    Assert.assertTrue(
        permissions.containsAll(Arrays.asList("TEST_PERMISSION_1", "TEST_PERMISSION_2")));
  }

  @Test
  public void testEmptyPermissionWhenNotPresent() {
    Path directory = TestDataHelper.getTestDataDirectory(this);
    Path androidManifestPath =
        directory.resolve("manifests/manifest_without_permissions/AndroidManifest.xml");
    ImmutableSet<String> permissions = androidManifestParser.parsePermissions(androidManifestPath);

    Assert.assertEquals(0, permissions.size());
  }

  @Test
  public void testInvalidPermissionWhenPresent() {
    Path directory = TestDataHelper.getTestDataDirectory(this);
    Path androidManifestPath =
        directory.resolve("manifests/manifest_with_invalid_permissions/AndroidManifest.xml");
    ImmutableSet<String> permissions = androidManifestParser.parsePermissions(androidManifestPath);

    Assert.assertEquals(0, permissions.size());
  }
}
