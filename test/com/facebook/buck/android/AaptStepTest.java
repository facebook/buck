/*
 * Copyright 2015-present Facebook, Inc.
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

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Test generation of command line flags based on creation parameters
 */
public class AaptStepTest {

  private Path basePath = Paths.get("/java/com/facebook/buck/example");

  /**
   * Build an AaptStep that can be used to generate a shell command. Should only
   * be used for checking the generated command, since it does not refer to useful
   * directories (so it can't be executed).
   */
  private AaptStep buildAaptStep(
      Optional<Path> pathToGeneratedProguardConfig,
      boolean isCrunchFiles,
      ManifestEntries manifestEntries) {
    return new AaptStep(
        /* workingDirectory */ basePath,
        /* manifestDirectory */ basePath.resolve("AndroidManifest.xml"),
        /* resDirectories */ ImmutableList.<Path>of(),
        /* assetsDirectories */ ImmutableSortedSet.<Path>of(),
        /* pathToOutputApk */ basePath.resolve("build").resolve("out.apk"),
        /* pathToRDotDText */ basePath.resolve("r"),
        pathToGeneratedProguardConfig,
        isCrunchFiles,
        manifestEntries
    );
  }

  /**
   * Create an execution context with the given verbosity level. The execution context
   * will yield fake values relative to the base path for all target queries.
   * The mock context returned has not been replayed, so the calling code
   * may add additional expectations, and is responsible for calling replay().
   */
  private ExecutionContext makeMockExecutionContext(Verbosity verbosity) {
    ExecutionContext mockContext = createMock(ExecutionContext.class);
    expect(mockContext.getVerbosity()).andReturn(verbosity);

    AndroidPlatformTarget mockTarget = createMock(AndroidPlatformTarget.class);
    expect(mockTarget.getAaptExecutable()).andReturn(basePath.resolve("mock_aapt_bin"));
    expect(mockTarget.getAndroidJar()).andReturn(basePath.resolve("mock_android.jar"));
    replay(mockTarget);
    expect(mockContext.getAndroidPlatformTarget()).andReturn(mockTarget);

    return mockContext;
  }

  @Test
  public void shouldEmitVerbosityFlagWithVerboseContext() throws Exception {
    AaptStep aaptStep = buildAaptStep(Optional.<Path>absent(), false, ManifestEntries.empty());
    ExecutionContext mockContext = makeMockExecutionContext(Verbosity.ALL);
    replay(mockContext);

    ImmutableList<String> command = aaptStep.getShellCommandInternal(mockContext);
    assertTrue(command.contains("-v"));
  }

  @Test
  public void shouldNotEmitVerbosityFlagWithQuietContext() throws Exception {
    AaptStep aaptStep = buildAaptStep(Optional.<Path>absent(), false, ManifestEntries.empty());
    ExecutionContext mockContext = makeMockExecutionContext(Verbosity.SILENT);
    replay(mockContext);

    ImmutableList<String> command = aaptStep.getShellCommandInternal(mockContext);
    assertFalse(command.contains("-v"));
  }

  @Test
  public void shouldEmitGFlagIfProguardConfigPresent() throws Exception {
    Path proguardConfig = basePath.resolve("mock_proguard.txt");
    AaptStep aaptStep = buildAaptStep(Optional.of(proguardConfig), false, ManifestEntries.empty());
    ExecutionContext mockContext = makeMockExecutionContext(Verbosity.ALL);
    replay(mockContext);

    ImmutableList<String> command = aaptStep.getShellCommandInternal(mockContext);

    assertTrue(command.contains("-G"));
    assertTrue(command.contains("/java/com/facebook/buck/example/mock_proguard.txt"));
  }

  @Test
  public void shouldEmitNoCrunchFlagIfNotCrunch() throws Exception {
    AaptStep aaptStep = buildAaptStep(Optional.<Path>absent(), false, ManifestEntries.empty());
    ExecutionContext mockContext = makeMockExecutionContext(Verbosity.ALL);
    replay(mockContext);

    ImmutableList<String> command = aaptStep.getShellCommandInternal(mockContext);

    assertTrue(command.contains("--no-crunch"));
  }

  @Test
  public void shouldNotEmitNoCrunchFlagIfCrunch() throws Exception {
    AaptStep aaptStep = buildAaptStep(Optional.<Path>absent(), true, ManifestEntries.empty());
    ExecutionContext mockContext = makeMockExecutionContext(Verbosity.ALL);
    replay(mockContext);

    ImmutableList<String> command = aaptStep.getShellCommandInternal(mockContext);

    assertFalse(command.contains("--no-crunch"));
  }

  @Test
  public void shouldEmitFlagsForManifestEntries() throws Exception {
    ManifestEntries entries = ManifestEntries.builder()
        .setMinSdkVersion(3)
        .setTargetSdkVersion(5)
        .setVersionCode(7)
        .setVersionName("eleven")
        .setDebugMode(true)
        .build();
    AaptStep aaptStep = buildAaptStep(Optional.<Path>absent(), true, entries);
    ExecutionContext mockContext = makeMockExecutionContext(Verbosity.ALL);
    replay(mockContext);

    ImmutableList<String> command = aaptStep.getShellCommandInternal(mockContext);

    assertTrue(command.contains("--min-sdk-version"));
    assertEquals("3", command.get(command.indexOf("--min-sdk-version") + 1));

    assertTrue(command.contains("--target-sdk-version"));
    assertEquals("5", command.get(command.indexOf("--target-sdk-version") + 1));

    assertTrue(command.contains("--version-code"));
    assertEquals("7", command.get(command.indexOf("--version-code") + 1));

    assertTrue(command.contains("--version-name"));
    assertEquals("eleven", command.get(command.indexOf("--version-name") + 1));

    assertTrue(command.contains("--debug-mode"));
    // This should be present because we've emitted > 0 manifest-changing flags.
    assertTrue(command.contains("--error-on-failed-insert"));
  }

  @Test
  public void shouldNotEmitFailOnInsertWithoutManifestEntries() throws Exception {
    AaptStep aaptStep = buildAaptStep(Optional.<Path>absent(), true, ManifestEntries.empty());
    ExecutionContext mockContext = makeMockExecutionContext(Verbosity.ALL);
    replay(mockContext);
    ImmutableList<String> command = aaptStep.getShellCommandInternal(mockContext);
    assertFalse(command.contains("--error-on-failed-insert"));
  }
}
