/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.ide.intellij.model.folders;

import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public class JavaResourceFolder extends InclusiveFolder {

  private Path resourcesRoot;

  JavaResourceFolder(Path path, ImmutableSortedSet<Path> inputs) {
    this(path, inputs, null);
  }

  public JavaResourceFolder(Path path, ImmutableSortedSet<Path> inputs, Path resourcesRoot) {
    super(path, false, inputs);
    this.resourcesRoot = resourcesRoot;
  }

  public JavaResourceFolder(Path path) {
    super(path);
  }

  public Optional<Path> getRelativeOutputPath() {
    if (resourcesRoot == null || !getPath().startsWith(resourcesRoot)) {
      return Optional.empty();
    } else {
      return Optional.of(resourcesRoot.relativize(getPath()));
    }
  }
  @Override
  protected IJFolderFactory getFactory() {
    return getFactoryWithResourcesRoot(resourcesRoot);
  }

  public IJFolderFactory getFactoryWithSameResourcesRoot() {
    return getFactory();
  }

  public static final IJFolderFactory getFactoryWithResourcesRoot(Path resourcesRoot) {
    return ((path, wantsPrefix, inputs) -> new JavaResourceFolder(path, inputs, resourcesRoot));
  }

  @Override
  public boolean canMergeWith(IjFolder other) {
    return super.canMergeWith(other)
        && Objects.equals(resourcesRoot, ((JavaResourceFolder) other).resourcesRoot);
  }

  @Override
  public boolean equals(Object other) {
    return super.equals(other) &&
        Objects.equals(resourcesRoot, ((JavaResourceFolder) other).resourcesRoot);
  }

  @Override
  public int hashCode() {
    return super.hashCode() ^ Objects.hashCode(resourcesRoot);
  }
}
