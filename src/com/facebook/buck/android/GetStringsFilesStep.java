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

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Generates a list of strings.xml files (excluding files in the whitelisted directories)
 *
 * The ordering of strings files is consistent with the order of the input resource directories
 */
public class GetStringsFilesStep implements Step {
  @VisibleForTesting
  static final Pattern STRINGS_FILE_PATH = Pattern.compile(
      "(\\b|.*/)res/values(-.+)*/strings.xml", Pattern.CASE_INSENSITIVE);

  private final ImmutableList<Path> resDirs;
  private final ImmutableList.Builder<Path> stringFilesBuilder;
  private final ImmutableSet<Path> whitelistedStringDirs;

  /**
   * @param resDirs list of {@code res} directories to find strings.xml files from
   * @param whitelistedStringDirs set of directories that will be filtered out
   */
  GetStringsFilesStep(
      ImmutableList<Path> resDirs,
      ImmutableList.Builder<Path> stringFilesBuilder,
      ImmutableSet<Path> whitelistedStringDirs) {
    this.resDirs = resDirs;
    this.stringFilesBuilder = stringFilesBuilder;
    this.whitelistedStringDirs = whitelistedStringDirs;
  }

  @Override
  public int execute(ExecutionContext context) {
    try {
      ProjectFilesystem filesystem = context.getProjectFilesystem();
      Predicate<Path> filter = new Predicate<Path>() {
        @Override
        public boolean apply(Path pathRelativeToProjectRoot) {
          String filePath = MorePaths.pathWithUnixSeparators(pathRelativeToProjectRoot);
          return STRINGS_FILE_PATH.matcher(filePath).matches();
        }
      };

      for (Path resDir : resDirs) {
        // TODO(user): Remove the need to check the whitelist
        if (!isPathWhitelisted(resDir)) {
          stringFilesBuilder.addAll(filesystem.getFilesUnderPath(resDir, filter));
        }
      }
      return 0;
    } catch (Exception e) {
      context.logError(e, "There was an error getting the list of string files.");
      return 1;
    }
  }

  private boolean isPathWhitelisted(Path pathRelativeToProjectRoot) {
    for (Path whitelistedStringDir : whitelistedStringDirs) {
      if (pathRelativeToProjectRoot.startsWith(whitelistedStringDir)) {
        return true;
      }
    }

    return false;
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
