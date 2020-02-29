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
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import java.util.List;

/**
 * Represents a list of output files.
 *
 * <p>The list of strings value are turned into relative paths which are then declared automatically
 * before executing the user's implementation function
 */
@BuckStyleValue
public abstract class OutputListAttribute extends Attribute<ImmutableList<String>> {

  private static final TypeCoercer<?, ImmutableList<String>> coercer =
      TypeCoercerFactoryForStarlark.typeCoercerForType(new TypeToken<ImmutableList<String>>() {});

  @Override
  public abstract Object getPreCoercionDefaultValue();

  @Override
  public abstract String getDoc();

  @Override
  public abstract boolean getMandatory();

  /** Whether or not the list can be empty */
  public abstract boolean getAllowEmpty();

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.append("<attr.output_list>");
  }

  @Override
  public TypeCoercer<?, ImmutableList<String>> getTypeCoercer() {
    return coercer;
  }

  @Override
  public void validateCoercedValue(ImmutableList<String> paths) throws CoerceFailedException {
    if (!getAllowEmpty() && paths.isEmpty()) {
      throw new CoerceFailedException("List of outputs may not be empty");
    }
  }

  @Override
  public PostCoercionTransform<RuleAnalysisContext, ?> getPostCoercionTransform() {
    return this::postCoercionTransform;
  }

  public static OutputListAttribute of(
      Object preCoercionDefaultValue, String doc, boolean mandatory, boolean allowEmpty) {
    return ImmutableOutputListAttribute.of(preCoercionDefaultValue, doc, mandatory, allowEmpty);
  }

  ImmutableList<Artifact> postCoercionTransform(
      Object coercedValue, RuleAnalysisContext analysisContext) {
    if (!(coercedValue instanceof List<?>)) {
      throw new IllegalArgumentException(String.format("Value %s must be a list", coercedValue));
    }
    List<?> listValue = (List<?>) coercedValue;
    ImmutableList.Builder<Artifact> builder =
        ImmutableList.builderWithExpectedSize(listValue.size());
    for (Object output : listValue) {
      builder.add(
          OutputAttributeValidator.validateAndRegisterArtifact(
              output, analysisContext.actionRegistry()));
    }
    return builder.build();
  }
}
