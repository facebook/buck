/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.anyObject;

import org.junit.Rule;
import org.junit.rules.ExpectedException;

import com.facebook.buck.util.FakeHostFilesystem;
import com.facebook.buck.util.HostFilesystem;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import org.easymock.EasyMockSupport;
import org.junit.Test;

import java.io.File;
import java.util.Properties;

/** Unit test for {@link AbstractCommandOptions}. */
public class AbstractCommandOptionsTest extends EasyMockSupport {

  private final File pathToNdk = new File("/path/to/ndk");

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testConstructor() {
    BuckConfig buckConfig = createMock(BuckConfig.class);
    AbstractCommandOptions options = new AbstractCommandOptions(buckConfig) {};

    assertSame(buckConfig, options.getBuckConfig());
  }

  private BuckConfig mockNdkBuckConfig(String minNdkVersion, String maxNdkVersion) {
    BuckConfig buckConfig = createMock(BuckConfig.class);

    expect(buckConfig.getMinimumNdkVersion())
        .andReturn(Optional.of(minNdkVersion));
    expect(buckConfig.getMaximumNdkVersion())
        .andReturn(Optional.of(maxNdkVersion));

    return buckConfig;
  }

  private ProjectFilesystem mockNdkFileSystem(String ndkVersion) {
    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);

    expect(projectFilesystem.readFirstLineFromFile(anyObject(File.class)))
        .andReturn(Optional.of(ndkVersion));

    return projectFilesystem;
  }

  @Test
  public void testNdkExactVersion() {
    BuckConfig buckConfig = mockNdkBuckConfig("r8", "r8");
    ProjectFilesystem projectFilesystem = mockNdkFileSystem("r8");

    replayAll();
    AbstractCommandOptions options = new AbstractCommandOptions(buckConfig) {};
    options.validateNdkVersion(projectFilesystem, pathToNdk);
    verifyAll();
  }

  @Test
  public void testNdkWideVersion() {
    BuckConfig buckConfig = mockNdkBuckConfig("r8", "r8e");
    ProjectFilesystem projectFilesystem = mockNdkFileSystem("r8d");

    replayAll();
    AbstractCommandOptions options = new AbstractCommandOptions(buckConfig) {};
    options.validateNdkVersion(projectFilesystem, pathToNdk);
    verifyAll();
  }

  @Test
  public void testNdkTooOldVersion() {
    BuckConfig buckConfig = mockNdkBuckConfig("r8", "r8e");
    ProjectFilesystem projectFilesystem = mockNdkFileSystem("r7");

    replayAll();
    AbstractCommandOptions options = new AbstractCommandOptions(buckConfig) {};
    try {
      options.validateNdkVersion(projectFilesystem, pathToNdk);
      fail("Should not be valid");
    } catch (HumanReadableException e) {
      assertEquals("Supported NDK versions are between r8 and r8e but Buck is configured to use r7 from " + pathToNdk.getAbsolutePath(), e.getMessage());
    }
    verifyAll();
  }

  @Test
  public void testNdkTooNewVersion() {
    BuckConfig buckConfig = mockNdkBuckConfig("r8", "r8e");
    ProjectFilesystem projectFilesystem = mockNdkFileSystem("r9");

    replayAll();
    AbstractCommandOptions options = new AbstractCommandOptions(buckConfig) {};
    try {
      options.validateNdkVersion(projectFilesystem, pathToNdk);
      fail("Should not be valid");
    } catch (HumanReadableException e) {
      assertEquals("Supported NDK versions are between r8 and r8e but Buck is configured to use r9 from " + pathToNdk.getAbsolutePath(), e.getMessage());
    }
    verifyAll();
  }

  @Test
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public void testNdkOnlyMinVersion() {
    BuckConfig buckConfig = createMock(BuckConfig.class);

    expect(buckConfig.getMinimumNdkVersion())
        .andReturn(Optional.of("r8"));
    Optional<String> empty = Optional.absent();
    expect(buckConfig.getMaximumNdkVersion())
        .andReturn(empty);

    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);

    replayAll();
    AbstractCommandOptions options = new AbstractCommandOptions(buckConfig) {};
    try {
      options.validateNdkVersion(projectFilesystem, pathToNdk);
      fail("Should not be valid");
    } catch (HumanReadableException e) {
      assertEquals("Either both min_version and max_version are provided or neither are", e.getMessage());
    }
    verifyAll();
  }

  @Test
  public void testSdkPathFromPropertiesReturnsAbsentWithNoPropertiesFileOrEnvironment() {
    Optional<Properties> properties = Optional.absent();
    HostFilesystem hostFilesystem = FakeHostFilesystem.empty();
    ImmutableMap<String, String> systemEnvironment = ImmutableMap.of();

    Optional<File> path = AbstractCommandOptions.findDirectoryByPropertiesThenEnvironmentVariable(
        properties,
        hostFilesystem,
        systemEnvironment,
        "sdk.dir");
    assertFalse(path.isPresent());
  }

  @Test
  public void testSdkPathFromPropertiesReturnsPathWhenDirectoryIsPresent() {
    Properties properties = new Properties();
    properties.setProperty("sdk.dir", "/path/to/sdk");
    ImmutableMap<String, String> systemEnvironment = ImmutableMap.of();
    HostFilesystem hostFilesystem = FakeHostFilesystem.withDirectories("/path/to/sdk");

    Optional<File> path = AbstractCommandOptions.findDirectoryByPropertiesThenEnvironmentVariable(
        Optional.of(properties),
        hostFilesystem,
        systemEnvironment,
        "sdk.dir");
    assertEquals(new File("/path/to/sdk"), path.get());
  }

  @Test
  public void testSdkPathFromPropertiesRaisesExceptionWhenDirectoryIsNotPresent() {
    Properties properties = new Properties();
    properties.setProperty("sdk.dir", "/path/to/sdk");
    ImmutableMap<String, String> systemEnvironment = ImmutableMap.of();
    HostFilesystem hostFilesystem = FakeHostFilesystem.empty();

    thrown.expect(RuntimeException.class);
    thrown.expectMessage(
        "Properties file local.properties contains invalid path [/path/to/sdk] for key sdk.dir.");

    AbstractCommandOptions.findDirectoryByPropertiesThenEnvironmentVariable(
        Optional.of(properties),
        hostFilesystem,
        systemEnvironment,
        "sdk.dir");
  }

  @Test
  public void testSdkPathFromEnvironmentReturnsPathWhenDirectoryIsPresent() {
    Optional<Properties> properties = Optional.absent();
    ImmutableMap<String, String> systemEnvironment = ImmutableMap.of("ANDROID_SDK", "/path/to/sdk");
    HostFilesystem hostFilesystem = FakeHostFilesystem.withDirectories("/path/to/sdk");

    Optional<File> path = AbstractCommandOptions.findDirectoryByPropertiesThenEnvironmentVariable(
        properties,
        hostFilesystem,
        systemEnvironment,
        "sdk.dir",
        "ANDROID_SDK");
    assertEquals(new File("/path/to/sdk"), path.get());
  }

  @Test
  public void testSdkPathFromEnvironmentRaisesExceptionWhenDirectoryIsNotPresent() {
    Optional<Properties> properties = Optional.absent();
    ImmutableMap<String, String> systemEnvironment = ImmutableMap.of("ANDROID_SDK", "/path/to/sdk");
    HostFilesystem hostFilesystem = FakeHostFilesystem.empty();

    thrown.expect(RuntimeException.class);
    thrown.expectMessage("Environment variable ANDROID_SDK points to invalid path [/path/to/sdk].");

    AbstractCommandOptions.findDirectoryByPropertiesThenEnvironmentVariable(
        properties,
        hostFilesystem,
        systemEnvironment,
        "sdk.dir",
        "ANDROID_SDK");
  }

  @Test
  public void testSdkPathFromEnvironmentReturnsAbsentWhenEnvironmentUnset() {
    Optional<Properties> properties = Optional.absent();
    ImmutableMap<String, String> systemEnvironment = ImmutableMap.of();
    HostFilesystem hostFilesystem = FakeHostFilesystem.empty();

    Optional<File> path = AbstractCommandOptions.findDirectoryByPropertiesThenEnvironmentVariable(
        properties,
        hostFilesystem,
        systemEnvironment,
        "sdk.dir",
        "ANDROID_SDK");
    assertFalse(path.isPresent());
  }
}
