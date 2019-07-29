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
package com.facebook.buck.core.starlark.rule.attr.impl;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.rules.providers.ProviderInfoCollection;
import com.facebook.buck.core.starlark.rule.attr.Attribute;
import com.facebook.buck.core.starlark.rule.attr.PostCoercionTransform;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.coercer.BuildTargetTypeCoercer;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.facebook.buck.rules.coercer.UnconfiguredBuildTargetTypeCoercer;
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;

/** Represents a single dependency. This is exposed to users as a {@link ProviderInfoCollection} */
@BuckStyleValue
public abstract class DepAttribute extends Attribute<BuildTarget> {

  private static final TypeCoercer<BuildTarget> coercer =
      new BuildTargetTypeCoercer(
          new UnconfiguredBuildTargetTypeCoercer(new ParsingUnconfiguredBuildTargetViewFactory()));

  @Override
  public abstract Object getPreCoercionDefaultValue();

  @Override
  public abstract String getDoc();

  @Override
  public abstract boolean getMandatory();

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.append("<attr.dep>");
  }

  @Override
  public TypeCoercer<BuildTarget> getTypeCoercer() {
    return coercer;
  }

  @Override
  public PostCoercionTransform<
          ImmutableMap<BuildTarget, ProviderInfoCollection>, ProviderInfoCollection>
      getPostCoercionTransform() {
    return DepAttribute::getProviderInfoCollectionForDep;
  }

  /**
   * Gets the ProviderInfoCollection for a specific dependency, or throws if the target is invalid
   *
   * @param target A post-coercion object that should be a {@link BuildTarget}
   * @param deps The map to lookup the provided build target in
   * @return The {@link ProviderInfoCollection} for the target given in {@code target}
   * @throws com.google.common.base.VerifyException if {@code target} is not a {@link BuildTarget}
   * @throws NullPointerException if provider information could not be found for the given target
   */
  static ProviderInfoCollection getProviderInfoCollectionForDep(
      Object target, ImmutableMap<BuildTarget, ProviderInfoCollection> deps) {
    Verify.verify(target instanceof BuildTarget, "%s must be a BuildTarget", target);

    return Preconditions.checkNotNull(deps.get(target), "Deps %s did not contain %s", deps, target);
  }
}
