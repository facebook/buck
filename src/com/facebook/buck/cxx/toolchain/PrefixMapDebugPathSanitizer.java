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
package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * This sanitizer works by depending on the compiler's -fdebug-prefix-map flag to properly ensure
 * that the output only contains references to the mapped-to paths (i.e. the fake paths).
 */
public class PrefixMapDebugPathSanitizer extends DebugPathSanitizer {

  private final String fakeCompilationDirectory;
  private final ImmutableBiMap<Path, String> other;

  public PrefixMapDebugPathSanitizer(
      String fakeCompilationDirectory, ImmutableBiMap<Path, String> other) {
    this.fakeCompilationDirectory = fakeCompilationDirectory;
    this.other = other;
  }

  @Override
  public String getCompilationDirectory() {
    return fakeCompilationDirectory;
  }

  @Override
  public ImmutableMap<String, String> getCompilationEnvironment(
      Path workingDir, boolean shouldSanitize) {
    return ImmutableMap.of("PWD", workingDir.toString());
  }

  @Override
  public void restoreCompilationDirectory(Path path, Path workingDir) throws IOException {
    // There should be nothing to sanitize in the compilation directory because the compilation
    // flags took care of it.
  }

  @Override
  public ImmutableList<String> getCompilationFlags(
      Compiler compiler, Path workingDir, ImmutableMap<Path, Path> prefixMap) {
    if (compiler instanceof WindowsCompiler) {
      return ImmutableList.of();
    }

    ImmutableList.Builder<String> flags = ImmutableList.builder();

    // As these replacements are processed one at a time, if one is a prefix (or actually is just
    // contained in) another, it must be processed after that other one. To ensure that we can
    // process them in the correct order, they are inserted into allPaths in order of length
    // (shortest first) so that prefixes will be handled correctly.
    RichStream.<Map.Entry<Path, String>>empty()
        // GCC has a bug (https://gcc.gnu.org/bugzilla/show_bug.cgi?id=71850) where it won't
        // properly pass arguments down to subprograms using argsfiles, which can make it prone to
        // argument list too long errors, so avoid adding `-fdebug-prefix-map` flags for each
        // `prefixMap` entry.
        .concat(
            compiler instanceof GccCompiler
                ? Stream.empty()
                : prefixMap
                    .entrySet()
                    .stream()
                    .map(e -> new AbstractMap.SimpleEntry<>(e.getKey(), e.getValue().toString())))
        .concat(RichStream.from(getAllPaths(Optional.of(workingDir))))
        .sorted(Comparator.comparingInt(entry -> entry.getKey().toString().length()))
        .map(p -> getDebugPrefixMapFlag(p.getKey(), p.getValue()))
        .forEach(flags::add);

    if (compiler instanceof GccCompiler) {
      // If we recorded switches in the debug info, the -fdebug-prefix-map values would contain the
      // unsanitized paths.
      flags.add("-gno-record-gcc-switches");
    }

    return flags.build();
  }

  private String getDebugPrefixMapFlag(Path realPath, String fakePath) {
    String realPathStr = realPath.toString();
    // If we're replacing the real path with an empty fake path, then also remove the trailing `/`
    // to prevent forming an absolute path.
    if (fakePath.isEmpty()) {
      realPathStr += "/";
    }
    return String.format("-fdebug-prefix-map=%s=%s", realPathStr, fakePath);
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
