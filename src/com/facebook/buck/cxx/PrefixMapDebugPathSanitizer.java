/*
 * Copyright 2016-present Facebook, Inc.
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

import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Optional;

/**
 * This sanitizer works by depending on the compiler's -fdebug-prefix-map flag to properly ensure
 * that the output only contains references to the mapped-to paths (i.e. the fake paths).
 */
public class PrefixMapDebugPathSanitizer extends DebugPathSanitizer {

  private final String fakeCompilationDirectory;
  private final boolean isGcc;
  private final CxxToolProvider.Type cxxType;

  private final ImmutableBiMap<Path, String> other;

  public PrefixMapDebugPathSanitizer(
      String fakeCompilationDirectory,
      ImmutableBiMap<Path, String> other,
      CxxToolProvider.Type cxxType) {
    this.fakeCompilationDirectory = fakeCompilationDirectory;
    this.isGcc = cxxType == CxxToolProvider.Type.GCC;
    this.cxxType = cxxType;

    ImmutableBiMap.Builder<Path, String> pathsBuilder = ImmutableBiMap.builder();
    // As these replacements are processed one at a time, if one is a prefix (or actually is just
    // contained in) another, it must be processed after that other one. To ensure that we can
    // process them in the correct order, they are inserted into allPaths in order of length
    // (longest first). Then, if they are processed in the order in allPaths, prefixes will be
    // handled correctly.
    other
        .entrySet()
        .stream()
        .sorted(
            (left, right) -> right.getKey().toString().length() - left.getKey().toString().length())
        .forEach(e -> pathsBuilder.put(e.getKey(), e.getValue()));
    this.other = pathsBuilder.build();
  }

  @Override
  public String getCompilationDirectory() {
    return fakeCompilationDirectory;
  }

  @Override
  ImmutableMap<String, String> getCompilationEnvironment(Path workingDir, boolean shouldSanitize) {
    return ImmutableMap.of("PWD", workingDir.toString());
  }

  @Override
  void restoreCompilationDirectory(Path path, Path workingDir) throws IOException {
    // There should be nothing to sanitize in the compilation directory because the compilation
    // flags took care of it.
  }

  @Override
  ImmutableList<String> getCompilationFlags(Path workingDir) {
    if (cxxType == CxxToolProvider.Type.WINDOWS) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<String> flags = ImmutableList.builder();
    // Two -fdebug-prefix-map flags will be applied in the reverse order, so reverse allPaths.
    Iterable<Map.Entry<Path, String>> iter =
        ImmutableList.copyOf(getAllPaths(Optional.of(workingDir))).reverse();
    for (Map.Entry<Path, String> mappings : iter) {
      flags.add(getDebugPrefixMapFlag(mappings.getKey(), mappings.getValue()));
    }
    if (isGcc) {
      // If we recorded switches in the debug info, the -fdebug-prefix-map values would contain the
      // unsanitized paths.
      flags.add("-gno-record-gcc-switches");
    }
    return flags.build();
  }

  private String getDebugPrefixMapFlag(Path realPath, String fakePath) {
    return String.format("-fdebug-prefix-map=%s=%s", realPath, fakePath);
  }

  @Override
  protected Iterable<Map.Entry<Path, String>> getAllPaths(Optional<Path> workingDir) {
    if (!workingDir.isPresent()) {
      return other.entrySet();
    }
    return Iterables.concat(
        other.entrySet(),
        ImmutableList.of(
            new AbstractMap.SimpleEntry<>(workingDir.get(), fakeCompilationDirectory)));
  }
}
