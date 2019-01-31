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

package com.facebook.buck.features.dotnet;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Represents an instance of the .Net framework. Two instances are assumed to be equal iff they
 * represent the same {@link FrameworkVersion} (notably, where that framework is installed is
 * irrelevant).
 */
public class DotnetFramework {

  private final FrameworkVersion version;
  private final ImmutableList<Path> frameworkDirs;

  private DotnetFramework(FrameworkVersion version, ImmutableList<Path> frameworkDirs) {
    this.version = version;
    this.frameworkDirs = frameworkDirs;
  }

  public static DotnetFramework resolveFramework(FrameworkVersion version) {
    return resolveFramework(FileSystems.getDefault(), version);
  }

  public Path findReferenceAssembly(String dllName) {
    for (Path frameworkDir : frameworkDirs) {
      Path toReturn = frameworkDir.resolve(dllName);
      if (Files.exists(toReturn)) {
        return toReturn;
      }
    }

    throw new HumanReadableException(
        "Unable to find dll in framework version %s under %s: %s", version, frameworkDirs, dllName);
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

    if (!(obj instanceof DotnetFramework)) {
      return false;
    }

    return version.equals(((DotnetFramework) obj).version);
  }

  @VisibleForTesting
  static DotnetFramework resolveFramework(FileSystem osFilesystem, FrameworkVersion version) {

    Builder<Path> builder = ImmutableList.builder();
    for (String dir : version.getDirectories()) {
      Path frameworkDir = osFilesystem.getPath(dir);
      if (!Files.exists(frameworkDir)) {
        throw new HumanReadableException(
            String.format("Required dir %s for %s was not found", dir, version));
      } else if (!Files.isDirectory(frameworkDir)) {
        throw new HumanReadableException(
            "Resolved framework directory is not a directory: %s", frameworkDir);
      } else {
        builder.add(frameworkDir);
      }
    }

    return new DotnetFramework(version, builder.build());
  }
}
