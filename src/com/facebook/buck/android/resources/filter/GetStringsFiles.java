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

package com.facebook.buck.android.resources.filter;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Generates a list of strings.xml files
 *
 * <p>The ordering of strings files is consistent with the order of the input resource directories
 */
public class GetStringsFiles {
  @VisibleForTesting
  public static final Pattern STRINGS_FILE_PATH =
      Pattern.compile("(\\b|.*/)res/values(-.+)*/strings.xml", Pattern.CASE_INSENSITIVE);

  public static ImmutableList<Path> getFiles(
      AbsPath root, DirectoryStream.Filter<? super Path> ignoreFilter, ImmutableList<Path> resDirs)
      throws IOException {
    Predicate<Path> isStringsFile =
        pathRelativeToProjectRoot -> {
          String filePath = PathFormatter.pathWithUnixSeparators(pathRelativeToProjectRoot);
          return STRINGS_FILE_PATH.matcher(filePath).matches();
        };

    ImmutableList.Builder<Path> stringFilesBuilder = ImmutableList.builder();
    for (Path resDir : resDirs) {
      stringFilesBuilder.addAll(
          ProjectFilesystemUtils.getFilesUnderPath(
              root,
              resDir,
              isStringsFile,
              ProjectFilesystemUtils.getDefaultVisitOptions(),
              ignoreFilter));
    }

    return stringFilesBuilder.build();
  }
}
