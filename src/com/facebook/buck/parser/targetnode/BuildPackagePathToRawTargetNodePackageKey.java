/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.parser.targetnode;

import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.model.targetgraph.RawTargetNodeWithDepsPackage;
import java.nio.file.Path;
import org.immutables.value.Value;

/**
 * Transformation key containing a path to a build package to get parsed {@link
 * RawTargetNodeWithDepsPackage} from it
 */
@Value.Immutable(builder = false, copy = false, prehash = true)
public abstract class BuildPackagePathToRawTargetNodePackageKey
    implements ComputeKey<RawTargetNodeWithDepsPackage> {

  /**
   * A path to a package root directory, i.e. a directory containing build file, relative to some
   * root (usually cell folder root)
   */
  @Value.Parameter
  public abstract Path getPath();

  @Override
  public Class<? extends ComputeKey<?>> getKeyClass() {
    return BuildPackagePathToRawTargetNodePackageKey.class;
  }
}
