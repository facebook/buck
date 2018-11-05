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

package com.facebook.buck.android;

import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Generates a list of strings.xml files
 *
 * <p>The ordering of strings files is consistent with the order of the input resource directories
 */
public class GetStringsFilesStep implements Step {
  @VisibleForTesting
  static final Pattern STRINGS_FILE_PATH =
      Pattern.compile("(\\b|.*/)res/values(-.+)*/strings.xml", Pattern.CASE_INSENSITIVE);

  private final ProjectFilesystem filesystem;
  private final ImmutableList<Path> resDirs;
  private final ImmutableList.Builder<Path> stringFilesBuilder;

  /** @param resDirs list of {@code res} directories to find strings.xml files from */
  GetStringsFilesStep(
      ProjectFilesystem filesystem,
      ImmutableList<Path> resDirs,
      ImmutableList.Builder<Path> stringFilesBuilder) {
    this.filesystem = filesystem;
    this.resDirs = resDirs;
    this.stringFilesBuilder = stringFilesBuilder;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {
    Predicate<Path> filter =
        pathRelativeToProjectRoot -> {
          String filePath = MorePaths.pathWithUnixSeparators(pathRelativeToProjectRoot);
          return STRINGS_FILE_PATH.matcher(filePath).matches();
        };

    for (Path resDir : resDirs) {
      stringFilesBuilder.addAll(filesystem.getFilesUnderPath(resDir, filter));
    }
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "get_strings_files ";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return getShortName();
  }
}
