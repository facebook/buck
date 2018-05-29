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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.Verbosity;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import org.junit.Test;

/** Test generation of command line flags based on creation parameters */
public class AaptStepTest {

  private Path basePath = Paths.get("/java/com/facebook/buck/example");
  private Path proguardConfig = basePath.resolve("mock_proguard.txt");

  /**
   * Build an AaptStep that can be used to generate a shell command. Should only be used for
   * checking the generated command, since it does not refer to useful directories (so it can't be
   * executed).
   */
  private AaptStep buildAaptStep(
      Path pathToGeneratedProguardConfig,
      boolean isCrunchFiles,
      boolean includesVectorDrawables,
      ManifestEntries manifestEntries) {
    return new AaptStep(
        BuildTargetFactory.newInstance("//dummy:target"),
        AndroidPlatformTarget.of(
            "android",
            basePath.resolve("mock_android.jar"),
            Collections.emptyList(),
            basePath.resolve("mock_aapt_bin"),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""),
            Paths.get("")),
        /* workingDirectory */ basePath,
        /* manifestDirectory */ basePath.resolve("AndroidManifest.xml"),
        /* resDirectories */ ImmutableList.of(),
        /* assetsDirectories */ ImmutableSortedSet.of(),
        /* pathToOutputApk */ basePath.resolve("build").resolve("out.apk"),
        /* pathToRDotDText */ basePath.resolve("r"),
        pathToGeneratedProguardConfig,
        ImmutableList.of(),
        isCrunchFiles,
        includesVectorDrawables,
        manifestEntries);
  }

  /**
   * Create an execution context with the given verbosity level. The execution context will yield
   * fake values relative to the base path for all target queries. The mock context returned has not
   * been replayed, so the calling code may add additional expectations, and is responsible for
   * calling replay().
   */
  private ExecutionContext createTestExecutionContext(Verbosity verbosity) {
    return TestExecutionContext.newBuilder().setConsole(new TestConsole(verbosity)).build();
  }

  @Test
  public void shouldEmitVerbosityFlagWithVerboseContext() {
    AaptStep aaptStep = buildAaptStep(proguardConfig, false, false, ManifestEntries.empty());
    ExecutionContext executionContext = createTestExecutionContext(Verbosity.ALL);

    ImmutableList<String> command = aaptStep.getShellCommandInternal(executionContext);
    assertTrue(command.contains("-v"));
  }

  @Test
  public void shouldNotEmitVerbosityFlagWithQuietContext() {
    AaptStep aaptStep = buildAaptStep(proguardConfig, false, false, ManifestEntries.empty());
    ExecutionContext executionContext = createTestExecutionContext(Verbosity.SILENT);

    ImmutableList<String> command = aaptStep.getShellCommandInternal(executionContext);
    assertFalse(command.contains("-v"));
  }

  @Test
  public void shouldEmitGFlagIfProguardConfigPresent() {
    AaptStep aaptStep = buildAaptStep(proguardConfig, false, false, ManifestEntries.empty());
    ExecutionContext executionContext = createTestExecutionContext(Verbosity.ALL);

    ImmutableList<String> command = aaptStep.getShellCommandInternal(executionContext);

    assertTrue(command.contains("-G"));
    String proguardConfigPath =
        MorePaths.pathWithPlatformSeparators("/java/com/facebook/buck/example/mock_proguard.txt");
    assertTrue(command.contains(proguardConfigPath));
  }

  @Test
  public void shouldEmitNoCrunchFlagIfNotCrunch() {
    AaptStep aaptStep = buildAaptStep(proguardConfig, false, false, ManifestEntries.empty());
    ExecutionContext executionContext = createTestExecutionContext(Verbosity.ALL);

    ImmutableList<String> command = aaptStep.getShellCommandInternal(executionContext);

    assertTrue(command.contains("--no-crunch"));
  }

  @Test
  public void shouldNotEmitNoCrunchFlagIfCrunch() {
    AaptStep aaptStep = buildAaptStep(proguardConfig, true, false, ManifestEntries.empty());
    ExecutionContext executionContext = createTestExecutionContext(Verbosity.ALL);

    ImmutableList<String> command = aaptStep.getShellCommandInternal(executionContext);

    assertFalse(command.contains("--no-crunch"));
  }

  @Test
  public void shouldEmitNoVersionVectorsFlagIfRequested() {
    AaptStep aaptStep = buildAaptStep(proguardConfig, false, true, ManifestEntries.empty());
    ExecutionContext executionContext = createTestExecutionContext(Verbosity.ALL);

    ImmutableList<String> command = aaptStep.getShellCommandInternal(executionContext);

    assertTrue(command.contains("--no-version-vectors"));
  }

  @Test
  public void shouldNotEmitNoVersionVectorsFlagIfNotRequested() {
    AaptStep aaptStep = buildAaptStep(proguardConfig, false, false, ManifestEntries.empty());
    ExecutionContext executionContext = createTestExecutionContext(Verbosity.ALL);

    ImmutableList<String> command = aaptStep.getShellCommandInternal(executionContext);

    assertFalse(command.contains("--no-version-vectors"));
  }

  @Test
  public void shouldEmitFlagsForManifestEntries() {
    ManifestEntries entries =
        ManifestEntries.builder()
            .setMinSdkVersion(3)
            .setTargetSdkVersion(5)
            .setVersionCode(7)
            .setVersionName("eleven")
            .setDebugMode(true)
            .build();
    AaptStep aaptStep = buildAaptStep(proguardConfig, true, false, entries);
    ExecutionContext executionContext = createTestExecutionContext(Verbosity.ALL);
    ImmutableList<String> command = aaptStep.getShellCommandInternal(executionContext);

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
  public void shouldNotEmitFailOnInsertWithoutManifestEntries() {
    AaptStep aaptStep = buildAaptStep(proguardConfig, true, false, ManifestEntries.empty());
    ExecutionContext executionContext = createTestExecutionContext(Verbosity.ALL);
    ImmutableList<String> command = aaptStep.getShellCommandInternal(executionContext);
    assertFalse(command.contains("--error-on-failed-insert"));
  }
}
