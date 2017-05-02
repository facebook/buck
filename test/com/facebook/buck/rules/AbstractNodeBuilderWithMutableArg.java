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
import com.facebook.buck.rules.coercer.CoercedTypeCache;
import com.facebook.buck.rules.coercer.ParamInfo;
import com.facebook.buck.rules.coercer.ParamInfoException;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import java.lang.reflect.Field;

public class AbstractNodeBuilderWithMutableArg<
        TArg, TDescription extends Description<TArg>, TBuildRule extends BuildRule>
    extends AbstractNodeBuilder<TArg, TDescription, TBuildRule> {

  protected final TArg arg;

  protected AbstractNodeBuilderWithMutableArg(TDescription description, BuildTarget target) {
    super(description, target);
    this.arg = makeArgWithDefaultValues(description);
  }

  protected AbstractNodeBuilderWithMutableArg(
      TDescription description, BuildTarget target, ProjectFilesystem projectFilesystem) {
    super(description, target, projectFilesystem);
    this.arg = makeArgWithDefaultValues(description);
  }

  protected AbstractNodeBuilderWithMutableArg(
      TDescription description,
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      HashCode hashCode) {
    super(description, target, projectFilesystem, hashCode);
    this.arg = makeArgWithDefaultValues(description);
  }

  @Override
  protected TArg getArgForPopulating() {
    return arg;
  }

  @Override
  protected TArg getPopulatedArg() {
    return arg;
  }

  /**
   * Populate optional fields of this constructor arg with their default values.
   *
   * <p>TODO(dwh): Remove this when all constructor args are immutables.
   */
  private TArg makeArgWithDefaultValues(TDescription description) {
    TArg arg;
    try {
      arg = description.getConstructorArgType().newInstance();
    } catch (IllegalAccessException | InstantiationException e) {
      throw new IllegalStateException(
          String.format(
              "Could not instantiate constructor arg %s for %s",
              description.getConstructorArgType().getClass(), description.getClass()),
          e);
    }
    try {
      for (ParamInfo info :
          CoercedTypeCache.INSTANCE
              .getAllParamInfo(TYPE_COERCER_FACTORY, arg.getClass())
              .values()) {
        if (info.isOptional()) {
          info.set(cellRoots, filesystem, target.getBasePath(), arg, null);
        }
      }
    } catch (ParamInfoException error) {
      throw new RuntimeException(error);
    }
    return arg;
  }

  @SuppressWarnings("unchecked")
  @Override
  protected final ImmutableSortedSet<BuildTarget> getDepsFromArg(TArg arg) {
    // Not all rules have deps, but all rules call them deps. When they do, they're always optional.
    // Grab them in the unsafest way I know.
    try {
      Field depsField = arg.getClass().getField("deps");
      Object deps = depsField.get(arg);

      if (deps == null) {
        return ImmutableSortedSet.of();
      }
      // Here's a whole series of assumptions in one lump of a Bad Idea.
      return (ImmutableSortedSet<BuildTarget>) deps;
    } catch (ReflectiveOperationException ignored) {
      // Field doesn't exist: no deps.
      return ImmutableSortedSet.of();
    }
  }
}
