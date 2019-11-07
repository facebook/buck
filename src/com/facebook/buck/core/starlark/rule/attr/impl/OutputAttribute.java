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

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.actions.ActionRegistry;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.starlark.rule.attr.Attribute;
import com.facebook.buck.core.starlark.rule.attr.PostCoercionTransform;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.coercer.StringTypeCoercer;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;

/**
 * Represents a single output file.
 *
 * <p>The string value is turned into a relative path which is then converted to a declared artifact
 * automatically before executing the user's implementation function
 */
@BuckStyleValue
public abstract class OutputAttribute extends Attribute<String> {

  private static final TypeCoercer<String> coercer = new StringTypeCoercer();

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
  public TypeCoercer<String> getTypeCoercer() {
    return coercer;
  }

  @Override
  public PostCoercionTransform<ImmutableMap<BuildTarget, ProviderInfoCollection>, ?>
      getPostCoercionTransform() {
    return this::postCoercionTransform;
  }

  @SuppressWarnings("unused")
  Artifact postCoercionTransform(
      Object coercedValue,
      ActionRegistry registry,
      ImmutableMap<BuildTarget, ProviderInfoCollection> deps) {
    if (!(coercedValue instanceof String)) {
      throw new IllegalArgumentException(String.format("Value %s must be a String", coercedValue));
    }
    // TODO(pjameson): pass the location of the UDR invocation all the way down to the coercer
    return registry.declareArtifact((String) coercedValue, Location.BUILTIN);
  }
}
