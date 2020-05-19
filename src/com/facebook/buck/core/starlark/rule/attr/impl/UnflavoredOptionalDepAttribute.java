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
import com.facebook.buck.core.starlark.rule.attr.Attribute;
import com.facebook.buck.core.starlark.rule.attr.PostCoercionTransform;
import com.facebook.buck.core.starlark.rule.data.SkylarkDependency;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import com.google.devtools.build.lib.syntax.Printer;
import com.google.devtools.build.lib.syntax.StarlarkList;
import java.util.List;
import java.util.Optional;

/** Represents a list of targets. Currently used only for {@code default_target_platform}. */
@BuckStyleValue
public abstract class UnflavoredOptionalDepAttribute
    extends Attribute<Optional<UnflavoredBuildTarget>> {

  private static final TypeCoercer<?, Optional<UnflavoredBuildTarget>> coercer =
      TypeCoercerFactoryForStarlark.typeCoercerForType(
          new TypeToken<Optional<UnflavoredBuildTarget>>() {});

  @Override
  public abstract Optional<String> getPreCoercionDefaultValue();

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
  public TypeCoercer<?, Optional<UnflavoredBuildTarget>> getTypeCoercer() {
    return coercer;
  }

  @Override
  public void validateCoercedValue(Optional<UnflavoredBuildTarget> paths)
      throws CoerceFailedException {
    if (!getAllowEmpty() && !paths.isPresent()) {
      throw new CoerceFailedException("List of dep paths may not be empty");
    }
  }

  @Override
  public PostCoercionTransform<
          RuleAnalysisContext, Optional<UnflavoredBuildTarget>, List<SkylarkDependency>>
      getPostCoercionTransform() {
    return this::postCoercionTransform;
  }

  public static UnflavoredOptionalDepAttribute of(
      Optional<String> preCoercionDefaultValue, String doc, boolean mandatory, boolean allowEmpty) {
    return ImmutableUnflavoredOptionalDepAttribute.ofImpl(
        preCoercionDefaultValue, doc, mandatory, allowEmpty);
  }

  private StarlarkList<SkylarkDependency> postCoercionTransform(
      Optional<UnflavoredBuildTarget> coercedValue, RuleAnalysisContext analysisContext) {
    return StarlarkList.immutableCopyOf(
        analysisContext
            .resolveDeps(
                coercedValue
                    .map(
                        target -> {
                          // TODO(nga): use proper configuration
                          return ImmutableList.of(
                              UnconfiguredBuildTarget.of(target, FlavorSet.NO_FLAVORS)
                                  .configure(UnconfiguredTargetConfiguration.INSTANCE));
                        })
                    .orElse(ImmutableList.of()))
            .entrySet().stream()
            .map(
                targetAndProviders ->
                    new SkylarkDependency(
                        targetAndProviders.getKey(), targetAndProviders.getValue()))
            .collect(ImmutableList.toImmutableList()));
  }
}
