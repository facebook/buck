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

package com.facebook.buck.android;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class GenerateManifestStepTest {
  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  @Before
  public void setUp() throws IOException {
    TestDataHelper.createProjectWorkspaceForScenario(this, "create_manifest", tmpFolder).setUp();
  }

  @Test
  public void testManifestGeneration() throws Exception {
    ExecutionContext context = TestExecutionContext.newInstance();
    ProjectFilesystem filesystem =
        context
            .getProjectFilesystemFactory()
            .createProjectFilesystem(
                CanonicalCellName.rootCell(),
                AbsPath.of(tmpFolder.getRoot()),
                BuckPaths.DEFAULT_BUCK_OUT_INCLUDE_TARGET_CONFIG_HASH);

    Path expectedOutputPath = Paths.get("AndroidManifest.expected.xml");
    Path skeletonPath = Paths.get("AndroidManifestSkeleton.xml");
    ImmutableSet<Path> libraryManifestFiles =
        RichStream.of("AndroidManifestA.xml", "AndroidManifestB.xml", "AndroidManifestC.xml")
            .map(Paths::get)
            .toImmutableSet();

    Path outputPath = tmpFolder.getRoot().resolve("AndroidManifest.xml");
    Path mergeReportPath = tmpFolder.getRoot().resolve("merge-report.txt");

    GenerateManifestStep manifestCommand =
        new GenerateManifestStep(
            filesystem,
            skeletonPath,
            APKModule.of(APKModuleGraph.ROOT_APKMODULE_NAME, true, true),
            libraryManifestFiles,
            outputPath,
            mergeReportPath);
    int result = manifestCommand.execute(context).getExitCode();

    assertEquals(0, result);

    List<String> expected =
        Files.lines(filesystem.resolve(expectedOutputPath)).collect(Collectors.toList());
    List<String> output = Files.lines(outputPath).collect(Collectors.toList());

    assertEquals(expected, output);

    String report = new String(Files.readAllBytes(mergeReportPath));
    assertThat(report, containsString("ADDED"));
    assertThat(report, containsString("MERGED"));
  }

  @Test
  public void testManifestGenerationWithModule() throws Exception {
    ExecutionContext context = TestExecutionContext.newInstance();
    ProjectFilesystem filesystem =
        context
            .getProjectFilesystemFactory()
            .createProjectFilesystem(
                CanonicalCellName.rootCell(),
                AbsPath.of(tmpFolder.getRoot()),
                BuckPaths.DEFAULT_BUCK_OUT_INCLUDE_TARGET_CONFIG_HASH);

    Path expectedOutputPath = Paths.get("ModuleManifest.expected.xml");
    Path skeletonPath = Paths.get("ModuleManifestSkeleton.xml");
    ImmutableSet<Path> libraryManifestFiles =
        RichStream.of("AndroidManifestA.xml", "AndroidManifestB.xml", "AndroidManifestC.xml")
            .map(Paths::get)
            .toImmutableSet();

    Path outputPath = tmpFolder.getRoot().resolve("AndroidManifest.xml");
    Path mergeReportPath = tmpFolder.getRoot().resolve("merge-report.txt");

    GenerateManifestStep manifestCommand =
        new GenerateManifestStep(
            filesystem,
            skeletonPath,
            APKModule.of("MODULE_NAME", false, false),
            libraryManifestFiles,
            outputPath,
            mergeReportPath);
    int result = manifestCommand.execute(context).getExitCode();

    assertEquals(0, result);

    List<String> expected =
        Files.lines(filesystem.resolve(expectedOutputPath)).collect(Collectors.toList());
    List<String> output = Files.lines(outputPath).collect(Collectors.toList());

    assertEquals(expected, output);

    String report = new String(Files.readAllBytes(mergeReportPath));
    assertThat(report, containsString("ADDED"));
    assertThat(report, containsString("MERGED"));
  }
}
