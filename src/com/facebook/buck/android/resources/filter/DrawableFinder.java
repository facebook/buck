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
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.regex.Pattern;

public class DrawableFinder {

  private static final Pattern DRAWABLE_PATH_PATTERN =
      Pattern.compile(".*drawable.*/.*(png|jpg|jpeg|gif|webp|xml)", Pattern.CASE_INSENSITIVE);
  // Android doesn't scale these, so we don't need to scale or filter them either.
  private static final Pattern DRAWABLE_EXCLUDE_PATTERN =
      Pattern.compile(".*-nodpi.*", Pattern.CASE_INSENSITIVE);

  /** Utility class: do not instantiate. */
  private DrawableFinder() {}

  public static ImmutableSet<Path> findDrawables(Collection<Path> dirs, AbsPath projectRoot)
      throws IOException {
    ImmutableSet.Builder<Path> drawableBuilder = ImmutableSet.builder();
    for (Path dir : dirs) {
      ProjectFilesystemUtils.walkRelativeFileTree(
          projectRoot,
          dir,
          ProjectFilesystemUtils.getDefaultVisitOptions(),
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
              String unixPath = PathFormatter.pathWithUnixSeparators(path);
              if (DRAWABLE_PATH_PATTERN.matcher(unixPath).matches()
                  && !DRAWABLE_EXCLUDE_PATTERN.matcher(unixPath).matches()) {
                // The path is normalized so that the value can be matched against patterns.
                drawableBuilder.add(path);
              }
              return FileVisitResult.CONTINUE;
            }
          },
          ProjectFilesystemUtils.getEmptyIgnoreFilter());
    }
    return drawableBuilder.build();
  }
}
