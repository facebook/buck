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

package com.facebook.buck.jvm.java;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.Escaper;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Optional;

public interface Javac extends RuleKeyAppendable, Tool {

  /**
   * An escaper for arguments written to @argfiles.
   */
  Function<String, String> ARGFILES_ESCAPER = Escaper.javacEscaper();
  String SRC_ZIP = ".src.zip";
  String SRC_JAR = "-sources.jar";

  JavacVersion getVersion();

  int buildWithClasspath(
      JavacExecutionContext context,
      BuildTarget invokingRule,
      ImmutableList<String> options,
      ImmutableSet<String> safeAnnotationProcessors,
      ImmutableSortedSet<Path> javaSourceFilePaths,
      Path pathToSrcsList,
      Optional<Path> workingDirectory,
      JavacOptions.AbiGenerationMode abiGenerationMode) throws InterruptedException;

  String getDescription(
      ImmutableList<String> options,
      ImmutableSortedSet<Path> javaSourceFilePaths,
      Path pathToSrcsList);

  String getShortName();

}
