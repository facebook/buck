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

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.ExecutableFinder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import javax.annotation.Nullable;

public class KotlinBuckConfig {
  private static final String SECTION = "kotlin";
  private static final String KOTLIN_HOME_CONFIG = "kotlin_home";

  private static final Path DEFAULT_KOTLIN_COMPILER = Paths.get("kotlinc");

  private final BuckConfig delegate;
  private @Nullable Kotlinc kotlinc;

  public KotlinBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  public Kotlinc getKotlinc() {
    if (kotlinc == null) {
      if (isExternalCompilation()) {
        kotlinc = new ExternalKotlinc(getPathToCompilerBinary());
      } else {
        Optional<SourcePath> kotlinHomeSourcePath =
            delegate.getSourcePath(SECTION, KOTLIN_HOME_CONFIG);
        if (kotlinHomeSourcePath.isPresent()) {
          kotlinc = new JarBackedReflectedKotlinc(kotlinHomeSourcePath.get());
        } else {
          throw new HumanReadableException(
              "kotlin_home needs to be set when not an external compilation");
        }
      }
    }
    return kotlinc;
  }

  public Optional<BuildTarget> getKotlinHomeTarget() {
    return delegate.getMaybeBuildTarget(SECTION, KOTLIN_HOME_CONFIG);
  }

  private Path getPathToCompilerBinary() {
    Path kotlinHome = getKotlinHome();
    Path compilerPath = kotlinHome.resolve("kotlinc");
    if (!Files.isExecutable(compilerPath)) {
      compilerPath = kotlinHome.resolve("bin").resolve("kotlinc");
      if (!Files.isExecutable(compilerPath)) {
        throw new HumanReadableException("Could not resolve kotlinc location.");
      }
    }

    return new ExecutableFinder().getExecutable(compilerPath, delegate.getEnvironment());
  }

  /**
   * Determine whether external Kotlin compilation is being forced. The default is internal
   * (in-process) execution, but this can be overridden in .buckconfig by setting the "external"
   * property to "true".
   *
   * @return true is external compilation is requested, false otherwise
   */
  private boolean isExternalCompilation() {
    Optional<Boolean> value = delegate.getBoolean(SECTION, "external");
    return value.orElse(false);
  }

  /**
   * Find the Kotlin home (installation) directory by searching in this order: <br>
   *
   * <ul>
   *   <li>If the "kotlin_home" directory is specified in .buckconfig then use it.
   *   <li>Check the environment for a KOTLIN_HOME variable, if defined then use it.
   *   <li>Resolve "kotlinc" with an ExecutableFinder, and if found then deduce the kotlin home
   *       directory from it.
   * </ul>
   *
   * @return the Kotlin home path
   */
  private Path getKotlinHome() {
    if (!isExternalCompilation()) {
      throw new HumanReadableException(
          "kotlinHome path can only be queried when it's an external compilation");
    }

    Path kotlinHome;

    try {
      Optional<String> value = delegate.getValue(SECTION, KOTLIN_HOME_CONFIG);
      if (value.isPresent()) {
        // try to get kotlin home path from kotlin_home buck config
        boolean isAbsolute = Paths.get(value.get()).isAbsolute();
        Optional<Path> homePath = delegate.getPath(SECTION, KOTLIN_HOME_CONFIG, !isAbsolute);

        if (homePath.isPresent() && Files.isDirectory(homePath.get())) {
          kotlinHome = homePath.get().toRealPath().normalize();
        } else {
          throw new HumanReadableException(
              "Kotlin home directory (" + homePath + ") specified in .buckconfig was not found.");
        }
      } else {
        // If the KOTLIN_HOME environment variable is specified we trust it
        String home = delegate.getEnvironment().get("KOTLIN_HOME");
        if (home != null) {
          kotlinHome = Paths.get(home).normalize();
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

    return kotlinHome;
  }
}
