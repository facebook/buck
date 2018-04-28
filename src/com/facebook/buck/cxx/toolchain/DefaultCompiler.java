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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.toolchain.tool.DelegatingTool;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;

/** Default implementation of the Compiler interface. */
public abstract class DefaultCompiler extends DelegatingTool implements Compiler {
  public DefaultCompiler(Tool tool) {
    super(tool);
  }

  @Override
  public ImmutableList<String> getFlagsForReproducibleBuild(
      String altCompilationDir, Path currentCellPath) {
    return ImmutableList.of();
  }

  @Override
  public Optional<ImmutableList<String>> getFlagsForColorDiagnostics() {
    return Optional.empty();
  }

  @Override
  public boolean isArgFileSupported() {
    return true;
  }

  @Override
  public ImmutableList<String> outputArgs(String outputPath) {
    return ImmutableList.of("-o", outputPath);
  }

  @Override
  public ImmutableList<String> outputDependenciesArgs(String outputPath) {
    return ImmutableList.of("-MD", "-MF", outputPath);
  }

  @Override
  public ImmutableList<String> languageArgs(String inputLanguage) {
    return ImmutableList.of("-x", inputLanguage);
  }

  @Override
  public ImmutableList<String> getPdcFlags() {
    return ImmutableList.of();
  }

  @Override
  public ImmutableList<String> getPicFlags() {
    return ImmutableList.of("-fPIC");
  }

  @Override
  public DependencyTrackingMode getDependencyTrackingMode() {
    return DependencyTrackingMode.MAKEFILE;
  }

  @Override
  public boolean shouldSanitizeOutputBinary() {
    return true;
  }

  @Override
  public Optional<String> getStderr(ProcessExecutor.Result result) {
    return result.getStderr();
  }
}
