/*
 * Copyright 2013-present Facebook, Inc.
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
package com.facebook.buck.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public class PropertyFinderTest {
  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testSdkPathFromPropertiesReturnsAbsentWithNoPropertiesFileOrEnvironment() {
    Optional<Properties> properties = Optional.absent();
    HostFilesystem hostFilesystem = FakeHostFilesystem.empty();
    ImmutableMap<String, String> systemEnvironment = ImmutableMap.of();

    Optional<Path> path = DefaultPropertyFinder.findDirectoryByPropertiesThenEnvironmentVariable(
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

    Optional<Path> path = DefaultPropertyFinder.findDirectoryByPropertiesThenEnvironmentVariable(
        Optional.of(properties),
        hostFilesystem,
        systemEnvironment,
        "sdk.dir");
    assertEquals(Paths.get("/path/to/sdk"), path.get());
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

    DefaultPropertyFinder.findDirectoryByPropertiesThenEnvironmentVariable(
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

    Optional<Path> path = DefaultPropertyFinder.findDirectoryByPropertiesThenEnvironmentVariable(
        properties,
        hostFilesystem,
        systemEnvironment,
        "sdk.dir",
        "ANDROID_SDK");
    assertEquals(Paths.get("/path/to/sdk"), path.get());
  }

  @Test
  public void testSdkPathFromEnvironmentRaisesExceptionWhenDirectoryIsNotPresent() {
    Optional<Properties> properties = Optional.absent();
    ImmutableMap<String, String> systemEnvironment = ImmutableMap.of("ANDROID_SDK", "/path/to/sdk");
    HostFilesystem hostFilesystem = FakeHostFilesystem.empty();

    thrown.expect(RuntimeException.class);
    thrown.expectMessage("Environment variable ANDROID_SDK points to invalid path [/path/to/sdk].");

    DefaultPropertyFinder.findDirectoryByPropertiesThenEnvironmentVariable(
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

    Optional<Path> path = DefaultPropertyFinder.findDirectoryByPropertiesThenEnvironmentVariable(
        properties,
        hostFilesystem,
        systemEnvironment,
        "sdk.dir",
        "ANDROID_SDK");
    assertFalse(path.isPresent());
  }
}
