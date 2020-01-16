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

package com.facebook.buck.apple;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableList;

/**
 * Information about a build target that represents a fat binary.
 *
 * <p>Fat binaries are represented by build targets having multiple platform flavors.
 */
@BuckStyleValue
abstract class MultiarchFileInfo {
  public abstract BuildTarget getFatTarget();

  public abstract ImmutableList<BuildTarget> getThinTargets();

  /**
   * Returns a representative platform flavor for use in retrieving architecture agnostic tools.
   *
   * <p>Platforms are architecture specific, but some tools are architecture agnostic. Since there
   * isn't a concept of target architecture agnostic tools, this simply returns one of the
   * platforms, trusting the caller to only use the architecture agnostic tools.
   */
  public abstract Flavor getRepresentativePlatformFlavor();
}
