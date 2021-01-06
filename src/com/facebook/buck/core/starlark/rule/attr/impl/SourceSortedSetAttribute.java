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
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.reflect.TypeToken;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;

/**
 * Class that represents a set of source files, whether on disk or that are other build targets
 *
 * <p>Using this attribute will automatically add source files that are build targets as
 * dependencies.
 */
@BuckStyleValue
public abstract class SourceSortedSetAttribute extends Attribute<ImmutableSortedSet<SourcePath>> {

  private static final TypeCoercer<?, ImmutableSortedSet<SourcePath>> coercer =
      TypeCoercerFactoryForStarlark.typeCoercerForType(
          new TypeToken<ImmutableSortedSet<SourcePath>>() {});

  @Override
  public abstract ImmutableSortedSet<String> getPreCoercionDefaultValue();

  @Override
  public abstract String getDoc();

  @Override
  public abstract boolean getMandatory();

  /** Whether or not the list can be empty */
  public abstract boolean getAllowEmpty();

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.append("<attr.source_set>");
  }

  @Override
  public TypeCoercer<?, ImmutableSortedSet<SourcePath>> getTypeCoercer() {
    return coercer;
  }

  @Override
  public void validateCoercedValue(ImmutableSortedSet<SourcePath> paths)
      throws CoerceFailedException {
    if (!getAllowEmpty() && paths.isEmpty()) {
      throw new CoerceFailedException("List of source paths may not be empty");
    }
  }

  @Override
  public PostCoercionTransform<RuleAnalysisContext, ImmutableSortedSet<Artifact>>
      getPostCoercionTransform() {
    return this::postCoercionTransform;
  }

  private ImmutableSortedSet<Artifact> postCoercionTransform(
      Object coercedValue, RuleAnalysisContext analysisContext) {
    if (!(coercedValue instanceof Iterable<?>)) {
      throw new IllegalArgumentException(
          String.format("Value %s must be an iterable", coercedValue));
    }
    Iterable<?> iterableValue = (Iterable<?>) coercedValue;

    return ImmutableSortedSet.copyOf(
        analysisContext.resolveSrcs(
            Iterables.transform(
                iterableValue,
                src -> {
                  if (!(src instanceof SourcePath)) {
                    throw new IllegalStateException(
                        String.format("%s needs to be a SourcePath", src));
                  } else {
                    return (SourcePath) src;
                  }
                })));
  }

  public static SourceSortedSetAttribute of(
      ImmutableSortedSet<String> preCoercionDefaultValue,
      String doc,
      boolean mandatory,
      boolean allowEmpty) {
    return ImmutableSourceSortedSetAttribute.of(
        preCoercionDefaultValue, doc, mandatory, allowEmpty);
  }
}
