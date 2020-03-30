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

import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transforms error messages such that paths are correct.
 *
 * <p>When preprocessing/compiling, the compiler may be run in a manner where the emitted paths are
 * inaccurate, this stream transformer rewrite error output to give sensible paths to the user.
 */
class CxxErrorTransformer {

  private final ProjectFilesystem filesystem;
  private final boolean shouldAbsolutize;
  private final HeaderPathNormalizer pathNormalizer;

  /**
   * @param shouldAbsolutize whether to transform paths to absolute paths.
   * @param pathNormalizer Path replacements to rewrite symlinked C headers.
   */
  public CxxErrorTransformer(
      ProjectFilesystem filesystem, boolean shouldAbsolutize, HeaderPathNormalizer pathNormalizer) {
    this.filesystem = filesystem;
    this.shouldAbsolutize = shouldAbsolutize;
    this.pathNormalizer = pathNormalizer;
  }

  private static final String TERM_ESCS = "(?:\\u001B\\[[;\\d]*[mK])*";
  private static final ImmutableList<Pattern> PATH_PATTERNS =
      Platform.detect() == Platform.WINDOWS
          ? ImmutableList.of()
          : ImmutableList.of(
              Pattern.compile(
                  "(?<prefix>^(?:In file included |\\s+)from "
                      + TERM_ESCS
                      + ")"
                      + "(?<path>[^:]+)"
                      + "(?<suffix>(?:[:,]\\d+(?:[:,]\\d+)?)?"
                      + TERM_ESCS
                      + "[:,]$)"),
              Pattern.compile(
                  "(?<prefix>^"
                      + TERM_ESCS
                      + ")"
                      + "(?<path>[^:]+)"
                      + "(?<suffix>(?::\\d+(?::\\d+:)?)?:)"));

  @VisibleForTesting
  String transformLine(SourcePathResolverAdapter pathResolver, String line) {
    for (Pattern pattern : PATH_PATTERNS) {
      Matcher m = pattern.matcher(line);
      if (m.find()) {
        StringBuilder builder = new StringBuilder();
        String prefix = m.group("prefix");
        if (prefix != null) {
          builder.append(prefix);
        }
        builder.append(transformPath(pathResolver, m.group("path")));
        String suffix = m.group("suffix");
        if (suffix != null) {
          builder.append(suffix);
        }
        return m.replaceAll(Matcher.quoteReplacement(builder.toString()));
      }
    }
    return line;
  }

  private String transformPath(SourcePathResolverAdapter pathResolver, String original) {
    Path path = MorePaths.normalize(filesystem.resolve(original));

    // And, of course, we need to fixup any replacement paths.
    Optional<Path> normalizedPath =
        pathNormalizer.getAbsolutePathForUnnormalizedPath(pathResolver, path);
    if (normalizedPath.isPresent()) {
      path = normalizedPath.get();
    }

    if (!shouldAbsolutize) {
      path = filesystem.getPathRelativeToProjectRoot(path).orElse(path);
    }

    return Escaper.escapePathForCIncludeString(path);
  }
}
