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

package com.facebook.buck.cli;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.Escaper;
import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

public class HashedBuckOutIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private boolean isHashed(
      ProjectWorkspace workspace,
      String target,
      String overridesCell,
      Iterable<String> excludes,
      Iterable<String> includes) {
    String output =
        Splitter.on(System.lineSeparator())
            .splitToList(
                workspace
                    .runBuckCommand(
                        "targets",
                        "-c",
                        "project.buck_out_include_target_config_hash=true",
                        "-c",
                        String.format(
                            "%s//project.buck_out_target_config_hash_excludes=%s",
                            overridesCell,
                            Joiner.on(',')
                                .join(
                                    Iterables.transform(
                                        excludes,
                                        Escaper.escaper(Escaper.Quoter.DOUBLE, CharMatcher.none())
                                            ::apply))),
                        "-c",
                        String.format(
                            "%s//project.buck_out_target_config_hash_includes=%s",
                            overridesCell,
                            Joiner.on(',')
                                .join(
                                    Iterables.transform(
                                        includes,
                                        Escaper.escaper(Escaper.Quoter.DOUBLE, CharMatcher.none())
                                            ::apply))),
                        "--show-output",
                        target)
                    .assertSuccess()
                    .getStdout())
            .get(0);

    List<String> parts = Splitter.on(' ').splitToList(output);
    BuildTarget realTarget = BuildTargetFactory.newInstance(parts.get(0));
    String path = parts.get(1);

    String nonHashedPrefix =
        overridesCell.equals(CanonicalCellName.rootCell().getName())
            ? String.format("buck-out/gen/%s", realTarget.getBaseName().getPath())
            : String.format(
                "buck-out/cells/%s/gen/%s", overridesCell, realTarget.getBaseName().getPath());
    return !path.startsWith(MorePaths.pathWithPlatformSeparators(nonHashedPrefix));
  }

  @Test
  public void testSettings() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "multiple_cell_build", tmp);
    workspace.setUp();
    assertTrue(
        isHashed(
            workspace,
            "//main:TestJava",
            CanonicalCellName.rootCell().getName(),
            ImmutableList.of(),
            ImmutableList.of()));
    assertFalse(
        isHashed(
            workspace,
            "//main:TestJava",
            CanonicalCellName.rootCell().getName(),
            ImmutableList.of(""),
            ImmutableList.of()));
    assertTrue(
        isHashed(
            workspace,
            "//main:TestJava",
            CanonicalCellName.rootCell().getName(),
            ImmutableList.of(""),
            ImmutableList.of("main")));
  }

  @Test
  public void overridesDoNotApplyToNonRootCells() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "multiple_cell_build", tmp);
    workspace.setUp();
    assertTrue(
        isHashed(workspace, "java//subdir:gen", "java", ImmutableList.of(""), ImmutableList.of()));
  }

  @Test
  public void linkingWorksForAllGenruleOutputs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "genrules", tmp);
    workspace.setUp();
    workspace
        .runBuckBuild("-c", "project.buck_out_links_to_hashed_paths=hardlink", "//:outputs_map")
        .assertSuccess();
    assertTrue(Files.exists(workspace.getPath("buck-out/gen/outputs_map/default.txt")));
    assertTrue(Files.exists(workspace.getPath("buck-out/gen/outputs_map/out1.txt")));
    assertTrue(Files.exists(workspace.getPath("buck-out/gen/outputs_map/out2.txt")));
    workspace
        .runBuckBuild("-c", "project.buck_out_links_to_hashed_paths=hardlink", "//:output")
        .assertSuccess();
    assertTrue(Files.exists(workspace.getPath("buck-out/gen/output/out.txt")));
  }
}
