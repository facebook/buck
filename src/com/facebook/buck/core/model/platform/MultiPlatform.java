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

package com.facebook.buck.core.model.platform;

import com.facebook.buck.core.exceptions.DependencyStack;
import com.google.common.collect.ImmutableCollection;
import java.util.Collection;

/**
 * A generic platform that encapsulates multiple other platforms. This is used to support building
 * packages and binaries that support multiple platforms (for example, an Android binary).
 *
 * <p>A multiplatform is a platform that has a base platform and multiple nested platforms. When
 * this platform is used in the context that expects a single platform the base platform is used to
 * match the constraints.
 */
public interface MultiPlatform extends NamedPlatform {

  @Override
  default boolean matchesAll(
      Collection<ConstraintValue> constraintValues, DependencyStack dependencyStack) {
    return getBasePlatform().matchesAll(constraintValues, dependencyStack);
  }

  /** Access base platform of this platform, for example, CPU-neutral Android platform */
  Platform getBasePlatform();

  /** Access nested platforms, for example, per-CPU platforms for Android platform */
  ImmutableCollection<NamedPlatform> getNestedPlatforms();
}
