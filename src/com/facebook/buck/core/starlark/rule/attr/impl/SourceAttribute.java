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
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.google.common.reflect.TypeToken;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;

/**
 * Represents a single source file, whether on disk or another build target
 *
 * <p>Using this attribute will automatically add source files that are build targets as
 * dependencies.
 */
@BuckStyleValue
public abstract class SourceAttribute extends Attribute<SourcePath> {

  private static final TypeCoercer<?, SourcePath> coercer =
      TypeCoercerFactoryForStarlark.typeCoercerForType(TypeToken.of(SourcePath.class));

  @Override
  public abstract Object getPreCoercionDefaultValue();

  @Override
  public abstract String getDoc();

  @Override
  public abstract boolean getMandatory();

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.append("<attr.source>");
  }

  @Override
  public TypeCoercer<?, SourcePath> getTypeCoercer() {
    return coercer;
  }

  @Override
  public PostCoercionTransform<RuleAnalysisContext, Artifact> getPostCoercionTransform() {
    return this::postCoercionTransform;
  }

  public static SourceAttribute of(Object preCoercionDefaultValue, String doc, boolean mandatory) {
    return ImmutableSourceAttribute.of(preCoercionDefaultValue, doc, mandatory);
  }

  private Artifact postCoercionTransform(Object src, RuleAnalysisContext analysisContext) {
    if (!(src instanceof SourcePath)) {
      throw new IllegalStateException(String.format("%s needs to be a SourcePath", src));
    }

    return analysisContext.resolveSrc((SourcePath) src);
  }
}
