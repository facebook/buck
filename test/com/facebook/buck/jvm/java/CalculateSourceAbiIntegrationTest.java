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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.zip.CustomJarOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class CalculateSourceAbiIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() throws InterruptedException, IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "source_abi", tmp);
    workspace.setUp();

    filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
  }

  @Test
  public void testAbiJarIncludesGeneratedClasses() throws IOException {
    BuildTarget mainTarget = BuildTargetFactory.newInstance("//:main");
    ProcessResult buildResult = workspace.runBuckBuild(mainTarget.getFullyQualifiedName());
    buildResult.assertSuccess();

    // Make sure we built the source ABI
    BuildTarget abiTarget = BuildTargetFactory.newInstance("//:lib#source-abi");
    workspace.getBuildLog().assertTargetBuiltLocally(abiTarget.getFullyQualifiedName());

    Path abiJarPath =
        filesystem.getPathForRelativePath(
            BuildTargets.getGenPath(filesystem, abiTarget, "lib__%s__output/lib-abi.jar"));
    assertTrue(Files.exists(abiJarPath));

    // Check that the jar has an entry for the generated class
    try (JarFile abiJar = new JarFile(abiJarPath.toFile())) {
      assertThat(
          abiJar.stream().map(JarEntry::getName).collect(Collectors.toSet()),
          Matchers.containsInAnyOrder(
              "META-INF/",
              "META-INF/MANIFEST.MF",
              "com/",
              "com/example/",
              "com/example/buck/",
              "com/example/buck/Lib.class",
              "com/example/buck/Lib2.class",
              "com/example/buck/Test.class"));

      // And the manifest has one too
      Manifest manifest = abiJar.getManifest();
      assertNotNull(
          manifest
              .getAttributes("com/example/buck/Test.class")
              .getValue(CustomJarOutputStream.DIGEST_ATTRIBUTE_NAME));
    }
  }

  @Test
  public void testErrorsReportedGracefully() throws IOException {
    ProcessResult buildResult = workspace.runBuckBuild("//:main-errors");
    buildResult.assertFailure();
    assertThat(
        buildResult.getStderr(),
        Matchers.stringContainsInOrder(
            "cannot find symbol",
            "Nonexistent<Integer>",
            "cannot find symbol",
            "List<Nonexistent"));
  }

  @Test
  public void testAbiJarExcludesRemovedClasses() throws IOException {
    BuildTarget mainTarget = BuildTargetFactory.newInstance("//:main-stripped");
    ProcessResult buildResult = workspace.runBuckBuild(mainTarget.getFullyQualifiedName());
    buildResult.assertFailure();
    assertThat(buildResult.getStderr(), Matchers.stringContainsInOrder("cannot find symbol"));

    // Make sure we built the source ABI
    BuildTarget abiTarget = BuildTargetFactory.newInstance("//:lib-stripped#source-abi");
    workspace.getBuildLog().assertTargetBuiltLocally(abiTarget.getFullyQualifiedName());

    Path abiJarPath =
        filesystem.getPathForRelativePath(
            BuildTargets.getGenPath(filesystem, abiTarget, "lib__%s__output/lib-stripped-abi.jar"));
    assertTrue(Files.exists(abiJarPath));

    // Check that the jar does not have an entry for the removed class
    try (JarFile abiJar = new JarFile(abiJarPath.toFile())) {
      assertThat(
          abiJar.stream().map(JarEntry::getName).collect(Collectors.toSet()),
          Matchers.containsInAnyOrder(
              "META-INF/",
              "META-INF/MANIFEST.MF",
              "com/",
              "com/example/",
              "com/example/buck/",
              "com/example/buck/Lib.class"));

      // And the manifest doesn't have one either
      Manifest manifest = abiJar.getManifest();
      assertNull(manifest.getAttributes("com/example/buck/Test.class"));
    }
  }

  @Test
  public void testAbiJarSupportsDepFileRuleKey() throws IOException {
    BuildTarget mainTarget = BuildTargetFactory.newInstance("//:main");
    ProcessResult buildResult = workspace.runBuckBuild(mainTarget.getFullyQualifiedName());
    buildResult.assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:main");
    workspace.getBuildLog().assertTargetBuiltLocally("//:lib#source-abi");

    workspace.replaceFileContents("Lib2.java", "changeMe", "changedMe");
    buildResult = workspace.runBuckBuild(mainTarget.getFullyQualifiedName());
    buildResult.assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally("//:lib#source-abi");
    workspace.getBuildLog().assertTargetHadMatchingDepfileRuleKey("//:main");
  }
}
