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

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.LineProcessorRunnable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transforms error messages such that paths are correct.
 *
 * When preprocessing/compiling, the compiler may be run in a manner where the emitted paths are
 * inaccurate, this stream transformer rewrite error output to give sensible paths to the user.
 */
class CxxErrorTransformerFactory {

  private final Optional<Path> workingDir;
  private final Optional<Function<Path, Path>> absolutifier;
  private final ImmutableMap<Path, Path> replacementPaths;
  private final DebugPathSanitizer sanitizer;

  /**
   * @param workingDir If set, replace padded working dir with effective compilation directory.
   * @param absolutifier If set, run paths through the given function that absolutizes the path.
   * @param replacementPaths Path replacements to rewrite symlinked C headers.
   * @param sanitizer Used to perform workingDir to compdir translation.
   */
  public CxxErrorTransformerFactory(
      Optional<Path> workingDir,
      Optional<Function<Path, Path>> absolutifier,
      Map<Path, Path> replacementPaths,
      DebugPathSanitizer sanitizer) {
    this.workingDir = workingDir;
    this.absolutifier = absolutifier;
    this.replacementPaths = ImmutableMap.copyOf(replacementPaths);
    this.sanitizer = sanitizer;
  }

  /**
   * Create a thread to process lines in the stream asynchronously.
   */
  public LineProcessorRunnable createTransformerThread(
      ExecutionContext context,
      InputStream inputStream,
      OutputStream outputStream) {
    return new LineProcessorRunnable(context.getExecutorService(ExecutionContext.ExecutorPool.CPU),
        inputStream,
        outputStream) {
      @Override
      public Iterable<String> process(String line) {
        return ImmutableList.of(transformLine(line));
      }
    };
  }

  private static final ImmutableList<Pattern> PATH_PATTERNS =
      ImmutableList.of(
          Pattern.compile(
              "(?<prefix>^(?:In file included |\\s+)from )" +
              "(?<path>[^:]+)" +
              "(?<suffix>[:,](?:\\d+[:,](?:\\d+[:,])?)?$)"),
          Pattern.compile(
              "(?<prefix>^(?:\u001B\\[[;\\d]*m)?)" +
              "(?<path>[^:]+)" +
              "(?<suffix>:(?:\\d+:(?:\\d+:)?)?)"));

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
    Path path = Paths.get(
        workingDir.isPresent() ?
            sanitizer.restore(workingDir, original) :
            original);

    // And, of course, we need to fixup any replacement paths.
    String result = Optional
        .fromNullable(replacementPaths.get(path))
        .transform(Escaper.PATH_FOR_C_INCLUDE_STRING_ESCAPER)
        .or(Escaper.escapePathForCIncludeString(path));
    if (absolutifier.isPresent()) {
      result = Preconditions.checkNotNull(absolutifier.get().apply(Paths.get(result))).toString();
    }
    return result;
  }
}
