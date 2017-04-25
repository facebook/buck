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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.infer.annotation.Assertions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * This sanitizer works by depending on the compiler's -fdebug-prefix-map flag to properly ensure
 * that the output only contains references to the mapped-to paths (i.e. the fake paths).
 */
public class PrefixMapDebugPathSanitizer extends DebugPathSanitizer {
  private static final Logger LOG = Logger.get(PrefixMapDebugPathSanitizer.class);
  protected final ImmutableBiMap<Path, Path> allPaths;
  private final ProjectFilesystem projectFilesystem;
  private boolean isGcc;
  private Path compilationDir;

  // Save so we can make a copy later
  private final ImmutableBiMap<Path, Path> other;
  private final CxxToolProvider.Type cxxType;

  public PrefixMapDebugPathSanitizer(
      int pathSize,
      char separator,
      Path fakeCompilationDirectory,
      ImmutableBiMap<Path, Path> other,
      Path realCompilationDirectory,
      CxxToolProvider.Type cxxType,
      ProjectFilesystem projectFilesystem) {
    super(separator, pathSize, fakeCompilationDirectory);
    this.projectFilesystem = projectFilesystem;
    this.isGcc = cxxType == CxxToolProvider.Type.GCC;
    this.compilationDir = realCompilationDirectory;
    // Save for later
    this.other = other;
    this.cxxType = cxxType;

    ImmutableBiMap.Builder<Path, Path> pathsBuilder = ImmutableBiMap.builder();
    // As these replacements are processed one at a time, if one is a prefix (or actually is just
    // contained in) another, it must be processed after that other one. To ensure that we can
    // process them in the correct order, they are inserted into allPaths in order of length
    // (longest first). Then, if they are processed in the order in allPaths, prefixes will be
    // handled correctly.
    pathsBuilder.putAll(
        FluentIterable.from(other.entrySet())
            .toSortedList(
                (left, right) ->
                    right.getKey().toString().length() - left.getKey().toString().length()));
    // We assume that nothing in other is a prefix of realCompilationDirectory (though the reverse
    // is fine).
    pathsBuilder.put(realCompilationDirectory, fakeCompilationDirectory);
    for (Path p : other.keySet()) {
      Assertions.assertCondition(!realCompilationDirectory.toString().contains(p.toString()));
    }

    this.allPaths = pathsBuilder.build();
  }

  @Override
  ImmutableMap<String, String> getCompilationEnvironment(Path workingDir, boolean shouldSanitize) {
    if (!workingDir.equals(compilationDir)) {
      throw new AssertionError(
          String.format(
              "Expected working dir (%s) to be same as compilation dir (%s)",
              workingDir, compilationDir));
    }
    return ImmutableMap.of("PWD", workingDir.toString());
  }

  @Override
  void restoreCompilationDirectory(Path path, Path workingDir) throws IOException {
    Assertions.assertCondition(workingDir.equals(compilationDir));
    // There should be nothing to sanitize in the compilation directory because the compilation
    // flags took care of it.
  }

  @Override
  ImmutableList<String> getCompilationFlags() {
    ImmutableList.Builder<String> flags = ImmutableList.builder();
    // Two -fdebug-prefix-map flags will be applied in the reverse order, so reverse allPaths.
    Iterable<Map.Entry<Path, Path>> iter = ImmutableList.copyOf(allPaths.entrySet()).reverse();
    for (Map.Entry<Path, Path> mappings : iter) {
      flags.add(getDebugPrefixMapFlag(mappings.getKey(), mappings.getValue()));
    }
    if (isGcc) {
      // If we recorded switches in the debug info, the -fdebug-prefix-map values would contain the
      // unsanitized paths.
      flags.add("-gno-record-gcc-switches");
    }
    return flags.build();
  }

  private String getDebugPrefixMapFlag(Path realPath, Path fakePath) {
    return String.format("-fdebug-prefix-map=%s=%s", realPath, fakePath);
  }

  @Override
  protected ImmutableBiMap<Path, Path> getAllPaths(Optional<Path> workingDir) {
    if (workingDir.isPresent()) {
      Assertions.assertCondition(workingDir.get().equals(compilationDir));
    }
    // We need to always sanitize the real workingDir because we add it directly into the flags.
    return allPaths;
  }

  @Override
  public DebugPathSanitizer withProjectFilesystem(ProjectFilesystem projectFilesystem) {
    if (this.projectFilesystem.equals(projectFilesystem)) {
      return this;
    }
    LOG.debug(
        "Creating a new PrefixMapDebugPathSanitizer with projectFilesystem %s",
        projectFilesystem.getRootPath());
    // TODO(mzlee): Do not create a new sanitizer every time
    return new PrefixMapDebugPathSanitizer(
        this.pathSize,
        this.separator,
        this.compilationDirectory,
        this.other,
        projectFilesystem.getRootPath(),
        this.cxxType,
        projectFilesystem);
  }
}
