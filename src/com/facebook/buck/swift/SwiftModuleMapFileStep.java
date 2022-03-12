/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.swift;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.swift.toolchain.ExplicitModuleOutput;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;

public class SwiftModuleMapFileStep implements Step {

  private static final Logger LOG =
      Logger.get(com.facebook.buck.swift.SwiftModuleMapFileStep.class);

  private final SourcePathResolverAdapter resolver;
  private final ProjectFilesystem filesystem;
  private final Path outputSwiftModuleMapPath;
  private final ImmutableSet<ExplicitModuleOutput> moduleDeps;

  public SwiftModuleMapFileStep(
      Path outputSwiftModuleMapPath,
      ImmutableSet<ExplicitModuleOutput> moduleDeps,
      SourcePathResolverAdapter resolver,
      ProjectFilesystem filesystem) {
    this.outputSwiftModuleMapPath = outputSwiftModuleMapPath;
    this.moduleDeps = moduleDeps;
    this.resolver = resolver;
    this.filesystem = filesystem;
  }

  @VisibleForTesting
  void writeFile(JsonGenerator jsonGen) throws IOException {
    ArrayList<Swiftmodule> swiftmodulesList = new ArrayList<>();
    for (var dep : moduleDeps) {
      if (dep.getIsSwiftmodule()) {
        Swiftmodule swiftmodule = new Swiftmodule();
        swiftmodule.moduleName = dep.getName();
        swiftmodule.modulePath = resolver.getIdeallyRelativePath(dep.getOutputPath()).toString();
        swiftmodule.isFramework = dep.getIsFramework();
        swiftmodulesList.add(swiftmodule);
      }
    }

    ObjectMapper objectMapper = new ObjectMapper();
    objectMapper.writeValue(jsonGen, swiftmodulesList);
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context)
      throws IOException, InterruptedException {
    try {

      if (!filesystem.exists(outputSwiftModuleMapPath)) {
        filesystem.createParentDirs(outputSwiftModuleMapPath);
      }

      OutputStream outputStream = filesystem.newFileOutputStream(outputSwiftModuleMapPath);
      try (JsonGenerator jsonGen = ObjectMappers.createGenerator(outputStream)) {
        writeFile(jsonGen);
      }

      return StepExecutionResults.SUCCESS;
    } catch (IOException e) {
      LOG.error(
          "Error writing swift_module_map to %s:\n%s", outputSwiftModuleMapPath, e.getMessage());
      return StepExecutionResults.ERROR;
    }
  }

  @Override
  public String getShortName() {
    return "swift_module_map";
  }

  @Override
  public String getDescription(StepExecutionContext context) {
    return getShortName() + " @ " + outputSwiftModuleMapPath;
  }

  @Override
  public String toString() {
    return String.format("SwiftModuleMap %s", outputSwiftModuleMapPath.toString());
  }

  private static class Swiftmodule {

    String moduleName;

    String modulePath;

    boolean isFramework;

    @SuppressWarnings("unused")
    public String getModuleName() {
      return moduleName;
    }

    @SuppressWarnings("unused")
    public String getModulePath() {
      return modulePath;
    }

    @SuppressWarnings("unused")
    public boolean getIsFramework() {
      return isFramework;
    }
  }
}
