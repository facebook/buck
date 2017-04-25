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

package com.facebook.buck.cxx;

import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.collect.ImmutableList;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

public interface Compiler extends Tool {

  ImmutableList<String> getFlagsForReproducibleBuild(
      String alternativeCompilationDir, Path currentCellPath);

  Optional<ImmutableList<String>> getFlagsForColorDiagnostics();

  ImmutableList<String> languageArgs(String language);

  boolean isArgFileSupported();

  boolean isDependencyFileSupported();

  ImmutableList<String> outputArgs(String outputPath);

  ImmutableList<String> outputDependenciesArgs(String outputPath);

  ImmutableList<String> getPicFlags();

  ImmutableList<String> getPdcFlags();

  boolean shouldSanitizeOutputBinary();

  InputStream getErrorStream(ProcessExecutor.LaunchedProcess compilerProcess);
}
