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


import static org.junit.Assume.assumeTrue;

import com.facebook.buck.io.ExecutableFinder;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class InferHelper {

  private InferHelper() {}

  public static class InferBug {
    private String type;
    private String file;
    private String procedure;

    public InferBug(String type, String file, String procedure) {
      this.type = type;
      this.file = file;
      this.procedure = procedure;
    }

    public String getType() {
      return type;
    }

    public String getFile() {
      return file;
    }

    public String getProcedure() {
      return procedure;
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof InferBug &&
          file.equals(((InferBug) obj).file) &&
          type.equals(((InferBug) obj).type) &&
          procedure.equals(((InferBug) obj).procedure);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(type, file);
    }

    @Override
    public String toString() {
      return "Type: " + type + ", File: " + file + ", Procedure: " + procedure;
    }
  }

  public static List<InferBug>
  loadInferReport(ProjectWorkspace workspace, String jsonReport) throws IOException {
    String reportContent = workspace.getFileContents(jsonReport);
    Type setType = new TypeToken<List<InferBug>>() {}.getType();
    return new GsonBuilder().create().fromJson(reportContent, setType);
  }

  public static Path assumeInferIsInstalled() {
    ExecutableFinder ef = new ExecutableFinder();
    Optional<Path> infer = ef.getOptionalExecutable(
        Paths.get("infer"),
        ImmutableMap.copyOf(System.getenv()));

    assumeTrue("Infer binaries not found.", infer.isPresent());

    return infer.get();
  }

  public static ProjectWorkspace setupCxxInferWorkspace(
      Object testCase,
      Path inferTopLevel,
      DebuggableTemporaryFolder temporaryFolder) throws IOException {
    Path inferRoot = inferTopLevel.getParent().getParent().getParent();
    Path inferBin = inferRoot.resolve(Paths.get("infer", "bin"));
    Path facebookClangPluginsRoot = inferRoot.getParent().resolve("facebook-clang-plugin");

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        testCase, "infertest", temporaryFolder);
    workspace.setUp();

    // create .buckconfig with the right path to the tools
    workspace.setUp();
    workspace.writeContentsToPath(
        String.format(
            "[infer]\n" +
                "infer_bin = %s\n" +
                "clang_compiler = %s\n" +
                "clang_plugin = %s\n",
            inferBin.toString(),
            facebookClangPluginsRoot.resolve(Paths.get("clang", "bin", "clang")),
            facebookClangPluginsRoot.resolve(
                Paths.get(
                    "libtooling",
                    "build",
                    "FacebookClangPlugin.dylib"))),
        ".buckconfig");

    return workspace;
  }
}
