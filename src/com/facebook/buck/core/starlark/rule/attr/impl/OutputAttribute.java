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

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.starlark.rule.attr.Attribute;
import com.facebook.buck.core.starlark.rule.attr.PostCoercionTransform;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.google.common.reflect.TypeToken;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;

/**
 * Represents a single output file.
 *
 * <p>The string value is turned into a relative path which is then converted to a declared artifact
 * automatically before executing the user's implementation function
 */
@BuckStyleValue
public abstract class OutputAttribute extends Attribute<String> {

  private static final TypeCoercer<?, String> coercer =
      TypeCoercerFactoryForStarlark.typeCoercerForType(TypeToken.of(String.class));

  @Override
  public abstract Object getPreCoercionDefaultValue();

  @Override
  public abstract String getDoc();

  @Override
  public abstract boolean getMandatory();

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.append("<attr.output>");
  }

  @Override
  public TypeCoercer<?, String> getTypeCoercer() {
    return coercer;
  }

  @Override
  public PostCoercionTransform<RuleAnalysisContext, ?> getPostCoercionTransform() {
    return this::postCoercionTransform;
  }

  public static OutputAttribute of(Object preCoercionDefaultValue, String doc, boolean mandatory) {
    return ImmutableOutputAttribute.of(preCoercionDefaultValue, doc, mandatory);
  }

  Artifact postCoercionTransform(Object coercedValue, RuleAnalysisContext analysisContext) {
    return OutputAttributeValidator.validateAndRegisterArtifact(
        coercedValue, analysisContext.actionRegistry());
  }
}
