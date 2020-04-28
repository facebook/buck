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

package com.facebook.buck.cxx;

import com.facebook.buck.cxx.toolchain.Compiler;
import com.facebook.buck.cxx.toolchain.DebugPathSanitizer;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;

/** Utility class for code to generate compilation commands. */
class CxxPreprocessAndCompileEnhancer {
  static ImmutableList<String> getCompilationCommandArguments(
      boolean allowColorsInDiagnostics,
      Compiler compiler,
      CxxPreprocessAndCompileStep.Operation operation,
      CxxSource.Type inputType,
      CxxPreprocessAndCompileStep.ToolCommand command,
      DebugPathSanitizer sanitizer,
      ProjectFilesystem filesystem,
      HeaderPathNormalizer headerPathNormalizer,
      Path output,
      Optional<Path> maybeDepFile,
      Path input) {
    boolean useUnixPathSeparator = compiler.getUseUnixPathSeparator();
    String inputLanguage =
        operation == CxxPreprocessAndCompileStep.Operation.GENERATE_PCH
            ? inputType.getPrecompiledHeaderLanguage().get()
            : inputType.getLanguage();
    return ImmutableList.<String>builder()
        .addAll(
            (allowColorsInDiagnostics
                    ? compiler.getFlagsForColorDiagnostics()
                    : Optional.<ImmutableList<String>>empty())
                .orElseGet(ImmutableList::of))
        .addAll(compiler.languageArgs(inputLanguage))
        .addAll(command.getArguments())
        .addAll(
            sanitizer.getCompilationFlags(
                compiler, filesystem.getRootPath().getPath(), headerPathNormalizer.getPrefixMap()))
        .addAll(
            compiler.outputArgs(
                useUnixPathSeparator
                    ? PathFormatter.pathWithUnixSeparators(output.toString())
                    : output.toString()))
        .add("-c")
        .addAll(
            maybeDepFile
                .map(depFile -> compiler.outputDependenciesArgs(depFile.toString()))
                .orElseGet(ImmutableList::of))
        .add(
            useUnixPathSeparator
                ? PathFormatter.pathWithUnixSeparators(input.toString())
                : input.toString())
        .build();
  }

  static ImmutableList<String> getCompilationCommand(
      boolean allowColorsInDiagnostics,
      Compiler compiler,
      CxxPreprocessAndCompileStep.Operation operation,
      CxxSource.Type inputType,
      CxxPreprocessAndCompileStep.ToolCommand command,
      DebugPathSanitizer sanitizer,
      ProjectFilesystem filesystem,
      HeaderPathNormalizer headerPathNormalizer,
      Path output,
      Optional<Path> maybeDepFile,
      Path input) {
    return ImmutableList.<String>builder()
        .addAll(command.getCommandPrefix())
        .addAll(
            getCompilationCommandArguments(
                allowColorsInDiagnostics,
                compiler,
                operation,
                inputType,
                command,
                sanitizer,
                filesystem,
                headerPathNormalizer,
                output,
                maybeDepFile,
                input))
        .build();
  }
}
