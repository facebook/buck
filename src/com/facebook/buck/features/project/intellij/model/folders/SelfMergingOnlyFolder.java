/*
 * Copyright 2016-present Facebook, Inc.
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

/** Base class for folders which can only be merged with other instances holding equal data. */
public abstract class SelfMergingOnlyFolder extends InclusiveFolder {

  public SelfMergingOnlyFolder(
      Path path, boolean wantsPackagePrefix, ImmutableSortedSet<Path> inputs) {
    super(path, wantsPackagePrefix, inputs);
  }

  public SelfMergingOnlyFolder(Path path) {
    super(path);
  }

  @Override
  public boolean canMergeWith(IjFolder other) {
    return equals(other);
  }

  @Override
  public IjFolder merge(IjFolder otherFolder) {
    if (equals(otherFolder)) {
      return this;
    }

    throw new IllegalArgumentException(
        "Can not merge two " + getClass().getSimpleName() + "s with different paths");
  }
}
