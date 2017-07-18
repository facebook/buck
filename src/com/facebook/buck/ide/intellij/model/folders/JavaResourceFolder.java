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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public class JavaResourceFolder extends InclusiveFolder {

  private Path resourcesRoot;
  private ResourceFolderType folderType;

  public JavaResourceFolder(
      Path path,
      ImmutableSortedSet<Path> inputs,
      Path resourcesRoot,
      ResourceFolderType folderType) {
    super(path, false, inputs);
    Preconditions.checkNotNull(resourcesRoot);
    Preconditions.checkNotNull(folderType);
    this.resourcesRoot = resourcesRoot;
    this.folderType = folderType;
  }

  public Optional<Path> getRelativeOutputPath() {
    if (resourcesRoot == null || !getPath().startsWith(resourcesRoot)) {
      return Optional.empty();
    } else {
      return Optional.of(resourcesRoot.relativize(getPath()));
    }
  }

  public Path getResourcesRoot() {
    return resourcesRoot;
  }

  public ResourceFolderType getFolderType() {
    return folderType;
  }

  @Override
  protected IJFolderFactory getFactory() {
    return getFactoryWithResourcesRootAndType(resourcesRoot, folderType);
  }

  public IJFolderFactory getFactoryWithSameResourcesRootAndType() {
    return getFactory();
  }

  public static final IJFolderFactory getFactoryWithResourcesRootAndType(
      Path resourcesRoot, ResourceFolderType folderType) {
    return ((path, wantsPrefix, inputs) ->
        new JavaResourceFolder(path, inputs, resourcesRoot, folderType));
  }

  @Override
  public boolean canMergeWith(IjFolder other) {
    return super.canMergeWith(other)
        && Objects.equals(resourcesRoot, ((JavaResourceFolder) other).resourcesRoot)
        && Objects.equals(folderType, ((JavaResourceFolder) other).folderType);
  }

  @Override
  public boolean equals(Object other) {
    return super.equals(other)
        && Objects.equals(resourcesRoot, ((JavaResourceFolder) other).resourcesRoot)
        && Objects.equals(folderType, ((JavaResourceFolder) other).folderType);
  }

  @Override
  public int hashCode() {
    return super.hashCode() ^ Objects.hashCode(resourcesRoot) ^ Objects.hashCode(folderType);
  }

}
