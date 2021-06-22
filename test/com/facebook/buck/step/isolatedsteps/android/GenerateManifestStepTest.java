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

package com.facebook.buck.step.isolatedsteps.android;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.watchman.WatchmanError;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
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
    StepExecutionContext context = TestExecutionContext.newInstance(tmpFolder.getRoot());
    ProjectFilesystem filesystem =
        context
            .getProjectFilesystemFactory()
            .createProjectFilesystem(
                CanonicalCellName.rootCell(),
                tmpFolder.getRoot(),
                BuckPaths.DEFAULT_BUCK_OUT_INCLUDE_TARGET_CONFIG_HASH,
                new WatchmanFactory.NullWatchman("GenerateManifestStepTest", WatchmanError.TEST));

    RelPath expectedOutputPath = RelPath.get("AndroidManifest.expected.xml");
    RelPath skeletonPath = RelPath.get("AndroidManifestSkeleton.xml");
    ImmutableSet<RelPath> libraryManifestFiles =
        RichStream.of("AndroidManifestA.xml", "AndroidManifestB.xml", "AndroidManifestC.xml")
            .map(RelPath::get)
            .toImmutableSet();

    RelPath outputPath = RelPath.get("AndroidManifest.xml");
    RelPath mergeReportPath = RelPath.get("merge-report.txt");

    GenerateManifestStep manifestCommand =
        new GenerateManifestStep(
            skeletonPath,
            APKModuleGraph.ROOT_APKMODULE_NAME,
            libraryManifestFiles,
            outputPath,
            mergeReportPath);
    int result = manifestCommand.execute(context).getExitCode();

    assertEquals(0, result);

    List<String> expected =
        Files.lines(filesystem.resolve(expectedOutputPath).getPath()).collect(Collectors.toList());
    List<String> output =
        Files.lines(filesystem.resolve(outputPath).getPath()).collect(Collectors.toList());

    assertEquals(expected, output);

    String report = new String(Files.readAllBytes(filesystem.resolve(mergeReportPath).getPath()));
    assertThat(report, containsString("ADDED"));
    assertThat(report, containsString("MERGED"));
  }

  @Test
  public void testManifestGenerationWithModule() throws Exception {
    StepExecutionContext context = TestExecutionContext.newInstance(tmpFolder.getRoot());
    ProjectFilesystem filesystem =
        context
            .getProjectFilesystemFactory()
            .createProjectFilesystem(
                CanonicalCellName.rootCell(),
                tmpFolder.getRoot(),
                BuckPaths.DEFAULT_BUCK_OUT_INCLUDE_TARGET_CONFIG_HASH,
                new WatchmanFactory.NullWatchman("GenerateManifestStepTest", WatchmanError.TEST));

    RelPath expectedOutputPath = RelPath.get("ModuleManifest.expected.xml");
    RelPath skeletonPath = RelPath.get("ModuleManifestSkeleton.xml");
    ImmutableSet<RelPath> libraryManifestFiles =
        RichStream.of("AndroidManifestA.xml", "AndroidManifestB.xml", "AndroidManifestC.xml")
            .map(RelPath::get)
            .toImmutableSet();

    RelPath outputPath = RelPath.get("AndroidManifest.xml");
    RelPath mergeReportPath = RelPath.get("merge-report.txt");

    GenerateManifestStep manifestCommand =
        new GenerateManifestStep(
            skeletonPath, "MODULE_NAME", libraryManifestFiles, outputPath, mergeReportPath);
    int result = manifestCommand.execute(context).getExitCode();

    assertEquals(0, result);

    List<String> expected =
        Files.lines(filesystem.resolve(expectedOutputPath).getPath()).collect(Collectors.toList());
    List<String> output =
        Files.lines(filesystem.resolve(outputPath).getPath()).collect(Collectors.toList());

    assertEquals(expected, output);

    String report = new String(Files.readAllBytes(filesystem.resolve(mergeReportPath).getPath()));
    assertThat(report, containsString("ADDED"));
    assertThat(report, containsString("MERGED"));
  }
}
