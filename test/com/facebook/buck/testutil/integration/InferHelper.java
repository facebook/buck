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


import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class InferHelper {

  private InferHelper() {}

  public static List<Object>
  loadInferReport(ProjectWorkspace workspace, String jsonReport) throws IOException {
    String reportContent = workspace.getFileContents(jsonReport);
    Object[] records = new GsonBuilder().create().fromJson(reportContent, Object[].class);
    return Arrays.asList(records);
  }

  public static ProjectWorkspace setupCxxInferWorkspace(
      Object testCase,
      DebuggableTemporaryFolder temporaryFolder) throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        testCase, "infertest", temporaryFolder);
    workspace.setUp();

    Path inferBin = workspace.getPath("fake-infer").resolve("fake-bin");
    Path facebookClangPluginsRoot = workspace.getPath("fake-infer").resolve("fake-clang");

    // create .buckconfig with the right path to the tools
    workspace.setUp();
    workspace.writeContentsToPath(
        String.format(
            "[infer]\n" +
                "infer_bin = %s\n" +
                "clang_compiler = %s\n" +
                "clang_plugin = %s\n",
            inferBin.toString(),
            facebookClangPluginsRoot.resolve("fake-clang"),
            facebookClangPluginsRoot.resolve("fake-plugin")),
        ".buckconfig");

    return workspace;
  }
}
