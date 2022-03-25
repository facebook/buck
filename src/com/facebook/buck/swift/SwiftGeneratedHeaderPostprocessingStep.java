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
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTreeWithModuleMap;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.ThrowingPrintWriter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A step that transforms a Swift generated Objective-C header to be compatible with Objective-C
 * compilation with modules disabled, by adding a #else block after the Swift compiler's `@import`
 * block, containing traditional textual-style imports that are equivalent to the full-module
 * `@import` that the Swift compiler uses.
 */
final class SwiftGeneratedHeaderPostprocessingStep implements Step {
  private final Path headerPathBeforePostprocessing;
  private final SourcePath headerPathAfterPostprocessing;
  private final ImmutableMap<String, HeaderSymlinkTreeWithModuleMap> moduleNameToSymlinkTrees;
  private final Map<String, List<String>> generatedHeaderPostprocessingSystemModuleToHeadersMap;
  private final SourcePathResolverAdapter sourcePathResolver;

  public SwiftGeneratedHeaderPostprocessingStep(
      Path headerPathBeforePostprocessing,
      SourcePath headerPathAfterPostprocessing,
      ImmutableMap<String, HeaderSymlinkTreeWithModuleMap> moduleNameToSymlinkTrees,
      Map<String, List<String>> generatedHeaderPostprocessingSystemModuleToHeadersMap,
      SourcePathResolverAdapter sourcePathResolver) {
    this.headerPathBeforePostprocessing = headerPathBeforePostprocessing;
    this.headerPathAfterPostprocessing = headerPathAfterPostprocessing;
    this.moduleNameToSymlinkTrees = moduleNameToSymlinkTrees;
    this.generatedHeaderPostprocessingSystemModuleToHeadersMap =
        generatedHeaderPostprocessingSystemModuleToHeadersMap;
    this.sourcePathResolver = sourcePathResolver;
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context) throws IOException {
    File before = headerPathBeforePostprocessing.toFile();
    Path after = sourcePathResolver.getAbsolutePath(headerPathAfterPostprocessing).getPath();
    try (BufferedReader reader = new BufferedReader(new FileReader(before));
        ThrowingPrintWriter writer = new ThrowingPrintWriter(Files.newOutputStream(after))) {
      // The Swift compiler's output is like this:
      //
      // #if __has_feature(modules)
      // #if __has_warning("-Watimport-in-framework-header")
      // #pragma clang diagnostic ignored "-Watimport-in-framework-header"
      // #endif
      // @import ModuleA;
      // @import ModuleB;
      // @import ModuleC;
      // #endif
      //
      // The implementation here balances being somewhat flexible to changes to the compiler's
      // output, unlikely though they may be, with avoiding adding too much complexity and getting
      // too close to implementing a full parser for Objective-C un-preprocessed header files.

      // When this is null, it means that we are still searching for the start of the conditional
      // @import block in the generated header.
      ImmutableList.Builder<String> modulesBuilder = null;

      // The Swift compiler emits an additional #if gate inside the conditional @import block, so
      // we need to track whether we're in a further nested conditional so that we know when the
      // main conditional block has ended.
      int ifLevel = 0;

      String line;
      while ((line = reader.readLine()) != null) {
        // When the modulesBuilder has not been set, we are still searching for the start of the
        // modules @import section.
        if (modulesBuilder == null) {
          if (line.equals("#if __has_feature(modules)")) {
            modulesBuilder = ImmutableList.builder();
            ifLevel = 1;
          }
        } else {
          if (line.startsWith("@import")) {
            // Splitting on:
            //   "@import ": to separate from the @import.
            //   Semicolon and period: to separate the main module name from submodules or EOL.
            // The module name will then be the first item.
            String module = line.split("(@import +)|[;.]")[1];
            modulesBuilder.add(module);
          } else if (line.startsWith("#if")) {
            // This allows us to handle the Clang diagnostic #if block that the compiler inserts
            // within the main #if block for modules.
            ifLevel++;
          } else if (line.startsWith("#endif")) {
            ifLevel--;
            if (ifLevel == 0) {
              // We only include the traditional textual imports when modules are disabled, so
              // that the behavior with modules enabled is identical to the behavior without
              // the postprocessing.
              writer.println("#else");
              writer.println("// This #else block was added by postprocessing code in Buck to add");
              writer.println("// compatibility with non-modules compilation in Objective-C. We");
              writer.println(
                  "// have added this postprocessing step to mitigate build performance");
              writer.println(
                  "// issues from the current implementation of modules in Buck. For more");
              writer.println("// details, see the implementation of this file in Buck in the file");
              writer.println("// SwiftGeneratedHeaderPostprocessingStep.java.");
              writer.println("#pragma clang diagnostic push");
              writer.println(
                  "#pragma clang diagnostic ignored \"-Wnonportable-system-include-path\"");
              for (String module : modulesBuilder.build()) {
                HeaderSymlinkTreeWithModuleMap symlinkTree = moduleNameToSymlinkTrees.get(module);
                if (symlinkTree == null) {
                  List<String> imports =
                      generatedHeaderPostprocessingSystemModuleToHeadersMap.get(module);
                  if (imports != null) {
                    for (String imp : imports) {
                      addImport(writer, imp);
                    }
                  } else {
                    // When we don't have an explicit override for the module, we use the module's
                    // name as an umbrella header. This is used for typical Apple frameworks like
                    // Foundation and UIKit.
                    addImport(writer, module, module + ".h");
                  }
                } else {
                  for (Path path : symlinkTree.getLinks().keySet()) {
                    addImport(writer, module, path.getFileName().toString());
                  }
                }
              }
              writer.println("#pragma clang diagnostic pop");
              modulesBuilder = null;
            }
          }
        }

        writer.println(line);
      }
    }

    return StepExecutionResults.SUCCESS;
  }

  private static void addImport(ThrowingPrintWriter writer, String module, String header)
      throws IOException {
    addImport(writer, String.format("%s/%s", module, header));
  }

  private static void addImport(ThrowingPrintWriter writer, String imp) throws IOException {
    writer.println(String.format(" #if __has_include(<%s>)", imp));
    writer.println(String.format("  #import <%s>", imp));
    writer.println(" #endif");
  }

  @Override
  public String getDescription(StepExecutionContext context) {
    return getShortName() + "-for-" + headerPathAfterPostprocessing.toString();
  }

  @Override
  public String getShortName() {
    return "swift-generated-header-postprocessing";
  }
}
