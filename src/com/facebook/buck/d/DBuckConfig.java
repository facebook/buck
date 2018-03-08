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

package com.facebook.buck.d;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.file.FileFinder;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Architecture;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.stream.StreamSupport;

public class DBuckConfig {
  private static final Path DEFAULT_D_COMPILER = Paths.get("dmd");
  private static final Logger LOG = Logger.get(DBuckConfig.class);

  private final BuckConfig delegate;

  public DBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  /** @return a Tool representing the D compiler to be used. */
  Tool getDCompiler() {
    return new HashedFileTool(delegate.getPathSourcePath(getDCompilerPath()));
  }

  /** @return a list of flags that must be passed to the compiler. */
  ImmutableList<String> getBaseCompilerFlags() {
    // If flags are configured in buckconfig, return those.
    // Else, return an empty list (no flags), as that should normally work.
    return delegate
        .getOptionalListWithoutComments("d", "base_compiler_flags", ' ')
        .orElse(ImmutableList.of());
  }

  /** @return a list of flags that must be passed to the linker to link D binaries. */
  public ImmutableList<String> getLinkerFlags() {
    Optional<ImmutableList<String>> configuredFlags =
        delegate.getOptionalListWithoutComments("d", "linker_flags", ' ');
    if (configuredFlags.isPresent()) {
      return configuredFlags.get();
    } else {
      // No flags configured; generate them based on library paths.
      ImmutableList.Builder<String> builder = ImmutableList.builder();
      builder.addAll(
          StreamSupport.stream(getBaseLibraryPaths().spliterator(), false)
              .map(input -> "-L" + input)
              .iterator());
      builder.add("-lphobos2", "-lpthread", "-lm");
      return builder.build();
    }
  }

  public BuckConfig getDelegate() {
    return delegate;
  }

  /**
   * @return a list of paths to be searched for libraries, in addition to paths that may be
   *     introduced by rules.
   */
  private Iterable<Path> getBaseLibraryPaths() {
    Optional<ImmutableList<String>> configuredPaths =
        delegate.getOptionalListWithoutComments("d", "library_path", ':');

    if (configuredPaths.isPresent()) {
      return FluentIterable.from(configuredPaths.get()).transform(input -> Paths.get(input));
    }

    // No paths configured. Make an educated guess and return that.
    // We search, in order:
    // 1. a lib directory next to the directory where the compiler is
    // 2. /usr/local/lib
    // 3. /usr/lib
    // 4. /usr/local/lib/${arch}-${platform}
    // 5. /usr/lib/${arch}-${platform}
    // For the platform names, both versions with and without "-gnu" are tried.
    Path compilerPath = getDCompilerPath();
    try {
      compilerPath = compilerPath.toRealPath();
    } catch (IOException e) {
      LOG.debug(
          "Could not resolve " + compilerPath + " to real path (likely cause: it does not exist)");
    }

    Path usrLib = Paths.get("/usr", "lib");
    Path usrLocalLib = Paths.get("/usr", "local", "lib");
    Architecture architecture = delegate.getArchitecture();
    String platformName = architecture + "-" + delegate.getPlatform().getAutoconfName();
    String platformNameGnu = platformName + "-gnu";

    ImmutableSet<Path> searchPath =
        ImmutableSet.<Path>builder()
            .add(compilerPath.getParent().resolve(Paths.get("..", "lib")).normalize())
            .add(usrLocalLib)
            .add(usrLib)
            .add(usrLocalLib.resolve(platformName))
            .add(usrLocalLib.resolve(platformNameGnu))
            .add(usrLib.resolve(platformName))
            .add(usrLib.resolve(platformNameGnu))
            .build();

    ImmutableSet<String> fileNames =
        FileFinder.combine(ImmutableSet.of("lib"), "phobos2", ImmutableSet.of(".a", ".so"));

    Optional<Path> phobosPath =
        FileFinder.getOptionalFile(fileNames, searchPath, Files::isRegularFile);

    if (phobosPath.isPresent()) {
      LOG.debug("Detected path to Phobos: " + phobosPath.get());
    } else {
      throw new HumanReadableException("Phobos not found, and not configured using d.library_path");
    }

    return ImmutableList.of(phobosPath.get().getParent());
  }

  /** @return the Path to the D compiler. */
  private Path getDCompilerPath() {
    Path compilerPath =
        delegate.getPath("d", "compiler", /*isCellRootRelative=*/ false).orElse(DEFAULT_D_COMPILER);

    return new ExecutableFinder().getExecutable(compilerPath, delegate.getEnvironment());
  }
}
