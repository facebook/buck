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

package com.facebook.buck.features.project.intellij.lang.java;

import com.facebook.buck.io.file.MorePaths;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class JavaPackagePathCache {
  private Map<Path, Path> cache;

  public JavaPackagePathCache() {
    this.cache = new HashMap<>();
  }

  public void insert(Path pathRelativeToProjectRoot, Path packageFolder) {
    Path parentPath = pathRelativeToProjectRoot.getParent();
    cache.put(parentPath, packageFolder);

    if (parentPath.endsWith(Objects.requireNonNull(packageFolder))) {
      Path packagePath = packageFolder;
      for (int i = 0; i <= packageFolder.getNameCount(); ++i) {
        cache.put(parentPath, packagePath);
        parentPath = MorePaths.getParentOrEmpty(parentPath);
        packagePath = MorePaths.getParentOrEmpty(packagePath);
      }
    }
  }

  public Optional<Path> lookup(Path pathRelativeToProjectRoot) {
    Path path = pathRelativeToProjectRoot.getParent();
    while (path != null) {
      Path prefix = cache.get(path);
      if (prefix != null) {
        Path suffix = path.relativize(pathRelativeToProjectRoot.getParent());
        return Optional.of(prefix.resolve(suffix));
      }
      path = path.getParent();
    }

    return Optional.empty();
  }
}
