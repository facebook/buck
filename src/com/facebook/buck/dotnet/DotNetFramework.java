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

package com.facebook.buck.dotnet;

import static java.util.Locale.US;

import com.facebook.buck.log.Logger;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Represents an instance of the .Net framework. Two instances are assumed to be equal iff they
 * represent the same {@link FrameworkVersion} (notably, where that framework is installed is
 * irrelevant).
 */
public class DotNetFramework {

  private static final Logger LOG = Logger.get(DotNetFramework.class);

  private static final ImmutableSet<String> PROGRAM_FILES_ENV_NAMES =
      ImmutableSet.of("programfiles(x86)", "programfiles");

  private final FrameworkVersion version;
  private final Path frameworkDir;

  private DotNetFramework(FrameworkVersion version, Path frameworkDir) {
    this.version = version;
    this.frameworkDir = frameworkDir;
  }

  public static DotNetFramework resolveFramework(
      ImmutableMap<String, String> env,
      FrameworkVersion version) {
    return resolveFramework(FileSystems.getDefault(), env, version);
  }

  public Path findReferenceAssembly(String dllName) {
    Path toReturn = frameworkDir.resolve(dllName);

    if (!Files.exists(toReturn)) {
      throw new HumanReadableException(
          "Unable to find dll in framework version %s under %s: %s",
          version,
          frameworkDir,
          dllName);
    }

    return toReturn;
  }

  @Override
  public int hashCode() {
    return version.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }

    if (!(obj instanceof DotNetFramework)) {
      return false;
    }

    return version.equals(((DotNetFramework) obj).version);
  }

  // TODO(user): Use official Win32 APIs to find the framework
  @VisibleForTesting
  static DotNetFramework resolveFramework(
      FileSystem osFilesystem,
      ImmutableMap<String, String> env,
      FrameworkVersion version) {

    Path programFiles = findProgramFiles(osFilesystem, env);
    Path baseDir = programFiles.resolve("Reference Assemblies")
        .resolve("Microsoft")
        .resolve("Framework");

    Path frameworkDir;
    switch (version) {
      case NET35:
        frameworkDir = baseDir.resolve("v3.5");
        break;

      // All other cases, just fall through
      case NET40:
      case NET45:
      case NET46:
        frameworkDir = baseDir.resolve(".NETFramework").resolve(version.getDirName());
        break;

      // Which we should never reach
      default:
        throw new HumanReadableException("Unknown .net framework version: %s", version);
    }

    if (!Files.exists(frameworkDir)) {
      throw new HumanReadableException(
          "Resolved framework dir for %s does not exist: %s",
          version,
          frameworkDir);
    }
    if (!Files.isDirectory(frameworkDir)) {
      throw new HumanReadableException(
          "Resolved framework directory is not a directory: %s",
          frameworkDir);
    }

    return new DotNetFramework(version, frameworkDir);
  }

  private static Path findProgramFiles(FileSystem osFilesystem, ImmutableMap<String, String> env) {
    for (String envName : PROGRAM_FILES_ENV_NAMES) {
      for (String key : env.keySet()) {
        if (envName.equals(key.toLowerCase(US))) {
          String value = env.get(key);
          Path path = osFilesystem.getPath(value);
          if (Files.exists(path)) {
            return path;
          } else {
            LOG.info("Found a program files path with %s that did not exist: %s", key, value);
          }
        }
      }
    }

    throw new HumanReadableException("Unable to find ProgramFiles or ProgramFiles(x86) env var");
  }
}
