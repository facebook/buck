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
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.starlark.rule.attr.Attribute;
import com.facebook.buck.core.starlark.rule.attr.PostCoercionTransform;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.reflect.TypeToken;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import java.util.List;

/**
 * Class that represents a list of sources files, whether on disk or that are other build targets
 *
 * <p>Using this attribute will automatically add source files that are build targets as
 * dependencies.
 */
@BuckStyleValue
public abstract class SourceListAttribute extends Attribute<ImmutableList<SourcePath>> {

  private static final TypeCoercer<?, ImmutableList<SourcePath>> coercer =
      TypeCoercerFactoryForStarlark.typeCoercerForType(
          new TypeToken<ImmutableList<SourcePath>>() {});

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
    printer.append("<attr.source_list>");
  }

  @Override
  public TypeCoercer<?, ImmutableList<SourcePath>> getTypeCoercer() {
    return coercer;
  }

  @Override
  public void validateCoercedValue(ImmutableList<SourcePath> paths) throws CoerceFailedException {
    if (!getAllowEmpty() && paths.isEmpty()) {
      throw new CoerceFailedException("List of source paths may not be empty");
    }
  }

  @Override
  public PostCoercionTransform<RuleAnalysisContext, List<Artifact>> getPostCoercionTransform() {
    return this::postCoercionTransform;
  }

  public static SourceListAttribute of(
      ImmutableList<String> preCoercionDefaultValue,
      String doc,
      boolean mandatory,
      boolean allowEmpty) {
    return ImmutableSourceListAttribute.of(preCoercionDefaultValue, doc, mandatory, allowEmpty);
  }

  private ImmutableList<Artifact> postCoercionTransform(
      Object coercedValue, RuleAnalysisContext analysisContext) {
    if (!(coercedValue instanceof List<?>)) {
      throw new IllegalArgumentException(String.format("Value %s must be a list", coercedValue));
    }
    List<?> listValue = (List<?>) coercedValue;

    return ImmutableList.copyOf(
        analysisContext.resolveSrcs(
            Iterables.transform(
                listValue,
                src -> {
                  if (!(src instanceof SourcePath)) {
                    throw new IllegalStateException(
                        String.format("%s needs to be a SourcePath", src));
                  } else {
                    return (SourcePath) src;
                  }
                })));
  }
}
