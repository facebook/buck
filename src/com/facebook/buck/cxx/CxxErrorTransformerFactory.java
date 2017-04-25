/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.LineProcessorRunnable;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.io.InputStream;
import java.io.OutputStream;
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
class CxxErrorTransformerFactory {

  private final ProjectFilesystem filesystem;
  private final boolean shouldAbsolutize;
  private final HeaderPathNormalizer pathNormalizer;

  /**
   * @param shouldAbsolutize whether to transform paths to absolute paths.
   * @param pathNormalizer Path replacements to rewrite symlinked C headers.
   */
  public CxxErrorTransformerFactory(
      ProjectFilesystem filesystem, boolean shouldAbsolutize, HeaderPathNormalizer pathNormalizer) {
    this.filesystem = filesystem;
    this.shouldAbsolutize = shouldAbsolutize;
    this.pathNormalizer = pathNormalizer;
  }

  /** Create a thread to process lines in the stream asynchronously. */
  public LineProcessorRunnable createTransformerThread(
      ExecutionContext context, InputStream inputStream, OutputStream outputStream) {
    return new LineProcessorRunnable(
        context.getExecutorService(ExecutorPool.CPU), inputStream, outputStream) {
      @Override
      public String process(String line) {
        return transformLine(line);
      }
    };
  }

  private static final ImmutableList<Pattern> PATH_PATTERNS =
      Platform.detect() == Platform.WINDOWS
          ? ImmutableList.of()
          : ImmutableList.of(
              Pattern.compile(
                  "(?<prefix>^(?:In file included |\\s+)from )"
                      + "(?<path>[^:]+)"
                      + "(?<suffix>[:,](?:\\d+[:,](?:\\d+[:,])?)?$)"),
              Pattern.compile(
                  "(?<prefix>^(?:\u001B\\[[;\\d]*m)?)"
                      + "(?<path>[^:]+)"
                      + "(?<suffix>:(?:\\d+:(?:\\d+:)?)?)"));

  @VisibleForTesting
  String transformLine(String line) {
    for (Pattern pattern : PATH_PATTERNS) {
      Matcher m = pattern.matcher(line);
      if (m.find()) {
        StringBuilder builder = new StringBuilder();
        String prefix = m.group("prefix");
        if (prefix != null) {
          builder.append(prefix);
        }
        builder.append(transformPath(m.group("path")));
        String suffix = m.group("suffix");
        if (suffix != null) {
          builder.append(suffix);
        }
        return m.replaceAll(Matcher.quoteReplacement(builder.toString()));
      }
    }
    return line;
  }

  private String transformPath(String original) {
    Path path = MorePaths.normalize(filesystem.resolve(original));

    // And, of course, we need to fixup any replacement paths.
    Optional<Path> normalizedPath = pathNormalizer.getAbsolutePathForUnnormalizedPath(path);
    if (normalizedPath.isPresent()) {
      path = normalizedPath.get();
    }

    if (!shouldAbsolutize) {
      path = filesystem.getPathRelativeToProjectRoot(path).orElse(path);
    }

    return Escaper.escapePathForCIncludeString(path);
  }
}
