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

package com.facebook.buck.features.project.intellij.model.folders;

import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import javax.annotation.Nullable;

public abstract class ResourceFolder extends InclusiveFolder {
  protected final Path resourcesRoot;

  ResourceFolder(Path path, @Nullable Path resourcesRoot, ImmutableSortedSet<Path> inputs) {
    super(path, false, inputs);
    if (resourcesRoot == null) {
      this.resourcesRoot = Paths.get("");
    } else {
      this.resourcesRoot = resourcesRoot;
    }
  }

  public Path getRelativeOutputPath() {
    return resourcesRoot.relativize(getPath());
  }

  public Path getResourcesRoot() {
    return resourcesRoot;
  }

  @Override
  public boolean isResourceFolder() {
    return true;
  }

  @Override
  public IjFolder merge(IjFolder otherFolder) {
    if (equals(otherFolder)) {
      return this;
    }

    ResourceFolder otherResourceFolder = (ResourceFolder) otherFolder;
    return getResourceFactory()
        .create(
            otherResourceFolder.getPath(),
            otherResourceFolder.getResourcesRoot(),
            combineInputs(this, otherResourceFolder));
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

  public abstract IjResourceFolderType getResourceFolderType();

  public abstract ResourceFolderFactory getResourceFactory();
}
