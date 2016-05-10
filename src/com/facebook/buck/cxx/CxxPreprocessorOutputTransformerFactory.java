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
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class CxxPreprocessorOutputTransformerFactory {

  private final Path workingDir;
  private final HeaderPathNormalizer pathNormalizer;
  private final DebugPathSanitizer sanitizer;

  public CxxPreprocessorOutputTransformerFactory(
      Path workingDir,
      HeaderPathNormalizer pathNormalizer,
      DebugPathSanitizer sanitizer) {
    this.workingDir = workingDir;
    this.pathNormalizer = pathNormalizer;
    this.sanitizer = sanitizer;
  }

  public LineProcessorRunnable createTransformerThread(
      ExecutionContext context,
      InputStream inputStream,
      OutputStream outputStream) {
    return new LineProcessorRunnable(
        context.getExecutorService(ExecutionContext.ExecutorPool.CPU),
        inputStream,
        outputStream) {
      @Override
      public String process(String line) {
        return transformLine(line);
      }
    };
  }

  // N.B. These include paths are special to GCC. They aren't real files and there is no remapping
  // needed, so we can just ignore them everywhere.
  private static final ImmutableSet<String> SPECIAL_INCLUDE_PATHS = ImmutableSet.of(
      "<built-in>",
      "<command-line>"
  );

  private static final Pattern LINE_MARKERS =
      Pattern.compile("^# (?<num>\\d+) \"(?<path>[^\"]+)\"(?<rest>.*)?$");

  @VisibleForTesting
  String transformLine(String line) {
    if (line.startsWith("# ")) {
      return transformPreprocessorLine(line);
    } else {
      return line;
    }
  }

  private String transformPreprocessorLine(String line) {
    Matcher m = LINE_MARKERS.matcher(line);

    if (m.find() && !SPECIAL_INCLUDE_PATHS.contains(m.group("path"))) {
      String originalPath = m.group("path");
      String replacementPath = originalPath;

      Optional<Path> normalizedPath =
          pathNormalizer.getRelativePathForUnnormalizedPath(Paths.get(replacementPath));
      if (normalizedPath.isPresent()) {
        replacementPath = Escaper.escapePathForCIncludeString(normalizedPath.get());
      }

      replacementPath = sanitizer.sanitize(Optional.of(workingDir), replacementPath);

      if (!originalPath.equals(replacementPath)) {
        String num = m.group("num");
        String rest = m.group("rest");
        return "# " + num + " \"" + replacementPath + "\"" + rest;
      }
    }

    return line;
  }
}
