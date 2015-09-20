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

package com.facebook.buck.rules;

import com.facebook.buck.io.ProjectFilesystem;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import javax.annotation.Nullable;

/**
 * Provides a mechanism to take the cell name from a {@link com.facebook.buck.model.BuildTarget} and
 * resolve that down to a {@link ProjectFilesystem}.
 * <p>
 * This is used to provide a layer of indirection from {@link Repository} to
 * {@link TargetGraphToActionGraph} without needing to make everything aware of how to handle
 * Repositories. Equality is measured by comparing the underlying filesystems, since this is how we
 * manage equality of Cell instances.
 */
public class CellFilesystemResolver implements Function<Optional<String>, ProjectFilesystem> {
  private ProjectFilesystem filesystem;
  private final Function<Optional<String>, ProjectFilesystem> repoFilesystemAliases;

  public CellFilesystemResolver(
      ProjectFilesystem filesystem,
      Function<Optional<String>, ProjectFilesystem> repoFilesystemAliases) {
    this.filesystem = filesystem;
    this.repoFilesystemAliases = repoFilesystemAliases;
  }

  @Override
  public ProjectFilesystem apply(Optional<String> input) {
    return Preconditions.checkNotNull(repoFilesystemAliases.apply(input));
  }

  public ProjectFilesystem getFilesystem() {
    return filesystem;
  }

  @Override
  public boolean equals(@Nullable Object object) {
    if (object == this) {
      return true;
    }

    if (!(object instanceof CellFilesystemResolver)) {
      return false;
    }

    return filesystem.equals(((CellFilesystemResolver) object).filesystem);
  }

  @Override
  public int hashCode() {
    return filesystem.hashCode();
  }
}
