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

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.toolchain.tool.impl.testutil.SimpleTool;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.JavaCompilationConstants;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ProGuardObfuscateStepTest {
  @Rule public final TemporaryPaths tmpDir = new TemporaryPaths();

  private ExecutionContext executionContext;
  private AndroidPlatformTarget androidPlatformTarget;

  @Before
  public void setUp() {
    androidPlatformTarget =
        AndroidPlatformTarget.of(
            "android",
            Paths.get(""),
            Collections.emptyList(),
            () -> new SimpleTool(""),
            () -> new SimpleTool(""),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""),
            Paths.get("proguard.jar"),
            Paths.get("sdk-default.pro"),
            Paths.get("sdk-optimized.pro"));
    executionContext = TestExecutionContext.newBuilder().build();
  }

  @Test
  public void testCreateEmptyZip() throws Exception {
    Path tmpFile = tmpDir.newFile();
    ProGuardObfuscateStep.createEmptyZip(tmpFile);

    // Try to read it.
    try (ZipFile zipFile = new ZipFile(tmpFile.toFile())) {
      int totalSize = 0;
      List<? extends ZipEntry> entries = Collections.list(zipFile.entries());

      assertTrue("Expected either 0 or 1 entry", entries.size() <= 1);
      for (ZipEntry entry : entries) {
        totalSize += entry.getSize();
      }
      assertEquals("Zip file should have zero-length contents", 0, totalSize);
    }
  }

  @Test
  public void testSdkConfigArgs() {
    Path cwd = Paths.get("root");

    checkSdkConfig(
        cwd, ProGuardObfuscateStep.SdkProguardType.DEFAULT, Optional.empty(), "sdk-default.pro");
    checkSdkConfig(
        cwd,
        ProGuardObfuscateStep.SdkProguardType.OPTIMIZED,
        Optional.empty(),
        "sdk-optimized.pro");
    checkSdkConfig(cwd, ProGuardObfuscateStep.SdkProguardType.NONE, Optional.empty(), null);
    checkSdkConfig(
        cwd, ProGuardObfuscateStep.SdkProguardType.NONE, Optional.of("/some/path"), null);
  }

  @Test
  public void testAdditionalLibraryJarsParameterFormatting() {
    Path cwd = Paths.get("root");

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    ProGuardObfuscateStep.create(
        androidPlatformTarget,
        JavaCompilationConstants.DEFAULT_JAVA_COMMAND_PREFIX,
        new FakeProjectFilesystem(),
        /* proguardJarOverride */ Optional.empty(),
        "1024M",
        Optional.empty(),
        /* customProguardConfigs */ ImmutableSet.of(),
        ProGuardObfuscateStep.SdkProguardType.DEFAULT,
        /* optimizationPasses */ OptionalInt.empty(),
        /* proguardJvmArgs */ Optional.empty(),
        /* inputAndOutputEntries */ ImmutableMap.of(),
        /* additionalLibraryJarsForProguard */ ImmutableSet.of(
            Paths.get("myfavorite.jar"), Paths.get("another.jar")),
        Paths.get("proguard-directory"),
        new FakeBuildableContext(),
        FakeBuildContext.NOOP_CONTEXT,
        false,
        steps);
    ProGuardObfuscateStep.CommandLineHelperStep commandLineHelperStep =
        (ProGuardObfuscateStep.CommandLineHelperStep) steps.build().get(2);
    ImmutableList<String> parameters = commandLineHelperStep.getParameters(cwd);
    int libraryJarsArgIndex = parameters.indexOf("-libraryjars");
    int libraryJarsValueIndex =
        parameters.indexOf("myfavorite.jar" + File.pathSeparatorChar + "another.jar");
    assertNotEquals(-1, libraryJarsArgIndex);
    assertEquals(libraryJarsValueIndex, libraryJarsArgIndex + 1);
  }

  @Test
  public void testProguardJvmArgs() {
    List<String> proguardJvmArgs = Arrays.asList("-Dparam1=value1", "-Dparam2=value2");

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    ProGuardObfuscateStep.create(
        androidPlatformTarget,
        JavaCompilationConstants.DEFAULT_JAVA_COMMAND_PREFIX,
        new FakeProjectFilesystem(),
        /* proguardJarOverride */ Optional.empty(),
        "1024M",
        Optional.empty(),
        /* customProguardConfigs */ ImmutableSet.of(),
        ProGuardObfuscateStep.SdkProguardType.DEFAULT,
        /* optimizationPasses */ OptionalInt.empty(),
        Optional.of(proguardJvmArgs),
        /* inputAndOutputEntries */ ImmutableMap.of(),
        /* additionalLibraryJarsForProguard */ ImmutableSet.of(
            Paths.get("myfavorite.jar"), Paths.get("another.jar")),
        Paths.get("proguard-directory"),
        new FakeBuildableContext(),
        FakeBuildContext.NOOP_CONTEXT,
        false,
        steps);
    ProGuardObfuscateStep proguardStep = (ProGuardObfuscateStep) steps.build().get(3);
    ImmutableList<String> parameters = proguardStep.getShellCommandInternal(executionContext);
    for (String s : proguardJvmArgs) {
      assertNotEquals(-1, parameters.indexOf(s));
    }
  }

  private void checkSdkConfig(
      Path cwd,
      ProGuardObfuscateStep.SdkProguardType sdkProguardConfig,
      Optional<String> proguardAgentPath,
      String expectedPath) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    ProGuardObfuscateStep.create(
        androidPlatformTarget,
        JavaCompilationConstants.DEFAULT_JAVA_COMMAND_PREFIX,
        new FakeProjectFilesystem(),
        /* proguardJarOverride */ Optional.empty(),
        "1024M",
        proguardAgentPath,
        /* customProguardConfigs */ ImmutableSet.of(),
        sdkProguardConfig,
        /* optimizationPasses */ OptionalInt.empty(),
        /* proguardJvmArgs */ Optional.empty(),
        /* inputAndOutputEntries */ ImmutableMap.of(),
        /* additionalLibraryJarsForProguard */ ImmutableSet.of(),
        Paths.get("proguard-directory"),
        new FakeBuildableContext(),
        FakeBuildContext.NOOP_CONTEXT,
        false,
        steps);
    ProGuardObfuscateStep.CommandLineHelperStep commandLineHelperStep =
        (ProGuardObfuscateStep.CommandLineHelperStep) steps.build().get(2);

    String found = null;
    Iterator<String> argsIt = commandLineHelperStep.getParameters(cwd).iterator();
    while (argsIt.hasNext()) {
      String arg = argsIt.next();
      if (!arg.equals("-include")) {
        continue;
      }
      assertTrue(argsIt.hasNext());
      String file = argsIt.next();
      if (file.startsWith("sdk-")) {
        found = file;
        break;
      }
    }

    assertEquals(expectedPath, found);
  }
}
