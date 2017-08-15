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
import javax.annotation.Nullable;

public abstract class ResourceFolder extends InclusiveFolder {
  @Nullable protected final Path resourcesRoot;

  ResourceFolder(Path path, ImmutableSortedSet<Path> inputs, @Nullable Path resourcesRoot) {
    super(path, false, inputs);
    this.resourcesRoot = resourcesRoot;
  }

  public Optional<Path> getRelativeOutputPath() {
    if (resourcesRoot == null || !getPath().startsWith(resourcesRoot)) {
      return Optional.empty();
    } else {
      return Optional.of(resourcesRoot.relativize(getPath()));
    }
  }

  @Nullable
  public Path getResourcesRoot() {
    return resourcesRoot;
  }

  @Override
  public boolean canMergeWith(IjFolder other) {
    return super.canMergeWith(other)
        && Objects.equals(resourcesRoot, ((ResourceFolder) other).resourcesRoot);
  }

  @Override
  public boolean equals(Object other) {
    return super.equals(other)
        && Objects.equals(resourcesRoot, ((ResourceFolder) other).resourcesRoot);
  }

  @Override
  public int hashCode() {
    return super.hashCode() ^ Objects.hashCode(resourcesRoot);
  }
}
