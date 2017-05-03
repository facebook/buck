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

package com.facebook.buck.rules;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;

public abstract class AbstractNodeBuilderWithImmutableArg<
        TArgBuilder, TArg, TDescription extends Description<TArg>, TBuildRule extends BuildRule>
    extends AbstractNodeBuilder<TArg, TDescription, TBuildRule> {

  protected final TArgBuilder argBuilder;

  protected AbstractNodeBuilderWithImmutableArg(TDescription description, BuildTarget target) {
    super(description, target);
    this.argBuilder = makeArgBuilder(description);
  }

  protected AbstractNodeBuilderWithImmutableArg(
      TDescription description, BuildTarget target, ProjectFilesystem projectFilesystem) {
    super(description, target, projectFilesystem);
    this.argBuilder = makeArgBuilder(description);
  }

  protected AbstractNodeBuilderWithImmutableArg(
      TDescription description,
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      HashCode hashCode) {
    super(description, target, projectFilesystem, hashCode);
    this.argBuilder = makeArgBuilder(description);
  }

  @SuppressWarnings("unchecked")
  private TArgBuilder makeArgBuilder(TDescription description) {
    Class<? extends TArg> constructorArgType = description.getConstructorArgType();
    try {
      return (TArgBuilder) constructorArgType.getMethod("builder").invoke(null);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected TArgBuilder getArgForPopulating() {
    return argBuilder;
  }

  @Override
  @SuppressWarnings("unchecked")
  protected TArg getPopulatedArg() {
    try {
      return (TArg) argBuilder.getClass().getMethod("build").invoke(argBuilder);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  protected final ImmutableSortedSet<BuildTarget> getDepsFromArg(TArg arg) {
    // Not all rules have deps, but all rules call them deps. When they do, they're always optional.
    // Grab them in the unsafest way I know.
    try {
      // Here's a whole series of assumptions in one lump of a Bad Idea.
      return (ImmutableSortedSet<BuildTarget>) arg.getClass().getMethod("getDeps").invoke(arg);
    } catch (ReflectiveOperationException ignored) {
      // Method doesn't exist: no deps.
      return ImmutableSortedSet.of();
    }
  }
}
