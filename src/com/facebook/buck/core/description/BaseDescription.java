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

package com.facebook.buck.core.description;

import com.facebook.buck.core.description.arg.ConstructorArg;
import com.facebook.buck.core.model.BuildTarget;
import com.google.common.collect.ImmutableSet;

/** Description common to both build and config rule types. */
public interface BaseDescription<T extends ConstructorArg> {

  /** The type of the constructor argument that is used by this description to create a rule */
  Class<T> getConstructorArgType();

  /** @return a set of configuration targets declared in a given constructor argument. */
  @SuppressWarnings("unused")
  default ImmutableSet<BuildTarget> getConfigurationDeps(T arg) {
    return ImmutableSet.of();
  }
}
