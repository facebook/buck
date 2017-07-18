/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.model;

import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Exposes some {@link com.facebook.buck.model.BuildTarget} logic that is only visible for testing.
 */
public class BuildTargetFactory {

  private BuildTargetFactory() {
    // Utility class
  }

  public static BuildTarget newInstance(String fullyQualifiedName) {
    return newInstance(null, fullyQualifiedName);
  }

  public static BuildTarget newInstance(@Nullable Path root, String fullyQualifiedName) {
    root = root == null ? new FakeProjectFilesystem().getRootPath() : root;

    int buildTarget = fullyQualifiedName.indexOf("//");
    Optional<String> cellName = Optional.empty();
    if (buildTarget > 0) {
      cellName = Optional.of(fullyQualifiedName.substring(0, buildTarget));
    } else if (buildTarget < 0) {
      throw new IllegalArgumentException(
          String.format(
              "Fully qualified target %s did not start with // or a cell name then //",
              fullyQualifiedName));
    }
    String[] parts = fullyQualifiedName.substring(buildTarget).split(":");
    Preconditions.checkArgument(parts.length == 2);
    String[] nameAndFlavor = parts[1].split("#");
    if (nameAndFlavor.length != 2) {
      return BuildTarget.of(UnflavoredBuildTarget.of(root, cellName, parts[0], parts[1]));
    }
    String[] flavors = nameAndFlavor[1].split(",");
    return BuildTarget.of(
        UnflavoredBuildTarget.of(root, cellName, parts[0], nameAndFlavor[0]),
        RichStream.from(flavors).map(InternalFlavor::of).toOnceIterable());
  }

  public static BuildTarget newInstance(Path cellPath, String baseName, String shortName) {
    return BuildTarget.of(
        UnflavoredBuildTarget.of(cellPath, Optional.empty(), baseName, shortName));
  }

  public static BuildTarget newInstance(
      Path cellPath, String baseName, String shortName, Flavor... flavors) {
    return BuildTarget.of(
        UnflavoredBuildTarget.of(cellPath, Optional.empty(), baseName, shortName),
        ImmutableSet.copyOf(flavors));
  }
}
