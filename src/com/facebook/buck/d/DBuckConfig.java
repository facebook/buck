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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class DBuckConfig {
  private static final Path DEFAULT_D_COMPILER = Paths.get("dmd");
  private static final Logger LOG = Logger.get(DBuckConfig.class);

  private final BuckConfig delegate;

  public DBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  /**
   * @return a Tool representing the D compiler to be used.
   */
  Tool getDCompiler() {
    return new HashedFileTool(getDCompilerPath());
  }

  /**
   * @return a list of flags that must be passed to the linker to link D binaries
   */
  ImmutableList<String> getLinkerFlagsForBinary() {
    Optional<ImmutableList<String>> configuredFlags =
        delegate.getOptionalListWithoutComments("d", "linker_flags_for_binary");
    if (configuredFlags.isPresent()) {
      return configuredFlags.get();
    } else {
      ImmutableList.Builder<String> builder = ImmutableList.builder();
      builder.addAll(
          FluentIterable
            .from(getBaseLibraryPaths())
            .transform(
                new Function<Path, String>() {
                  @Override
                  public String apply(Path input) {
                    return "-L" + input;
                  }
                }));
      builder.add("-lphobos2", "-lpthread", "-lm");
      return builder.build();
    }
  }

  /**
   * @return a list of paths to be searched for libraries, in addition to paths that may
   *   be introduced by rules.
   */
  private Iterable<Path> getBaseLibraryPaths() {
    Optional<ImmutableList<String>> configuredPaths =
        delegate.getOptionalListWithoutComments("d", "library_path");

    if (configuredPaths.isPresent()) {
      return FluentIterable
          .from(configuredPaths.get())
          .transform(
              new Function<String, Path>() {
                @Override
                public Path apply(String input) {
                  return Paths.get(input);
                }
              });
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
      LOG.debug("Could not resolve " + compilerPath +
              " to real path (likely cause: it does not exist)");
    }
    Path usrLib = Paths.get("/usr", "lib");
    Path usrLocalLib = Paths.get("/usr", "local", "lib");
    String platformName = delegate.getArchitecture().toString() + "-" +
        delegate.getPlatform().getAutoconfName();
    String platformNameGnu = platformName + "-gnu";
    for (Path libDir : ImmutableList.of(
        compilerPath.getParent().resolve(Paths.get("..", "lib")).normalize(),
        usrLocalLib,
        usrLib,
        usrLocalLib.resolve(platformName),
        usrLocalLib.resolve(platformNameGnu),
        usrLib.resolve(platformName),
        usrLib.resolve(platformNameGnu))) {
      for (String libName : ImmutableList.of("libphobos2.a", "libphobos2.so")) {
        Path phobosPath = libDir.resolve(libName);
        if (Files.exists(phobosPath)) {
          LOG.debug("Detected path to Phobos: " + phobosPath);
          return ImmutableList.of(libDir);
        }
      }
    }

    throw new HumanReadableException(
        "D standard library not found, and not configured using d.library_path");
  }

  /**
   * @return the Path to the D compiler.
   */
  private Path getDCompilerPath() {
    Path compilerPath = delegate.getPath("d", "compiler").or(DEFAULT_D_COMPILER);

    return new ExecutableFinder().getExecutable(compilerPath, delegate.getEnvironment());
  }
}
