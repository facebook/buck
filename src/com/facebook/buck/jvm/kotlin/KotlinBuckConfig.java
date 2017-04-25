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

package com.facebook.buck.jvm.kotlin;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.model.Either;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import javax.annotation.Nullable;

public class KotlinBuckConfig {
  private static final String SECTION = "kotlin";

  private static final Path DEFAULT_KOTLIN_COMPILER = Paths.get("kotlinc");

  private final BuckConfig delegate;
  private @Nullable Path kotlinHome;

  public KotlinBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  /**
   * Get the Tool instance for the Kotlin compiler.
   *
   * @return the Kotlin compiler Tool
   */
  public Supplier<Tool> getKotlinCompiler() {
    Path compilerPath = getKotlinHome().resolve("kotlinc");
    if (!Files.isExecutable(compilerPath)) {
      compilerPath = getKotlinHome().resolve(Paths.get("bin", "kotlinc"));
      if (!Files.isExecutable(compilerPath)) {
        throw new HumanReadableException("Could not resolve kotlinc location.");
      }
    }

    Path compiler = new ExecutableFinder().getExecutable(compilerPath, delegate.getEnvironment());
    return Suppliers.ofInstance(new HashedFileTool(compiler));
  }

  /**
   * Get the path to the Kotlin runtime jar.
   *
   * @return the Kotlin runtime jar path
   */
  public Either<SourcePath, Path> getPathToRuntimeJar() {
    Optional<String> value = delegate.getValue(SECTION, "runtime_jar");

    if (value.isPresent()) {
      boolean isAbsolute = Paths.get(value.get()).isAbsolute();
      if (isAbsolute) {
        return Either.ofRight(delegate.getPath(SECTION, "runtime_jar", false).get().normalize());
      } else {
        return Either.ofLeft(delegate.getSourcePath(SECTION, "runtime_jar").get());
      }
    }

    Path runtime = getKotlinHome().resolve("kotlin-runtime.jar");
    if (Files.isRegularFile(runtime)) {
      return Either.ofRight(runtime.toAbsolutePath().normalize());
    }

    runtime = getKotlinHome().resolve(Paths.get("lib", "kotlin-runtime.jar"));
    if (Files.isRegularFile(runtime)) {
      return Either.ofRight(runtime.toAbsolutePath().normalize());
    }

    throw new HumanReadableException("Could not resolve kotlin runtime JAR location.");
  }

  private Path getKotlinHome() {
    if (kotlinHome != null) {
      return kotlinHome;
    }

    try {
      // Check the buck configuration for a specified kotlin compliler
      Optional<String> value = delegate.getValue(SECTION, "compiler");
      boolean isAbsolute = (value.isPresent() && Paths.get(value.get()).isAbsolute());
      Optional<Path> compilerPath = delegate.getPath(SECTION, "compiler", !isAbsolute);
      if (compilerPath.isPresent()) {
        if (Files.isExecutable(compilerPath.get())) {
          kotlinHome = compilerPath.get().toRealPath().getParent().normalize();
          if (kotlinHome != null && kotlinHome.endsWith(Paths.get("bin"))) {
            kotlinHome = kotlinHome.getParent().normalize();
          }
          return kotlinHome;
        } else {
          throw new HumanReadableException(
              "Could not deduce kotlin home directory from path " + compilerPath.toString());
        }
      } else {
        // If the KOTLIN_HOME environment variable is specified we trust it
        String home = delegate.getEnvironment().get("KOTLIN_HOME");
        if (home != null) {
          kotlinHome = Paths.get(home).normalize();
          return kotlinHome;
        } else {
          // Lastly, we try to resolve from the system PATH
          Optional<Path> compiler =
              new ExecutableFinder()
                  .getOptionalExecutable(DEFAULT_KOTLIN_COMPILER, delegate.getEnvironment());
          if (compiler.isPresent()) {
            kotlinHome = compiler.get().toRealPath().getParent().normalize();
            if (kotlinHome != null && kotlinHome.endsWith(Paths.get("bin"))) {
              kotlinHome = kotlinHome.getParent().normalize();
            }
            return kotlinHome;
          } else {
            throw new HumanReadableException(
                "Could not resolve kotlin home directory, Consider setting KOTLIN_HOME.");
          }
        }
      }
    } catch (IOException io) {
      throw new HumanReadableException(
          "Could not resolve kotlin home directory, Consider setting KOTLIN_HOME.", io);
    }
  }
}
