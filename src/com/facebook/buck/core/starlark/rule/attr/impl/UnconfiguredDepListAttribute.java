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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.starlark.rule.attr.Attribute;
import com.facebook.buck.core.starlark.rule.attr.PostCoercionTransform;
import com.facebook.buck.core.starlark.rule.data.SkylarkDependency;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.reflect.TypeToken;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import java.util.List;

/**
 * Represents a list of dependencies. These are exposed to users as {@link ProviderInfoCollection}
 */
@BuckStyleValue
public abstract class UnconfiguredDepListAttribute
    extends Attribute<ImmutableList<UnconfiguredBuildTarget>> {

  private static final TypeCoercer<?, ImmutableList<UnconfiguredBuildTarget>> coercer =
      TypeCoercerFactoryForStarlark.typeCoercerForType(
          new TypeToken<ImmutableList<UnconfiguredBuildTarget>>() {});

  @Override
  public abstract ImmutableList<String> getPreCoercionDefaultValue();

  @Override
  public abstract String getDoc();

  @Override
  public abstract boolean getMandatory();

  /** Whether or not the list can be empty */
  public abstract boolean getAllowEmpty();

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.append("<attr.dep_list>");
  }

  @Override
  public TypeCoercer<?, ImmutableList<UnconfiguredBuildTarget>> getTypeCoercer() {
    return coercer;
  }

  @Override
  public void validateCoercedValue(ImmutableList<UnconfiguredBuildTarget> paths)
      throws CoerceFailedException {
    if (!getAllowEmpty() && paths.isEmpty()) {
      throw new CoerceFailedException("List of dep paths may not be empty");
    }
  }

  @Override
  public PostCoercionTransform<RuleAnalysisContext, List<SkylarkDependency>>
      getPostCoercionTransform() {
    return this::postCoercionTransform;
  }

  public static UnconfiguredDepListAttribute of(
      ImmutableList<String> preCoercionDefaultValue,
      String doc,
      boolean mandatory,
      boolean allowEmpty) {
    return ImmutableUnconfiguredDepListAttribute.of(
        preCoercionDefaultValue, doc, mandatory, allowEmpty);
  }

  @SuppressWarnings("unused")
  private ImmutableList<SkylarkDependency> postCoercionTransform(
      Object coercedValue, RuleAnalysisContext analysisContext) {
    Verify.verify(coercedValue instanceof List<?>, "Value %s must be a list", coercedValue);
    List<?> listValue = (List<?>) coercedValue;
    ImmutableList.Builder<SkylarkDependency> builder =
        ImmutableList.builderWithExpectedSize(listValue.size());

    return analysisContext
        .resolveDeps(
            Iterables.transform(
                listValue,
                target -> {
                  Verify.verify(target instanceof BuildTarget, "%s must be a BuildTarget", target);
                  return (BuildTarget) target;
                }))
        .entrySet().stream()
        .map(
            targetAndProviders ->
                new SkylarkDependency(targetAndProviders.getKey(), targetAndProviders.getValue()))
        .collect(ImmutableList.toImmutableList());
  }
}
