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

package com.facebook.buck.core.starlark.rule.attr.impl;

import com.facebook.buck.core.model.FlavorSet;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.starlark.rule.attr.Attribute;
import com.facebook.buck.core.starlark.rule.attr.PostCoercionTransform;
import com.facebook.buck.core.starlark.rule.data.SkylarkDependency;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import java.util.List;
import java.util.stream.Collectors;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.StarlarkList;

/**
 * Represents a list of dependencies. These are exposed to users as {@link ProviderInfoCollection}
 */
@BuckStyleValue
public abstract class UnflavoredDepListAttribute
    extends Attribute<ImmutableList<UnflavoredBuildTarget>> {

  private static final TypeCoercer<?, ImmutableList<UnflavoredBuildTarget>> coercer =
      TypeCoercerFactoryForStarlark.typeCoercerForType(
          new TypeToken<ImmutableList<UnflavoredBuildTarget>>() {});

  @Override
  public abstract ImmutableList<String> getPreCoercionDefaultValue();

  @Override
  public abstract String getDoc();

  @Override
  public abstract boolean getMandatory();

  /** Whether or not the list can be empty */
  public abstract boolean getAllowEmpty();

  @Override
  public void repr(Printer printer) {
    printer.append("<attr.dep_list>");
  }

  @Override
  public TypeCoercer<?, ImmutableList<UnflavoredBuildTarget>> getTypeCoercer() {
    return coercer;
  }

  @Override
  public void validateCoercedValue(ImmutableList<UnflavoredBuildTarget> paths)
      throws CoerceFailedException {
    if (!getAllowEmpty() && paths.isEmpty()) {
      throw new CoerceFailedException("List of dep paths may not be empty");
    }
  }

  @Override
  public PostCoercionTransform<
          RuleAnalysisContext, ImmutableList<UnflavoredBuildTarget>, List<SkylarkDependency>>
      getPostCoercionTransform() {
    return this::postCoercionTransform;
  }

  public static UnflavoredDepListAttribute of(
      ImmutableList<String> preCoercionDefaultValue,
      String doc,
      boolean mandatory,
      boolean allowEmpty) {
    return ImmutableUnflavoredDepListAttribute.ofImpl(
        preCoercionDefaultValue, doc, mandatory, allowEmpty);
  }

  @SuppressWarnings("unused")
  private StarlarkList<SkylarkDependency> postCoercionTransform(
      ImmutableList<UnflavoredBuildTarget> coercedValue, RuleAnalysisContext analysisContext) {
    ImmutableList.Builder<SkylarkDependency> builder =
        ImmutableList.builderWithExpectedSize(coercedValue.size());

    return StarlarkList.immutableCopyOf(
        analysisContext
            .resolveDeps(
                coercedValue.stream()
                    .map(
                        target -> {
                          // TODO(nga): wrong configuration
                          return UnconfiguredBuildTarget.of(target, FlavorSet.NO_FLAVORS)
                              .configure(UnconfiguredTargetConfiguration.INSTANCE);
                        })
                    .collect(Collectors.toList()))
            .entrySet().stream()
            .map(
                targetAndProviders ->
                    new SkylarkDependency(
                        targetAndProviders.getKey(), targetAndProviders.getValue()))
            .collect(ImmutableList.toImmutableList()));
  }
}
