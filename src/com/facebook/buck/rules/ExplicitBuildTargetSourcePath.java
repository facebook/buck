/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Pair;
import java.nio.file.Path;

/**
 * A {@link BuildTargetSourcePath} which resolves to a specific (possibly non-default) output of the
 * {@link BuildRule} referred to by its target.
 */
public class ExplicitBuildTargetSourcePath
    extends BuildTargetSourcePath<ExplicitBuildTargetSourcePath> {

  private final Path resolvedPath;

  public ExplicitBuildTargetSourcePath(BuildTarget target, Path path) {
    super(target);
    this.resolvedPath = path;
  }

  public Path getResolvedPath() {
    return resolvedPath;
  }

  @Override
  protected Object asReference() {
    return new Pair<>(getTarget(), resolvedPath);
  }

  @Override
  protected int compareReferences(ExplicitBuildTargetSourcePath o) {
    if (o == this) {
      return 0;
    }

    int res = getTarget().compareTo(o.getTarget());
    if (res != 0) {
      return res;
    }

    return resolvedPath.compareTo(o.resolvedPath);
  }
}
