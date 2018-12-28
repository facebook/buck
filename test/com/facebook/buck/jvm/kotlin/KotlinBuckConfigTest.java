/*
 * Copyright 2016-present Facebook, Inc.
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
package com.facebook.buck.jvm.kotlin;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class KotlinBuckConfigTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  Path testDataDirectory;

  @Before
  public void setUp() throws IOException {
    KotlinTestAssumptions.assumeUnixLike();

    tmp.newFolder("faux_kotlin_home");
    tmp.newFolder("faux_kotlin_home/bin");
    tmp.newFolder("faux_kotlin_home/libexec/bin");
    tmp.newFolder("faux_kotlin_home/libexec/lib");
    tmp.newExecutableFile("faux_kotlin_home/kotlinc");

    testDataDirectory = tmp.getRoot();
  }

  @Test
  public void testInitializesKotlincWithSourcePathFromPathWhenNotExternal() throws IOException {
    // GIVEN
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of("kotlin", ImmutableMap.of("kotlin_home", "faux_kotlin_home")))
            .build();

    buckConfig
        .getFilesystem()
        .createNewFile(Paths.get("faux_kotlin_home/libexec/lib/kotlin-compiler-embeddable.jar"));

    // WHEN
    KotlinBuckConfig kotlinBuckConfig = new KotlinBuckConfig(buckConfig);

    // THEN
    assertTrue(kotlinBuckConfig.getKotlinc() instanceof JarBackedReflectedKotlinc);
    assertFalse(kotlinBuckConfig.getKotlinHomeTarget().isPresent());
  }

  @Test
  public void testInitializesKotlincWithSourcePathFromTargetWhenNotExternal() {
    // GIVEN
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "kotlin", ImmutableMap.of("kotlin_home", "//faux_kotlin_home:home")))
            .build();

    // WHEN
    KotlinBuckConfig kotlinBuckConfig = new KotlinBuckConfig(buckConfig);

    // THEN
    assertTrue(kotlinBuckConfig.getKotlinc() instanceof JarBackedReflectedKotlinc);
    assertTrue(kotlinBuckConfig.getKotlinHomeTarget().isPresent());
  }

  @Test
  public void testInitializesKotlincWithPathWhenExternal() {
    // GIVEN
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "kotlin",
                    ImmutableMap.of(
                        "kotlin_home",
                        testDataDirectory.resolve("faux_kotlin_home").toAbsolutePath().toString(),
                        "external",
                        "true")))
            .build();

    // WHEN
    KotlinBuckConfig kotlinBuckConfig = new KotlinBuckConfig(buckConfig);

    // THEN
    assertTrue(kotlinBuckConfig.getKotlinc() instanceof ExternalKotlinc);
    assertFalse(kotlinBuckConfig.getKotlinHomeTarget().isPresent());
  }

  @Test
  public void testInitializesKotlincWithKotlinHomeEnvPathWhenExternal() {
    // GIVEN
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "kotlin",
                    ImmutableMap.of(
                        "kotlin_home",
                        testDataDirectory.resolve("faux_kotlin_home").toAbsolutePath().toString(),
                        "external",
                        "true")))
            .setEnvironment(
                ImmutableMap.of(
                    "KOTLIN_HOME",
                    testDataDirectory.resolve("faux_kotlin_home").toAbsolutePath().toString()))
            .build();

    // WHEN
    KotlinBuckConfig kotlinBuckConfig = new KotlinBuckConfig(buckConfig);

    // THEN
    assertTrue(kotlinBuckConfig.getKotlinc() instanceof ExternalKotlinc);
    assertFalse(kotlinBuckConfig.getKotlinHomeTarget().isPresent());
  }

  @Test
  public void testInitializesKotlincWithPathFromEnvPathWhenExternal() {
    // GIVEN
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("kotlin", ImmutableMap.of("external", "true")))
            .setEnvironment(
                ImmutableMap.of(
                    "PATH",
                    testDataDirectory.resolve("faux_kotlin_home").toAbsolutePath().toString()))
            .build();

    // WHEN
    KotlinBuckConfig kotlinBuckConfig = new KotlinBuckConfig(buckConfig);

    // THEN
    assertTrue(kotlinBuckConfig.getKotlinc() instanceof ExternalKotlinc);
    assertFalse(kotlinBuckConfig.getKotlinHomeTarget().isPresent());
  }
}
