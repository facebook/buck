/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.java;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;

import java.nio.file.Path;

public class ResourcesRootPackageFinder implements JavaPackageFinder {

  private final Path resourcesRoot;
  private final JavaPackageFinder fallbackFinder;

  public ResourcesRootPackageFinder(Path resourcesRoot, JavaPackageFinder fallbackFinder) {
    this.resourcesRoot = resourcesRoot;
    this.fallbackFinder = fallbackFinder;
  }

  @Override
  public Path findJavaPackageFolder(Path pathRelativeToProjectRoot) {
    if (pathRelativeToProjectRoot.startsWith(resourcesRoot)) {
      return MorePaths.getParentOrEmpty(
          MorePaths.relativize(
              resourcesRoot,
              pathRelativeToProjectRoot));
    }
    return fallbackFinder.findJavaPackageFolder(pathRelativeToProjectRoot);
  }

  @Override
  public String findJavaPackage(Path pathRelativeToProjectRoot) {
    Path folder = findJavaPackageFolder(pathRelativeToProjectRoot);
    return DefaultJavaPackageFinder.findJavaPackageWithPackageFolder(folder);
  }

  @Override
  public String findJavaPackage(BuildTarget buildTarget) {
    return fallbackFinder.findJavaPackage(buildTarget);
  }
}
