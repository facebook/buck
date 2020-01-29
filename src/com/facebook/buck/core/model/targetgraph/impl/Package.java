/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.model.targetgraph.impl;

import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.visibility.VisibilityPattern;
import com.google.common.collect.ImmutableSet;

/**
 * A {@link Package} contains attributes that are applied by default to all {@link TargetNode}s
 * contained within a build file {@link com.facebook.buck.parser.api.BuildFileManifest}. A `Package`
 * contains metadata gathered from `PACKAGE` files of the current directory/package and all parent
 * directories/packages.
 */
@BuckStyleValue
public abstract class Package {

  public abstract ImmutableSet<VisibilityPattern> getVisibilityPatterns();

  public abstract ImmutableSet<VisibilityPattern> getWithinViewPatterns();

  public static Package of(
      ImmutableSet<VisibilityPattern> visibilityPatterns,
      ImmutableSet<VisibilityPattern> withinViewPatterns) {
    return ImmutablePackage.of(visibilityPatterns, withinViewPatterns);
  }
}
