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

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;

public class ProGuardConfigTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void proGuardJarOverrideUsedShouldBeRelativeToTheProjectRoot() throws IOException {
    Path proGuardJar = Paths.get("proguard.jar");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.touch(proGuardJar);

    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of("tools", ImmutableMap.of("proguard", proGuardJar.toString())))
            .setFilesystem(filesystem)
            .build();
    ProGuardConfig proGuardConfig = new ProGuardConfig(buckConfig);

    Optional<SourcePath> proGuardJarOverride = proGuardConfig.getProguardJarOverride();

    assertTrue(proGuardJarOverride.isPresent());
    assertEquals(FakeSourcePath.of(filesystem, proGuardJar), proGuardJarOverride.get());
  }

  @Test(expected = HumanReadableException.class)
  public void whenProGuardJarNotFound() {
    Path proGuardJar = Paths.get("proguard.jar");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of("tools", ImmutableMap.of("proguard", proGuardJar.toString())))
            .setFilesystem(filesystem)
            .build();
    ProGuardConfig proGuardConfig = new ProGuardConfig(buckConfig);

    Optional<SourcePath> proGuardJarOverride = proGuardConfig.getProguardJarOverride();

    assertTrue(proGuardJarOverride.isPresent());
    assertEquals(FakeSourcePath.of(filesystem, proGuardJar), proGuardJarOverride.get());
  }

  @Test
  public void whenProGuardMaxHeapSizeOverrideUsed() {
    String proGuardMaxHeapSize = "1234M";
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "tools", ImmutableMap.of("proguard-max-heap-size", proGuardMaxHeapSize)))
            .setFilesystem(filesystem)
            .build();
    ProGuardConfig proGuardConfig = new ProGuardConfig(buckConfig);

    assertEquals(proGuardMaxHeapSize, proGuardConfig.getProguardMaxHeapSize());
  }
}
