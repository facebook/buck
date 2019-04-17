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

package com.facebook.buck.core.parser;

import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import org.immutables.value.Value;

/** Contains relative paths for all the packages matched by some build target pattern */
@Value.Immutable(builder = false, copy = false)
public abstract class BuildPackagePaths implements ComputeResult {

  /** Relative paths to folders that are roots for build packages, i.e. which contain build files */
  @Value.Parameter
  public abstract ImmutableSortedSet<Path> getPackageRoots();
}
