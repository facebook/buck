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

package com.facebook.buck.java;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.Escaper;
import com.google.common.base.CharMatcher;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

public interface Javac extends RuleKeyAppendable {

  /**
   * An escaper for arguments written to @argfiles.
   */
  Function<String, String> ARGFILES_ESCAPER =
      Escaper.escaper(
          Escaper.Quoter.DOUBLE,
          CharMatcher.anyOf("#\"'").or(CharMatcher.WHITESPACE));
  String SRC_ZIP = ".src.zip";

  JavacVersion getVersion();

  int buildWithClasspath(
      ExecutionContext context,
      BuildTarget invokingRule,
      ImmutableList<String> options,
      ImmutableSet<Path> javaSourceFilePaths,
      Optional<Path> pathToSrcsList,
      Optional<Path> workingDirectory) throws InterruptedException;

  String getDescription(
      ExecutionContext context,
      ImmutableList<String> options,
      ImmutableSet<Path> javaSourceFilePaths,
      Optional<Path> pathToSrcsList);

  String getShortName();

  boolean isUsingWorkspace();

}
