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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Preconditions;

import java.nio.file.Path;

import javax.annotation.Nullable;

/**
 * Exposes some {@link com.facebook.buck.model.BuildTarget} logic that is only visible for testing.
 */
public class BuildTargetFactory {

  private BuildTargetFactory() {
    // Utility class
  }

  public static BuildTarget newInstance(String fullyQualifiedName) {
    return newInstance((Path) null, fullyQualifiedName);
  }

  public static BuildTarget newInstance(ProjectFilesystem filesystem, String fullyQualifiedName) {
    return newInstance(filesystem.getRootPath(), fullyQualifiedName);
  }

  public static BuildTarget newInstance(
      @Nullable Path root,
      String fullyQualifiedName) {
    root = root == null ? new FakeProjectFilesystem().getRootPath() : root;

    String[] parts = fullyQualifiedName.split(":");
    Preconditions.checkArgument(parts.length == 2);
    String[] nameAndFlavor = parts[1].split("#");
    if (nameAndFlavor.length != 2) {
      return BuildTarget.builder(root, parts[0], parts[1]).build();
    }
    String[] flavors = nameAndFlavor[1].split(",");
    BuildTarget.Builder buildTargetBuilder =
        BuildTarget.builder(root, parts[0], nameAndFlavor[0]);
    for (String flavor : flavors) {
      buildTargetBuilder.addFlavors(ImmutableFlavor.of(flavor));
    }
    return buildTargetBuilder.build();
  }
}
