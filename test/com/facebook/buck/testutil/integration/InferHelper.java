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

package com.facebook.buck.testutil.integration;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.type.TypeReference;
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
      Object testCase, Path workspaceRoot, String scenarioName) throws IOException {
    ProjectWorkspace projectWorkspace =
        TestDataHelper.createProjectWorkspaceForScenario(testCase, scenarioName, workspaceRoot);
    projectWorkspace.setUp();
    return projectWorkspace;
  }

  public static ProjectWorkspace setupCxxInferWorkspace(
      Object testCase, TemporaryPaths temporaryFolder, Optional<String> rawBlacklistRegex)
      throws IOException {
    return setupCxxInferWorkspace(
        testCase, temporaryFolder.getRoot(), rawBlacklistRegex, "infertest", Optional.empty());
  }

  public static ProjectWorkspace setupCxxInferWorkspace(
      Object testCase,
      Path temporaryFolder,
      Optional<String> rawBlacklistRegex,
      String scenarioName,
      Optional<Path> fakeInferRootPathOpt)
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(testCase, scenarioName, temporaryFolder);

    Path fakeInferRootPath = fakeInferRootPathOpt.orElse(workspace.getPath("fake-infer"));

    Path inferBin = fakeInferRootPath.resolve("fake-bin");
    Path facebookClangPluginsRoot = fakeInferRootPath.resolve("fake-clang");

    // create .buckconfig with the right path to the tools
    workspace.setUp();

    workspace.writeContentsToPath(
        new InferConfigGenerator(inferBin, facebookClangPluginsRoot, rawBlacklistRegex)
            .toBuckConfigLines(),
        ".buckconfig");

    return workspace;
  }

  public static String[] getCxxCLIConfigurationArgs(
      Path fakeInferRootPath, Optional<String> rawBlacklistRegex, BuildTarget buildTarget) {
    Path inferBin = fakeInferRootPath.resolve("fake-bin");
    Path facebookClangPluginRoot = fakeInferRootPath.resolve("fake-clang");
    return new InferConfigGenerator(inferBin, facebookClangPluginRoot, rawBlacklistRegex)
        .toCrossCellCLIArgs(buildTarget);
  }

  private static class InferConfigGenerator {

    private Path inferBin;
    private Path clangCompiler;
    private Path clangPlugin;
    private Optional<String> rawBlacklistRegex;

    public InferConfigGenerator(
        Path inferBin, Path facebookClangPluginRoot, Optional<String> rawBlacklistRegex) {
      this.inferBin = inferBin;
      this.clangCompiler = facebookClangPluginRoot.resolve("fake-clang");
      this.clangPlugin = facebookClangPluginRoot.resolve("fake-plugin");
      this.rawBlacklistRegex = rawBlacklistRegex;
    }

    public String[] toCrossCellCLIArgs(BuildTarget buildTarget) {
      ImmutableList<String> baseConfig =
          ImmutableList.of(
              buildTarget.getFullyQualifiedName(),
              "--config",
              "*//infer.infer_bin=" + inferBin,
              "--config",
              "*//infer.clang_compiler=" + clangCompiler,
              "--config",
              "*//infer.clang_plugin=" + clangPlugin,
              "--config",
              "build.depfiles=cache");

      ImmutableList<String> blacklistRegex = ImmutableList.of();
      if (rawBlacklistRegex.isPresent()) {
        blacklistRegex =
            ImmutableList.of("--config", "*//infer.blacklist_regex=" + rawBlacklistRegex.get());
      }

      return ImmutableList.builder()
          .addAll(baseConfig)
          .addAll(blacklistRegex)
          .build()
          .toArray(new String[baseConfig.size() + blacklistRegex.size()]);
    }

    public String toBuckConfigLines() {
      String blacklistRegexConfig = "";
      if (rawBlacklistRegex.isPresent()) {
        blacklistRegexConfig = "blacklist_regex = " + rawBlacklistRegex.get() + "\n";
      }

      return String.format(
          "[infer]\n"
              + "infer_bin = %s\n"
              + "clang_compiler = %s\n"
              + "clang_plugin = %s\n"
              + "%s\n"
              + "[build]\n"
              + "depfiles = cache",
          inferBin.toString(), clangCompiler, clangPlugin, blacklistRegexConfig);
    }
  }
}
