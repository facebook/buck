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

package com.facebook.buck.testutil.integration;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public class InferHelper {

  private InferHelper() {}

  public static List<Object> loadInferReport(ProjectWorkspace workspace, String jsonReport)
      throws IOException {
    String reportContent = workspace.getFileContents(jsonReport);
    return ObjectMappers.createParser(reportContent)
        .readValueAs(new TypeReference<List<Object>>() {});
  }

  public static ProjectWorkspace setupWorkspace(
      Object testCase, AbsPath workspaceRoot, String scenarioName) throws IOException {
    ProjectWorkspace projectWorkspace =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            testCase, scenarioName, workspaceRoot);
    projectWorkspace.setUp();
    return projectWorkspace;
  }

  public static ProjectWorkspace setupCxxInferWorkspace(
      Object testCase, TemporaryPaths temporaryFolder, Optional<String> rawBlockListRegex)
      throws IOException {
    return setupCxxInferWorkspace(
        testCase, temporaryFolder.getRoot(), rawBlockListRegex, "infertest", Optional.empty());
  }

  public static ProjectWorkspace setupCxxInferWorkspace(
      Object testCase,
      AbsPath temporaryFolder,
      Optional<String> rawBlockListRegex,
      String scenarioName,
      Optional<AbsPath> fakeInferRootPathOpt)
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            testCase, scenarioName, temporaryFolder);

    AbsPath fakeInferRootPath =
        fakeInferRootPathOpt.orElse(AbsPath.of(workspace.getPath("fake-infer")));

    AbsPath inferBin = fakeInferRootPath.resolve("fake-bin");

    // create .buckconfig with the right path to the tools
    workspace.setUp();

    workspace.writeContentsToPath(
        new InferConfigGenerator(inferBin.getPath(), rawBlockListRegex).toBuckConfigLines(),
        ".buckconfig");

    return workspace;
  }

  public static String[] getCxxCLIConfigurationArgs(
      Path fakeInferRootPath, Optional<String> rawBlockListRegex, BuildTarget buildTarget) {
    Path inferBin = fakeInferRootPath.resolve("fake-bin");
    return new InferConfigGenerator(inferBin, rawBlockListRegex).toCrossCellCLIArgs(buildTarget);
  }

  private static class InferConfigGenerator {

    private final Path inferBin;
    private final Optional<String> rawBlockListRegex;

    public InferConfigGenerator(Path inferBin, Optional<String> rawBlockListRegex) {
      this.inferBin = inferBin;
      this.rawBlockListRegex = rawBlockListRegex;
    }

    public String[] toCrossCellCLIArgs(BuildTarget buildTarget) {
      ImmutableList<String> baseConfig =
          ImmutableList.of(
              buildTarget.getFullyQualifiedName(),
              "--config",
              "*//infer.binary=" + inferBin + "/infer",
              "--config",
              "build.depfiles=cache");

      ImmutableList<String> blockListRegex = ImmutableList.of();
      if (rawBlockListRegex.isPresent()) {
        blockListRegex =
            ImmutableList.of("--config", "*//infer.block_list_regex=" + rawBlockListRegex.get());
      }

      return FluentIterable.concat(baseConfig, blockListRegex).toArray(String.class);
    }

    public String toBuckConfigLines() {
      String blockListRegexConfig = "";
      if (rawBlockListRegex.isPresent()) {
        blockListRegexConfig = "block_list_regex = " + rawBlockListRegex.get() + "\n";
      }

      return String.format(
          "[infer]\n" + "binary = %s/infer\n" + "%s\n" + "[build]\n" + "depfiles = cache",
          inferBin.toString(), blockListRegexConfig);
    }
  }
}
