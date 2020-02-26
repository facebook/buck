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
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.rules.providers.Provider;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.starlark.rule.attr.Attribute;
import com.facebook.buck.core.starlark.rule.attr.PostCoercionTransform;
import com.facebook.buck.core.starlark.rule.data.SkylarkDependency;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;

/** Represents a single dependency. This is exposed to users as a {@link ProviderInfoCollection} */
@BuckStyleValue
public abstract class DepAttribute extends Attribute<BuildTarget> {

  private static final TypeCoercer<?, BuildTarget> coercer =
      TypeCoercerFactoryForStarlark.typeCoercerForType(TypeToken.of(BuildTarget.class));

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
  public TypeCoercer<?, BuildTarget> getTypeCoercer() {
    return coercer;
  }

  public abstract ImmutableList<Provider<?>> getProviders();

  @Override
  public PostCoercionTransform<RuleAnalysisContext, SkylarkDependency> getPostCoercionTransform() {
    return this::postCoercionTransform;
  }

  public static DepAttribute of(
      Object preCoercionDefaultValue,
      String doc,
      boolean mandatory,
      ImmutableList<Provider<?>> providers) {
    return ImmutableDepAttribute.of(preCoercionDefaultValue, doc, mandatory, providers);
  }

  private SkylarkDependency postCoercionTransform(Object dep, RuleAnalysisContext analysisContext) {
    Verify.verify(dep instanceof BuildTarget, "%s must be a BuildTarget", dep);
    BuildTarget target = (BuildTarget) dep;

    ProviderInfoCollection providers = analysisContext.resolveDep(target);
    validateProvidersPresent(getProviders(), target, providers);
    return new SkylarkDependency(target, providers);
  }
}
