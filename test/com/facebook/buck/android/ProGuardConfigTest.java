/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class ProGuardConfigTest {

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Test
  public void proGuardJarOverrideUsedShouldBeRelativeToTheProjectRoot() throws IOException {
    Path proGuardJar = Paths.get("proguard.jar");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.touch(proGuardJar);

    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of(
            "tools",
            ImmutableMap.of("proguard", proGuardJar.toString())),
        filesystem);
    ProGuardConfig proGuardConfig = new ProGuardConfig(buckConfig);

    Optional<Path> proGuardJarOverride = proGuardConfig.getProguardJarOverride();

    assertTrue(proGuardJarOverride.isPresent());
    assertEquals(filesystem.getPathForRelativePath(proGuardJar), proGuardJarOverride.get());
  }

  @Test(expected = HumanReadableException.class)
  public void whenProGuardJarNotFound() throws IOException {
    Path proGuardJar = Paths.get("proguard.jar");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of(
            "tools",
            ImmutableMap.of("proguard", proGuardJar.toString())),
        filesystem);
    ProGuardConfig proGuardConfig = new ProGuardConfig(buckConfig);

    Optional<Path> proGuardJarOverride = proGuardConfig.getProguardJarOverride();

    assertTrue(proGuardJarOverride.isPresent());
    assertEquals(proGuardJar, proGuardJarOverride.get());
  }

  @Test
  public void whenProGuardMaxHeapSizeOverrideUsed() throws IOException {
    String proGuardMaxHeapSize = "1234M";
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of(
            "tools",
            ImmutableMap.of("proguard-max-heap-size", proGuardMaxHeapSize)),
        filesystem);
    ProGuardConfig proGuardConfig = new ProGuardConfig(buckConfig);

    assertEquals(proGuardMaxHeapSize, proGuardConfig.getProguardMaxHeapSize());
  }

}
