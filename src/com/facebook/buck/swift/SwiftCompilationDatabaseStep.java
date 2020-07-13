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

package com.facebook.buck.swift;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.cxx.CxxCompilationDatabaseEntry;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes a Swift compilation command into a JSON file consisting of a list of serialized {@link
 * CxxCompilationDatabaseEntry}.
 */
public class SwiftCompilationDatabaseStep extends SwiftCompileStepBase {
  private final AbsPath outputPath;
  private final SourcePathResolverAdapter resolver;
  private final ImmutableSortedSet<SourcePath> srcs;

  SwiftCompilationDatabaseStep(
      AbsPath outputPath,
      AbsPath compilerCwd,
      ImmutableList<String> compilerCommandPrefix,
      ImmutableList<String> compilerCommandArguments,
      ProjectFilesystem filesystem,
      SourcePathResolverAdapter resolver,
      ImmutableSortedSet<SourcePath> srcs,
      boolean withDownwardApi) {
    super(
        compilerCwd, compilerCommandPrefix, compilerCommandArguments, filesystem, withDownwardApi);
    this.outputPath = outputPath;
    this.resolver = resolver;
    this.srcs = srcs;
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context)
      throws IOException, InterruptedException {
    ImmutableList<String> rawCommand = getRawCommand();

    try (OutputStream outputStream = filesystem.newFileOutputStream(outputPath.getPath())) {
      try (JsonGenerator jsonGen = ObjectMappers.createGenerator(outputStream)) {
        jsonGen.writeStartArray();
        jsonGen.writeObject(
            CxxCompilationDatabaseEntry.of(
                compilerCwd.getPath().toString(), computeFileValue(), rawCommand));
        jsonGen.writeEndArray();
      }
    }

    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "swift-compile-command";
  }

  private String computeFileValue() {
    ImmutableList<String> relativeSrcPaths =
        this.srcs.stream()
            .map(src -> resolver.getAbsolutePath(src))
            .map(absPath -> filesystem.relativize(absPath))
            .map(relPath -> relPath.toString())
            .sorted()
            .collect(ImmutableList.toImmutableList());
    // The compilation database has a "file" field which cannot accurately represent whole
    // module compilation model but various tools still depend on the presence of the field
    // which is usually used a key. So, provide a list of files separated by a special char.
    return Joiner.on("|").join(relativeSrcPaths);
  }
}
